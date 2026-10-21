package org.fossify.gallery.faces

import android.content.Context
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.File

// Jednorazový prepočet všetkých odtlačkov vo faces.db na v2 (FaceEmbedder.PIPELINE_MODEL_ID) NA MIESTE:
// id tvárí, rámčeky, menovky ani odmietnutia sa nemenia. Spúšťa ho IndexingService (TASK_FACE_V2) pri
// nabíjaní (ProcessingPolicy), alebo ručne z Nastavení. Dá sa kedykoľvek prerušiť (stop, odpojená
// nabíjačka) — pokračuje od tvárí, ktoré ešte nemajú hotový v2 vektor vo faces_v2.db.
//
// Neporovnávať v1 s v2: v2 vektory sa do faces.db zapíšu až keď sú hotové VŠETKY (prepnutie
// v jednej transakcii). Dovtedy appka rozpoznáva čisto vo v1 (nové tváre sa zatiaľ ukladajú bez
// odtlačku a v2 vektor čaká vo faces_v2.db), potom čisto vo v2. Tváre, ktoré sa prepočítať nedajú
// (fotka zmazaná/presunutá, nečitateľný súbor), ostanú po prepnutí bez odtlačku — menovky v people.db
// ostávajú a odtlačok im doplní denná oprava (FaceIndexer → RefreshFaces.repairUnembedded), keď sa
// súbor znova nájde. Pôvodné v1 vektory ostávajú zálohované vo faces_v2.db.
// Vzory (anchor_embeddings) vytvorené pred prepnutím sú v1: neodstraňujú sa, len sa odložia
// (záporné person_id), takže ich žiadne porovnanie ani prenos nevidí; person_id = -pôvodné.
object FaceReembedV2 {
    @Volatile
    var isRunning = false
        private set

    // priebeh pre Nastavenia (počet tvárí s hotovým v2 vektorom / všetky tváre)
    @Volatile
    var progressDone = 0
        private set

    @Volatile
    var progressTotal = 0
        private set

    private const val MAX_DECODE = 1024
    private const val MAX_ROUNDS = 4

    fun run(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (switched: Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            val models = Models(appCtx)
            try {
                val switched = when {
                    !FaceEmbedVersion.isPending(appCtx) -> true
                    FaceEmbedVersion.isFinalizing(appCtx) -> {
                        finalizeSwitch(appCtx)
                        true
                    }

                    else -> migrate(appCtx, models, onProgress)
                }
                onDone(switched)
            } catch (e: Throwable) {
                onError(e.javaClass.simpleName + (e.message?.let { ": " + it.take(140) } ?: ""))
            } finally {
                models.close()
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    // [hotové, všetky] pre Nastavenia — volať na pozadí
    fun progress(context: Context): IntArray {
        if (isRunning) return intArrayOf(progressDone, progressTotal)
        return try {
            val total = FacesDatabase.getInstance(context).FaceDao().getFaceCount()
            intArrayOf(FaceV2Staging.get(context).doneCount().coerceAtMost(total), total)
        } catch (e: Throwable) {
            intArrayOf(0, 0)
        }
    }

    // modely sa načítajú až keď je čo počítať
    private class Models(private val context: Context) {
        private var detector: FaceDetectionHelper? = null
        private var embedder: FaceEmbedder? = null
        private var landmarker: FaceLandmarkHelper? = null

        fun detector() = detector ?: FaceDetectionHelper(context).also { detector = it }
        fun embedder() = embedder ?: FaceEmbedder(context).also { embedder = it }
        fun landmarker() = landmarker ?: FaceLandmarkHelper(context).also { landmarker = it }

        fun close() {
            detector?.close()
            embedder?.close()
            landmarker?.close()
        }
    }

    private fun migrate(context: Context, models: Models, onProgress: (Int, Int) -> Unit): Boolean {
        val dao = FacesDatabase.getInstance(context).FaceDao()
        val staging = FaceV2Staging.get(context)
        val attempts = HashMap<Long, Int>()
        // viac kôl: tváre pribudnuté počas behu (rodinné balíky) a opakovanie zlyhaných pokusov
        for (round in 0 until MAX_ROUNDS) {
            if (!isRunning) return false
            val faces = dao.getEveryFace()
            val entries = staging.entries()
            val todo = faces.filter { f ->
                val id = f.id ?: return@filter false
                val e = entries[id]
                val tried = attempts[id] ?: 0
                when (e?.state) {
                    null -> tried == 0
                    EmbedVersionRules.STATE_DONE -> false
                    EmbedVersionRules.STATE_MISSING -> tried == 0
                    else -> e.tries < EmbedVersionRules.MAX_TRIES && tried < EmbedVersionRules.MAX_TRIES
                }
            }
            if (todo.isEmpty()) break
            progressTotal = faces.size
            progressDone = entries.values.count { it.state == EmbedVersionRules.STATE_DONE }
            onProgress(progressDone, progressTotal)
            for ((path, list) in todo.groupBy { it.mediaFullPath }) {
                if (!isRunning) return false
                val (ok, processed) = processPhoto(dao, staging, path, list, models)
                processed.forEach { attempts[it] = (attempts[it] ?: 0) + 1 }
                progressDone += ok
                onProgress(progressDone, progressTotal)
            }
        }
        if (!isRunning) return false
        if (!EmbedVersionRules.readyToSwitch(tally(dao.getEveryFace(), staging.entries()))) return false
        FaceEmbedVersion.markSwitchStarted(context, System.currentTimeMillis())
        finalizeSwitch(context)
        return true
    }

    // v2 vektory tvárí jednej fotky do faces_v2.db; vráti (počet hotových, id spracovaných tvárí).
    // Cesty sa počas behu môžu zmeniť (FaceRelinker preklápa presunuté fotky cez UPDATE media_full_path):
    // spracujú sa len tváre, ktoré sú na tejto ceste aj TERAZ; ostatné nechá na ďalšie kolo pod novou cestou.
    private fun processPhoto(
        dao: FaceDao,
        staging: FaceV2Staging,
        path: String,
        candidates: List<FaceEntity>,
        models: Models,
    ): Pair<Int, List<Long>> {
        val currentIds = dao.getFacesForPath(path).mapNotNull { it.id }.toHashSet()
        val faces = candidates.filter { it.id in currentIds }
        val ids = faces.mapNotNull { f -> f.id?.let { it to f.embedding } }
        val processed = ids.map { it.first }
        if (faces.isEmpty()) return 0 to processed
        if (!File(path).exists()) {
            staging.putFailed(ids, EmbedVersionRules.STATE_MISSING)
            return 0 to processed
        }
        val res = try {
            UprightDecoder.decode(path, MAX_DECODE)
        } catch (e: Throwable) {
            null
        }
        if (res == null) {
            staging.putFailed(ids, EmbedVersionRules.STATE_FAILED)
            return 0 to processed
        }
        val bmp = res.bitmap
        val done = ArrayList<Triple<Long, ByteArray, ByteArray?>>()
        val failed = ArrayList<Pair<Long, ByteArray?>>()
        var full: FullResCrop? = null
        try {
            val detector = models.detector()
            // Aktuálna detekcia (ak sedí na uložený rámček) dá aj body očí pre 2-bodové zarovnanie a
            // pomôže pri rámčekoch zo starého neotočeného priestoru (pred 0.47). Rámčeky sa NEMENIA.
            val detected = try {
                detector.detect(bmp)
            } catch (e: Throwable) {
                emptyList()
            }
            val all = dao.getFacesForPath(path)
            val match = RefreshFaces.matchOldFaces(all, detected, res, bmp.width, bmp.height)
            val byId = HashMap<Long, FaceDetectionHelper.DetectedFace>()
            for ((di, oi) in match.withIndex()) {
                val id = all.getOrNull(oi)?.id ?: continue
                byId[id] = detected[di]
            }
            full = FullResCrop.open(path, MAX_DECODE, bmp.width, bmp.height)
            for (f in faces) {
                val id = f.id ?: continue
                val d = byId[id] ?: FaceDetectionHelper.DetectedFace(
                    f.bboxLeft, f.bboxTop, f.bboxRight, f.bboxBottom, f.score, 0f, 0f, 0f, 0f, false,
                )
                val e = FaceAligner.embedDetected(bmp, d, models.landmarker(), models.embedder(), detector, full)
                if (e != null) done.add(Triple(id, e, f.embedding)) else failed.add(id to f.embedding)
            }
        } finally {
            full?.close()
            bmp.recycle()
        }
        staging.putDone(done)
        staging.putFailed(failed, EmbedVersionRules.STATE_FAILED)
        return done.size to processed
    }

    private fun tally(faces: List<FaceEntity>, entries: Map<Long, FaceV2Staging.Entry>): EmbedVersionRules.Tally {
        var done = 0
        var missing = 0
        var exhausted = 0
        var transient = 0
        val rootOk = HashMap<String, Boolean>()
        for (f in faces) {
            val id = f.id ?: continue
            val e = entries[id] ?: continue
            when (e.state) {
                EmbedVersionRules.STATE_DONE -> done++
                EmbedVersionRules.STATE_MISSING -> {
                    // fotku medzičasom našiel relinker pod novou cestou → nie je chýbajúca, prepočíta sa
                    if (File(f.mediaFullPath).exists()) continue
                    val root = EmbedVersionRules.volumeRoot(f.mediaFullPath)
                    val ok = root == null || rootOk.getOrPut(root) { File(root).let { it.isDirectory && it.list() != null } }
                    if (ok) missing++ else transient++
                }

                else -> if (e.tries >= EmbedVersionRules.MAX_TRIES) exhausted++
            }
        }
        return EmbedVersionRules.Tally(faces.count { it.id != null }, done, missing, exhausted, transient)
    }

    // Prepnutie (po FaceEmbedVersion.markSwitchStarted). Idempotentné — po páde sa zopakuje celé.
    private fun finalizeSwitch(context: Context) {
        val at = FaceEmbedVersion.switchedAt(context) ?: return
        val staging = FaceV2Staging.get(context)
        val vectors = staging.doneVectors()
        val facesDb = FacesDatabase.getInstance(context)
        val dao = facesDb.FaceDao()
        facesDb.runInTransaction {
            for ((id, v) in vectors) dao.updateEmbedding(id, v)
            // ostatné tváre s (v1) odtlačkom: neprepočítateľné alebo pribudnuté medzičasom → bez odtlačku
            // (v1 sa zálohuje), aby sa v1 s v2 nikdy neporovnávali; denná oprava im v2 doplní
            val sql = facesDb.openHelper.writableDatabase
            val stale = ArrayList<Pair<Long, ByteArray>>()
            sql.query("SELECT id, embedding FROM faces WHERE embedding IS NOT NULL").use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (!vectors.containsKey(id)) stale.add(id to c.getBlob(1))
                }
            }
            for ((id, v1) in stale) {
                staging.backupV1(id, v1)
                sql.execSQL("UPDATE faces SET embedding = NULL WHERE id = ?", arrayOf<Any>(id))
            }
        }
        // v1 vzory (Picasa aj rodinné) sa odložia: záporné person_id, nič sa nemaže; potvrdené
        // priradenia (face_assignments) sa nemenia
        val peopleDb = PeopleDatabase.getInstance(context)
        var picasaParked = false
        peopleDb.runInTransaction {
            peopleDb.openHelper.writableDatabase.execSQL(
                "UPDATE anchor_embeddings SET person_id = -person_id WHERE person_id > 0 AND created_at < ?",
                arrayOf<Any>(at),
            )
            picasaParked = peopleDb.PeopleDao().getAllAnchors().any { it.personId < 0 && it.source == PicasaImportService.SOURCE }
        }
        FaceEmbedVersion.markSwitchFinished(context, picasaParked)
    }
}
