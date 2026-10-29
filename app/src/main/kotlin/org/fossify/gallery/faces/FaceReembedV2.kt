package org.fossify.gallery.faces

import android.content.Context
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.helpers.BackgroundPriority
import org.fossify.gallery.helpers.HeavyGate
import org.fossify.gallery.helpers.HeavySlot
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
@Suppress("TooManyFunctions") // kroky jedného prepočtu (kolo, fotka, detekcia, bilancia, prepnutie)
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
    private const val MAX_ERROR_LEN = 140

    // Modely sa načítajú až pri prvej tvári na prepočet (FaceModels, s miestom v HeavyGate);
    // onDone/onError až po ich zatvorení. Zastavenie počas čakania na miesto = nedokončené (onDone(false)).
    @Suppress("TooGenericExceptionCaught") // chyba ľubovoľného modelu sa len ohlási, prepočet pokračuje nabudúce
    fun run(
        context: Context,
        onWaiting: () -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (switched: Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            var switched = false
            var error: String? = null
            BackgroundPriority.run {
                val slot = HeavySlot({ isRunning }, onWaiting, onResume = { onProgress(progressDone, progressTotal) })
                val models = FaceModels(appCtx, slot)
                try {
                    switched = when {
                        !FaceEmbedVersion.isPending(appCtx) -> true
                        FaceEmbedVersion.isFinalizing(appCtx) -> {
                            finalizeSwitch(appCtx)
                            true
                        }

                        else -> migrate(appCtx, models, onProgress)
                    }
                } catch (ignored: HeavyGate.Stopped) {
                    switched = false
                } catch (e: Throwable) {
                    error = e.javaClass.simpleName + (e.message?.let { ": " + it.take(MAX_ERROR_LEN) } ?: "")
                } finally {
                    models.close()
                    isRunning = false
                }
            }
            val err = error
            if (err != null) onError(err) else onDone(switched)
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
        } catch (ignored: Throwable) {
            intArrayOf(0, 0)
        }
    }

    private fun migrate(context: Context, models: FaceModels, onProgress: (Int, Int) -> Unit): Boolean {
        val dao = FacesDatabase.getInstance(context).FaceDao()
        val staging = FaceV2Staging.get(context)
        val attempts = HashMap<Long, Int>()
        // viac kôl: tváre pribudnuté počas behu (rodinné balíky, preklopené cesty) a opakovanie zlyhaných
        var rounds = 0
        var more = true
        while (more && rounds < MAX_ROUNDS && isRunning) {
            more = runRound(dao, staging, models, attempts, onProgress)
            rounds++
        }
        if (!isRunning) return false
        if (!EmbedVersionRules.readyToSwitch(tally(dao.getFaceRefs(), staging.entries()))) return false
        FaceEmbedVersion.markSwitchStarted(context, System.currentTimeMillis())
        finalizeSwitch(context)
        return true
    }

    // Jedno kolo nad aktuálnym zoznamom tvárí (číta sa nanovo, cesty sa medzitým mohli zmeniť).
    // false = nebolo čo robiť alebo zastavené.
    private fun runRound(
        dao: FaceDao,
        staging: FaceV2Staging,
        models: FaceModels,
        attempts: HashMap<Long, Int>,
        onProgress: (Int, Int) -> Unit,
    ): Boolean {
        // len id a cesty (bez stĺpca embedding) — odtlačky sa čítajú až po fotkách v processPhoto
        val faces = dao.getFaceRefs()
        val entries = staging.entries()
        val todo = faces.filter { f ->
            entries[f.id].let { EmbedVersionRules.needsAttempt(it?.state, it?.tries ?: 0, attempts[f.id] ?: 0) }
        }
        if (todo.isEmpty()) return false
        progressTotal = faces.size
        progressDone = entries.values.count { it.state == EmbedVersionRules.STATE_DONE }
        onProgress(progressDone, progressTotal)
        for ((path, list) in todo.groupBy({ it.mediaFullPath }, { it.id })) {
            if (!isRunning) return false
            val (ok, processed) = processPhoto(dao, staging, path, list.toHashSet(), models)
            processed.forEach { attempts[it] = (attempts[it] ?: 0) + 1 }
            progressDone += ok
            onProgress(progressDone, progressTotal)
        }
        return true
    }

    // v2 vektory tvárí jednej fotky do faces_v2.db; vráti (počet hotových, id spracovaných tvárí).
    // Cesty sa počas behu môžu zmeniť (FaceRelinker preklápa presunuté fotky cez UPDATE media_full_path):
    // spracujú sa len tváre, ktoré sú na tejto ceste aj TERAZ; ostatné nechá na ďalšie kolo pod novou cestou.
    private fun processPhoto(
        dao: FaceDao,
        staging: FaceV2Staging,
        path: String,
        candidates: Set<Long>,
        models: FaceModels,
    ): Pair<Int, List<Long>> {
        // aktuálne tváre fotky (aj s v1 odtlačkom na zálohu); kandidáti, ktorí už na ceste nie sú, idú do ďalšieho kola
        val current = dao.getFacesForPath(path)
        val faces = current.filter { it.id in candidates }
        val ids = faces.mapNotNull { f -> f.id?.let { it to f.embedding } }
        val processed = ids.map { it.first }
        val res = if (faces.isEmpty() || !File(path).exists()) null else decode(path)
        if (res == null) {
            val state = if (File(path).exists()) EmbedVersionRules.STATE_FAILED else EmbedVersionRules.STATE_MISSING
            staging.putFailed(ids, state)
            return 0 to processed
        }
        val bmp = res.bitmap
        val done = ArrayList<Triple<Long, ByteArray, ByteArray?>>()
        val failed = ArrayList<Pair<Long, ByteArray?>>()
        val full = FullResCrop.open(path, MAX_DECODE, bmp.width, bmp.height)
        try {
            val detector = models.detector()
            val byId = detectionsById(current, res, detector)
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

    private fun decode(path: String): UprightDecoder.Result? = try {
        UprightDecoder.decode(path, MAX_DECODE)
    } catch (ignored: Throwable) {
        null
    }

    // Aktuálna detekcia (ak sedí na uložený rámček) dá aj body očí pre 2-bodové zarovnanie a pomôže pri
    // rámčekoch zo starého neotočeného priestoru (pred 0.47). Uložené rámčeky sa tu NEMENIA.
    private fun detectionsById(
        old: List<FaceEntity>,
        res: UprightDecoder.Result,
        detector: FaceDetectionHelper,
    ): Map<Long, FaceDetectionHelper.DetectedFace> {
        val bmp = res.bitmap
        val detected = try {
            detector.detect(bmp)
        } catch (ignored: Throwable) {
            emptyList()
        }
        val match = RefreshFaces.matchOldFaces(old, detected, res, bmp.width, bmp.height)
        val byId = HashMap<Long, FaceDetectionHelper.DetectedFace>()
        for ((di, oi) in match.withIndex()) {
            old.getOrNull(oi)?.id?.let { byId[it] = detected[di] }
        }
        return byId
    }

    private fun tally(faces: List<FaceRef>, entries: Map<Long, FaceV2Staging.Entry>): EmbedVersionRules.Tally {
        var done = 0
        var missing = 0
        var exhausted = 0
        var transient = 0
        val rootOk = HashMap<String, Boolean>()
        for (f in faces) {
            val e = entries[f.id] ?: continue
            when {
                e.state == EmbedVersionRules.STATE_DONE -> done++
                // fotku medzičasom našiel relinker pod novou cestou → nie je chýbajúca, prepočíta sa
                e.state == EmbedVersionRules.STATE_MISSING && !File(f.mediaFullPath).exists() ->
                    if (volumeMounted(f.mediaFullPath, rootOk)) missing++ else transient++

                e.state == EmbedVersionRules.STATE_FAILED && e.tries >= EmbedVersionRules.MAX_TRIES -> exhausted++
            }
        }
        return EmbedVersionRules.Tally(faces.size, done, missing, exhausted, transient)
    }

    private fun volumeMounted(path: String, cache: HashMap<String, Boolean>): Boolean {
        val root = EmbedVersionRules.volumeRoot(path) ?: return true
        return cache.getOrPut(root) { File(root).let { it.isDirectory && it.list() != null } }
    }

    // Prepnutie (po FaceEmbedVersion.markSwitchStarted). Idempotentné — po páde sa zopakuje celé.
    private fun finalizeSwitch(context: Context) {
        val at = FaceEmbedVersion.switchedAt(context) ?: return
        val staging = FaceV2Staging.get(context)
        val vectors = staging.doneVectors()
        val facesDb = FacesDatabase.getInstance(context)
        val dao = facesDb.FaceDao()
        facesDb.runInTransaction {
            for ((id, v) in vectors) dao.updateEmbedding(id, v, FaceEmbedVersion.PIPELINE_VERSION)
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
                sql.execSQL("UPDATE faces SET embedding = NULL, embed_version = NULL WHERE id = ?", arrayOf<Any>(id))
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
            picasaParked = peopleDb.PeopleDao().getAllAnchors()
                .any { it.personId < 0 && it.source == PicasaImportService.SOURCE }
        }
        FaceEmbedVersion.markSwitchFinished(context, picasaParked)
    }
}
