package org.fossify.gallery.faces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface PeopleDao {
    // --- osoby ---
    @Insert
    fun insertPerson(person: PersonEntity): Long

    @Query("UPDATE persons SET name = :name WHERE id = :id")
    fun renamePerson(id: Long, name: String)

    @Query("DELETE FROM persons WHERE id = :id")
    fun deletePerson(id: Long)

    // Úplné odstránenie osoby (priradenia, negatívne pravidlá, vzory, osoba) v JEDNEJ transakcii —
    // pád uprostred nesmie nechať napoly zmazanú osobu.
    @Transaction
    fun deletePersonCompletely(personId: Long) {
        deleteAssignmentsForPerson(personId)
        deleteCannotLinksForPerson(personId)
        deleteAnchorsForPerson(personId)
        deletePerson(personId)
    }

    @Transaction
    fun deletePersonsCompletely(personIds: List<Long>) {
        personIds.forEach { deletePersonCompletely(it) }
    }

    // Zlúčenie osoby [from] do [to] v JEDNEJ transakcii: presunú sa tváre, negatívne pravidlá aj vzorové
    // embeddingy (Picasa), potom [from] zanikne. Bez transakcie by pád alebo výnimka nechali zlúčenie napoly.
    // E2: pred zlúčením sa zapíše merge_log (na vrátenie); [groupIds] = skupiny osôb (extras.db, MergeLogCodec
    // .encodeGroups) — prečíta ich volajúci, presun členstiev robí po zlúčení sám.
    @Transaction
    fun mergePersons(from: Long, to: Long, now: Long, groupIds: String?) {
        if (from == to) return
        logMerge(from, to, now, groupIds)
        reassignPerson(from, to)
        deleteConflictingCannotLinks(from, to)
        reassignCannotLinks(from, to)
        deleteContradictingCannotLinks(to)
        reassignAnchors(from, to)
        deletePerson(from)
    }

    // stav zlúčenej osoby pred zlúčením (všetko, čo mergePersons zmení)
    fun logMerge(from: Long, to: Long, now: Long, groupIds: String?): Long {
        val person = getPerson(from)
        val assigns = getAssignmentsForPerson(from)
        val faces = assigns.mapTo(HashSet()) { it.faceId }
        val fromLinks = getCannotLinkFaces(from)
        val toLinks = getCannotLinkFaces(to).toHashSet()
        val links = MergeLogCodec.Links(
            from = fromLinks,
            moved = fromLinks.filter { it !in toLinks },
            toRemoved = toLinks.filter { it in faces },
        )
        return insertMergeLog(
            MergeLogEntity(
                fromPersonId = from,
                toPersonId = to,
                faceIds = MergeLogCodec.encodeAssignments(assigns),
                cannotLinks = MergeLogCodec.encodeLinks(links),
                createdAt = now,
                fromName = person?.name,
                fromCreatedAt = person?.createdAt,
                anchorIds = MergeLogCodec.encodeIds(getAnchorIdsForPerson(from)),
                groupIds = groupIds,
            )
        )
    }

    // Zlúčenie viacerých osôb do jednej cieľovej — celé v jednej transakcii (buď všetky, alebo žiadna).
    // [groupIds] = skupiny každej zlúčenej osoby (MergeLogCodec.encodeGroups), chýbajúca = bez skupín.
    @Transaction
    fun mergeManyInto(fromIds: List<Long>, to: Long, now: Long, groupIds: Map<Long, String>) {
        fromIds.forEach { mergePersons(it, to, now, groupIds[it]) }
    }

    // E2 — vrátenie zlúčenia z merge_log v jednej transakcii: pôvodná osoba (rovnaké id, meno), jej tváre
    // (len tie, ktoré stále patria cieľovej osobe), „nie je“ a vzory. Skupiny (extras.db) podľa výsledku
    // doplní volajúci. Vráti výsledok plánu (Refused = nič sa nezapísalo).
    @Transaction
    fun undoMerge(logId: Long, now: Long): MergeUndo.Outcome? {
        val log = getMergeLog(logId) ?: return null
        val state = MergeUndo.State(
            personIds = getPersons().mapTo(HashSet()) { it.id },
            targetFaces = getAssignmentsForPerson(log.toPersonId).mapTo(HashSet()) { it.faceId },
            targetLinks = getCannotLinkFaces(log.toPersonId).toHashSet(),
        )
        val outcome = MergeUndo.plan(log, state)
        if (outcome !is MergeUndo.Outcome.Ready) return outcome
        val plan = outcome.plan
        insertPerson(PersonEntity(id = plan.personId, name = plan.name, createdAt = plan.createdAt))
        plan.assignments.forEach { upsertAssignment(it) }
        plan.deleteTargetLinks.forEach { deleteCannotLink(it, plan.targetId) }
        plan.insertFromLinks.forEach { insertCannotLink(CannotLinkEntity(it, plan.personId)) }
        plan.insertTargetLinks.forEach { insertCannotLink(CannotLinkEntity(it, plan.targetId)) }
        plan.anchorIds.chunked(MergeUndo.ID_CHUNK).forEach { moveAnchors(it, plan.targetId, plan.personId) }
        markMergeUndone(logId, now)
        return outcome
    }

    // Nová osoba + priradenie tvárí atomicky (návrh pomenovaný / presun tváre k novej osobe).
    // E2: [source] = pôvod (AssignmentSource), [confidence] = skóre tvárí, ak je známe.
    @Transaction
    fun createPersonWithFaces(
        name: String,
        faceIds: List<Long>,
        now: Long,
        source: String,
        confidence: Map<Long, Float>,
    ): Long {
        val personId = insertPerson(PersonEntity(name = name, createdAt = now))
        faceIds.forEach { upsertAssignment(FaceAssignmentEntity(it, personId, true, now, source, confidence[it])) }
        return personId
    }

    @Query("SELECT * FROM persons WHERE id = :id")
    fun getPerson(id: Long): PersonEntity?

    @Query("SELECT * FROM persons")
    fun getPersons(): List<PersonEntity>

    // --- priradenia tvárí ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAssignment(assignment: FaceAssignmentEntity)

    @Query("DELETE FROM face_assignments WHERE face_id = :faceId")
    fun deleteAssignment(faceId: Long)

    // zruší priradenie tváre LEN ak patrí práve tejto osobe (cudzie priradenie ostane nedotknuté)
    @Query("DELETE FROM face_assignments WHERE face_id = :faceId AND person_id = :personId")
    fun deleteAssignmentOfPerson(faceId: Long, personId: Long)

    // Ručné priradenie viacerých tvárí k osobe atomicky (všetky, alebo žiadna).
    // E2: [source] = pôvod (AssignmentSource), [confidence] = skóre tvárí, ak je známe.
    @Transaction
    fun assignFaces(faceIds: List<Long>, personId: Long, now: Long, source: String, confidence: Map<Long, Float>) {
        faceIds.forEach { upsertAssignment(FaceAssignmentEntity(it, personId, true, now, source, confidence[it])) }
    }

    @Query("SELECT * FROM face_assignments")
    fun getAssignments(): List<FaceAssignmentEntity>

    @Query("SELECT * FROM face_assignments WHERE person_id = :personId")
    fun getAssignmentsForPerson(personId: Long): List<FaceAssignmentEntity>

    // najviac ~500 id naraz (limit premenných SQLite), volajúci delí zoznam (MergeUndo.ID_CHUNK)
    @Query("SELECT * FROM face_assignments WHERE face_id IN (:faceIds)")
    fun getAssignmentsForFaces(faceIds: List<Long>): List<FaceAssignmentEntity>

    @Query("UPDATE face_assignments SET person_id = :to WHERE person_id = :from")
    fun reassignPerson(from: Long, to: Long)

    @Query("DELETE FROM face_assignments WHERE person_id = :personId")
    fun deleteAssignmentsForPerson(personId: Long)

    // --- negatívne pravidlá (učenie) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCannotLink(link: CannotLinkEntity)

    @Query("SELECT * FROM cannot_links")
    fun getCannotLinks(): List<CannotLinkEntity>

    // (face_id, person_id) je kompozitný primárny kľúč: ak tvár „nie je" ani [from], ani [to], holý UPDATE
    // by vytvoril duplicitný riadok a vyhodil SQLiteConstraintException — konfliktné riadky [from] sa preto
    // zahodia (platné pravidlo pre [to] už existuje) a až potom sa zvyšok presunie.
    @Query(
        "DELETE FROM cannot_links WHERE person_id = :from AND face_id IN " +
            "(SELECT face_id FROM cannot_links WHERE person_id = :to)"
    )
    fun deleteConflictingCannotLinks(from: Long, to: Long)

    @Query("UPDATE cannot_links SET person_id = :to WHERE person_id = :from")
    fun reassignCannotLinks(from: Long, to: Long)

    // C8 — po zlúčení: „tvár nie je osoba“ pri tvári, ktorá je osobe priradená, si protirečí (napr. tvár
    // odmietnutá pri Jánovi a potvrdená pri „Jankovi“, ktorý sa do Jána zlúčil). Priradenie má prednosť,
    // inak by tvár ako negatívny prototyp (KnnModel) ťahala skóre vlastnej osoby nadol. Len dotaz, bez zmeny schémy.
    @Query(
        "DELETE FROM cannot_links WHERE person_id = :personId AND face_id IN " +
            "(SELECT face_id FROM face_assignments WHERE person_id = :personId)"
    )
    fun deleteContradictingCannotLinks(personId: Long)

    // „Toto nie je táto osoba" pre viac tvárí naraz: zruší ich priradenie k osobe a zapíše negatívne pravidlo
    // (aby sa už nenavrhli) — atomicky.
    @Transaction
    fun rejectFacesForPerson(faceIds: List<Long>, personId: Long) {
        faceIds.forEach {
            deleteAssignmentOfPerson(it, personId)
            insertCannotLink(CannotLinkEntity(it, personId))
        }
    }

    @Query("DELETE FROM cannot_links WHERE person_id = :personId")
    fun deleteCannotLinksForPerson(personId: Long)

    @Query("DELETE FROM cannot_links WHERE face_id = :faceId AND person_id = :personId")
    fun deleteCannotLink(faceId: Long, personId: Long)

    @Query("SELECT face_id FROM cannot_links WHERE person_id = :personId")
    fun getCannotLinkFaces(personId: Long): List<Long>

    // --- záznam zlúčení (E2) ---
    @Insert
    fun insertMergeLog(entry: MergeLogEntity): Long

    @Query("SELECT * FROM merge_log WHERE id = :id")
    fun getMergeLog(id: Long): MergeLogEntity?

    // posledné nevrátené zlúčenia (Ľudia → „Vrátiť zlúčenie“)
    @Query("SELECT * FROM merge_log WHERE undone_at IS NULL ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun getRecentMerges(limit: Int): List<MergeLogEntity>

    @Query("UPDATE merge_log SET undone_at = :at WHERE id = :id")
    fun markMergeUndone(id: Long, at: Long)

    // --- import z Picasy (anchor embeddingy + dedup mien) ---
    @Query("SELECT * FROM persons WHERE name = :name LIMIT 1")
    fun findPersonByName(name: String): PersonEntity?

    @Insert
    fun insertAnchor(anchor: AnchorEmbeddingEntity)

    @Query("SELECT * FROM anchor_embeddings")
    fun getAllAnchors(): List<AnchorEmbeddingEntity>

    @Query("DELETE FROM anchor_embeddings")
    fun deleteAllAnchors()

    @Query("SELECT embedding FROM anchor_embeddings WHERE person_id = :personId")
    fun getAnchorEmbeddings(personId: Long): List<ByteArray>

    @Query("DELETE FROM anchor_embeddings WHERE person_id = :personId")
    fun deleteAnchorsForPerson(personId: Long)

    @Query("UPDATE anchor_embeddings SET person_id = :to WHERE person_id = :from")
    fun reassignAnchors(from: Long, to: Long)

    @Query("SELECT id FROM anchor_embeddings WHERE person_id = :personId")
    fun getAnchorIdsForPerson(personId: Long): List<Long>

    // vrátenie zlúčenia: len vybrané vzory, ktoré stále patria cieľovej osobe
    @Query("UPDATE anchor_embeddings SET person_id = :to WHERE person_id = :from AND id IN (:ids)")
    fun moveAnchors(ids: List<Long>, from: Long, to: Long)

    // rodinné prepojenie (balík D7): sada odtlačkov jedného zdroja sa nahrádza, nie pridáva
    // (len dotaz, bez zmeny schémy)
    @Query("DELETE FROM anchor_embeddings WHERE source = :source AND person_id = :personId")
    fun deleteAnchorsFromSource(personId: Long, source: String)

    @Query("SELECT COUNT(*) FROM anchor_embeddings")
    fun getAnchorCount(): Int

    @Insert
    fun insertImportManifest(manifest: ImportManifestEntity)

    @Query("SELECT * FROM import_manifests WHERE manifest_hash = :hash LIMIT 1")
    fun findImportManifest(hash: String): ImportManifestEntity?
}
