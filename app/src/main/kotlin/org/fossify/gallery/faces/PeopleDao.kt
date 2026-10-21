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
    @Transaction
    fun mergePersons(from: Long, to: Long) {
        if (from == to) return
        reassignPerson(from, to)
        deleteConflictingCannotLinks(from, to)
        reassignCannotLinks(from, to)
        reassignAnchors(from, to)
        deletePerson(from)
    }

    // Zlúčenie viacerých osôb do jednej cieľovej — celé v jednej transakcii (buď všetky, alebo žiadna).
    @Transaction
    fun mergeManyInto(fromIds: List<Long>, to: Long) {
        fromIds.forEach { mergePersons(it, to) }
    }

    // Nová osoba + priradenie tvárí atomicky (návrh pomenovaný / presun tváre k novej osobe).
    @Transaction
    fun createPersonWithFaces(name: String, faceIds: List<Long>, now: Long): Long {
        val personId = insertPerson(PersonEntity(name = name, createdAt = now))
        faceIds.forEach { upsertAssignment(FaceAssignmentEntity(it, personId, true, now)) }
        return personId
    }

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
    @Transaction
    fun assignFaces(faceIds: List<Long>, personId: Long, now: Long) {
        faceIds.forEach { upsertAssignment(FaceAssignmentEntity(it, personId, true, now)) }
    }

    @Query("SELECT * FROM face_assignments")
    fun getAssignments(): List<FaceAssignmentEntity>

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
