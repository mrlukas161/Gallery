package org.fossify.gallery.faces

import android.content.Context
import org.fossify.gallery.family.ContentKeyDao
import org.fossify.gallery.family.ContentKeyStore
import org.fossify.gallery.family.FaceMatch
import org.fossify.gallery.family.FamilyDatabase
import org.fossify.gallery.family.RelBox
import org.fossify.gallery.helpers.IndexStatus
import java.io.File
import java.util.concurrent.Callable

// A5: znovunájdenie presunutých fotiek podľa obsahového kľúča — beží vo FaceIndexer PRED detekciou.
//
// Keď fotka s tvárami zmizne zo svojej cesty (presun, premenovanie, skrytie „.meno", SD karta pod
// inou cestou, obnova z Koša pod iným menom), indexer by ju na novej ceste detegoval nanovo: nové id,
// nepomenované duplikáty a menovky „stratené z dohľadu". Relinker preto nájde chýbajúce cesty
// (rovnaký test ako denné upratovanie, IndexStatus.reallyMissingTester), k nim fotky s rovnakou
// veľkosťou a rovnakým ContentKey a pri jednoznačnej zhode 1:1 tváre preklopí cez UPDATE na mieste
// (id, menovky, „nie je to on" aj odložené tváre ostanú). Nejednoznačná zhoda = nič.
//
// Ak nová cesta už má tváre z indexera: duplikáty sa zmažú LEN vtedy, keď na ich id nič neodkazuje
// (people.db, extras.db, family.db proposals); inak sa UPDATE nerobí a priradenia sa len prenesú
// cez prekryv rámčekov (FaceMatch.best). Schéma faces.db sa nemení.
//
// Kľúč starej cesty musí byť v content_keys ešte PRED presunom — preto sa tu pri každom spracovaní
// knižnice dopočítajú kľúče ciest s tvárami (pri nabíjaní všetky, inak po dávkach; RelinkData).
object FaceRelinker {
    private const val PREFS = "galeria_faces"

    // prenos priradení (Transfer) sa pre pár stará → nová cesta robí len raz: stará cesta ostáva
    // chýbať navždy a opakovaný prenos by vrátil priradenie, ktoré používateľ medzitým zrušil
    private const val KEY_TRANSFERRED = "faces_relink_transferred"
    private const val PAIR_SEPARATOR = "\u0000"

    private enum class Outcome { MOVED, TRANSFERRED, SKIPPED }

    // Spustí sa raz na začiatku FaceIndexer.index (mimo hlavného vlákna). Nikdy nevyhodí výnimku.
    // Vráti počet preklopených fotiek.
    fun relink(context: Context, dao: FaceDao, running: () -> Boolean): Int {
        val app = context.applicationContext
        var moved = 0
        try {
            val keyDao = FamilyDatabase.getInstance(app).contentKeyDao()
            val keys = ContentKeyStore(keyDao)
            val facePaths = dao.getPathsWithFaces()
            val tester = if (facePaths.isEmpty()) null else IndexStatus.reallyMissingTester(app)
            if (tester != null) moved = relinkMissing(app, dao, keyDao, keys, facePaths.filter(tester), running)
            if (facePaths.isNotEmpty()) RelinkData.precomputeKeys(app, keys, dao.getPathsWithFaces(), running)
        } catch (ignored: Throwable) {
            // relinker je len poistka — indexovanie musí pokračovať aj bez neho
        }
        // fotky čakajúce na prepracovanie po otočení (A6), ktoré sa minule nedokončili
        FaceRefreshQueue.processAsync(app)
        return moved
    }

    private fun relinkMissing(
        app: Context,
        dao: FaceDao,
        keyDao: ContentKeyDao,
        keys: ContentKeyStore,
        missingPaths: List<String>,
        running: () -> Boolean,
    ): Int {
        val missing = missingPaths.mapNotNull { path ->
            keyDao.get(path)?.let { RelinkPlanner.Missing(path, it.size, it.contentKey) }
        }
        if (missing.isEmpty()) return 0
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val transferred = HashSet(prefs.getStringSet(KEY_TRANSFERRED, null).orEmpty())
        val matches = RelinkPlanner.plan(missing, findCandidates(app, keys, missing, running))
            .filter { pairKey(it) !in transferred }
        if (matches.isEmpty()) return 0
        val referenced = RelinkData.loadReferencedIds(app)
        var moved = 0
        for (match in matches.takeWhile { running() }) {
            val outcome = try {
                apply(app, dao, keys, match, referenced)
            } catch (ignored: Throwable) {
                Outcome.SKIPPED // jedna fotka nesmie zastaviť ostatné; nič sa nezapísalo napoly (transakcia)
            }
            if (outcome == Outcome.MOVED) moved++
            if (outcome == Outcome.TRANSFERRED) transferred.add(pairKey(match))
        }
        prefs.edit().putStringSet(KEY_TRANSFERRED, transferred).apply()
        return moved
    }

    private fun pairKey(match: RelinkPlanner.Match) = match.missing.path + PAIR_SEPARATOR + match.candidate.path

    private fun apply(
        app: Context,
        dao: FaceDao,
        keys: ContentKeyStore,
        match: RelinkPlanner.Match,
        referenced: Set<Long>?,
    ): Outcome {
        val oldPath = match.missing.path
        val newPath = match.candidate.path
        val oldFaces = dao.getFacesForPath(oldPath)
        if (oldFaces.isEmpty()) return Outcome.SKIPPED
        val newFaces = dao.getFacesForPath(newPath)
        return when (val action = RelinkPlanner.decide(slots(oldFaces), slots(newFaces), referenced)) {
            is RelinkPlanner.Action.Move -> {
                val storeId = match.candidate.mediaStoreId.takeIf { it != RelinkData.KEEP_STORE_ID }
                    ?: oldFaces.first().mediaStoreId
                if (move(app, dao, match, storeId, newFaces, action.deleteIds)) {
                    keys.forget(oldPath)
                    keys.keyFor(newPath)
                    Outcome.MOVED
                } else {
                    Outcome.SKIPPED
                }
            }

            RelinkPlanner.Action.Transfer -> {
                transferAssignments(app, newPath, oldFaces, newFaces)
                Outcome.TRANSFERRED
            }
        }
    }

    private fun slots(faces: List<FaceEntity>): List<RelinkPlanner.FaceSlot> =
        faces.mapNotNull { f -> f.id?.let { RelinkPlanner.FaceSlot(it, f.faceIndex) } }

    // Preklopenie v JEDNEJ transakcii faces.db: (zmazanie overených duplikátov), UPDATE ciest, prenos značky.
    private fun move(
        app: Context,
        dao: FaceDao,
        match: RelinkPlanner.Match,
        storeId: Long,
        newFacesAtPlan: List<FaceEntity>,
        deleteIds: List<Long>,
    ): Boolean {
        val oldPath = match.missing.path
        val newPath = match.candidate.path
        // odkazy sa mohli zmeniť od načítania (používateľ práve pomenoval tvár) — over znova
        val referencedNow = if (deleteIds.isEmpty()) emptySet() else RelinkData.loadReferencedIds(app)
        if (referencedNow == null || deleteIds.any { it in referencedNow }) return false
        val planned = newFacesAtPlan.mapNotNull { it.id }.toSet()
        return FacesDatabase.getInstance(app).runInTransaction(
            Callable {
                // medzitým pribudli/ubudli tváre na novej ceste → nerobiť nič, skúsi sa pri ďalšom behu
                if (dao.getFacesForPath(newPath).mapNotNull { it.id }.toSet() != planned) return@Callable false
                if (deleteIds.isNotEmpty() && dao.deleteUnreferencedDuplicates(newPath, deleteIds) != deleteIds.size) {
                    error("duplikáty sa zmenili") // výnimka = rollback celej transakcie
                }
                if (dao.relinkPath(oldPath, newPath, storeId) <= 0) error("nič na preklopenie")
                val marker = dao.getIndexedPhoto(oldPath)
                val indexedAt = marker?.indexedAt ?: System.currentTimeMillis()
                dao.insertIndexedPhoto(IndexedPhotoEntity(newPath, dao.getFaceCountForPath(newPath), indexedAt))
                if (marker != null) dao.deleteIndexedPhoto(oldPath)
                true
            }
        )
    }

    // Nová cesta má tváre s odkazmi → bez UPDATE; priradenia, „nie je to on" a odloženie sa prenesú
    // z jednoznačne spárovaných starých tvárí. Existujúce priradenie novej tváre sa nikdy neprepíše.
    private fun transferAssignments(
        app: Context,
        newPath: String,
        oldFaces: List<FaceEntity>,
        newFaces: List<FaceEntity>,
    ) {
        val geometry = RelinkData.rawGeometry(newPath) ?: return
        val transform = RelBox.exifTransform(geometry[2])
        fun boxes(f: FaceEntity) =
            FaceMatch.relBoxes(
                f.bboxLeft, f.bboxTop, f.bboxRight, f.bboxBottom, geometry[0], geometry[1], transform, f.boxSpace,
            )
        val oldBoxes = oldFaces.mapNotNull { f -> f.id?.let { it to boxes(f) } }.filter { it.second.isNotEmpty() }
        // nové tváre z indexera sú vždy v upright priestore → len prvá hypotéza
        val newBoxes = newFaces.mapNotNull { f -> f.id?.let { it to boxes(f).take(1) } }
            .filter { it.second.isNotEmpty() }
        val pairs = RelinkPlanner.transferPairs(oldBoxes, newBoxes)
        if (pairs.isEmpty()) return

        val peopleDb = PeopleDatabase.getInstance(app)
        val people = peopleDb.PeopleDao()
        val assignments = people.getAssignments().associateBy { it.faceId }
        val cannot = people.getCannotLinks().groupBy({ it.faceId }, { it.personId })
        val now = System.currentTimeMillis()
        peopleDb.runInTransaction(
            Runnable {
                for ((oldId, newId) in pairs) {
                    val old = assignments[oldId]
                    val existing = assignments[newId]
                    if (old != null && existing == null) {
                        // E2: pôvod (source) aj istota sa prenesú so starým priradením
                        people.upsertAssignment(old.copy(faceId = newId, updatedAt = now))
                    }
                    val owner = existing?.personId ?: old?.personId
                    cannot[oldId].orEmpty().filter { it != owner }
                        .forEach { people.insertCannotLink(CannotLinkEntity(newId, it)) }
                }
            }
        )
        transferIgnored(app, pairs, assignments.keys)
    }

    // odložená stará tvár → odložiť aj novú, ak žiadna z nich nemá osobu
    private fun transferIgnored(app: Context, pairs: Map<Long, Long>, assigned: Set<Long>) {
        val extras = ExtrasDatabase.getInstance(app).ExtrasDao()
        val ignored = extras.getIgnoredIds().toHashSet()
        pairs.filter { (oldId, newId) -> oldId in ignored && oldId !in assigned && newId !in assigned }
            .forEach { (_, newId) -> extras.insertIgnored(IgnoredFaceEntity(newId)) }
    }

    // Kandidáti: fotky z MediaStore s rovnakou veľkosťou ako niektorá chýbajúca cesta a skryté/odkryté
    // dvojičky („.meno" ↔ „meno" v tom istom priečinku), ktoré MediaStore nemusí poznať.
    private fun findCandidates(
        app: Context,
        keys: ContentKeyStore,
        missing: List<RelinkPlanner.Missing>,
        running: () -> Boolean,
    ): List<RelinkPlanner.Candidate> {
        val sizes = missing.mapTo(HashSet()) { it.size }
        val fromStore = RelinkData.queryImagesWithSize(app, sizes)
        val known = fromStore.mapTo(HashSet()) { it.second }
        val twins = missing.mapNotNull { RelinkData.hiddenTwin(it.path) }
            .filter { it !in known }
            .map { RelinkData.KEEP_STORE_ID to it }
        return (fromStore + twins).asSequence()
            .takeWhile { running() }
            .mapNotNull { (id, path) -> candidate(keys, path, id, sizes) }
            .toList()
    }

    private fun candidate(
        keys: ContentKeyStore,
        path: String,
        storeId: Long,
        sizes: Set<Long>,
    ): RelinkPlanner.Candidate? {
        val file = File(path)
        val size = if (file.isFile) file.length() else -1L
        if (size !in sizes) return null
        val key = keys.keyFor(path) ?: return null
        return RelinkPlanner.Candidate(path, size, key, storeId)
    }
}
