package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import java.util.concurrent.atomic.AtomicLong

// „Prepracovať tváre" — jednorazová oprava + doplnenie:
//  1) fotka sa dekóduje SPRÁVNE OTOČENÁ (EXIF) -> detektor nájde aj tváre na fotkách na výšku,
//     ktoré predtým prehliadol, a výrezy už nie sú otočené o 90°,
//  2) tváre sa detegujú nanovo (aj menšie/vzdialené),
//  3) STARÉ záznamy sa spárujú s novými cez prekryv rámčekov (IoU) a AKTUALIZUJÚ SA NA MIESTE
//     (rovnaké id) -> priradenia k osobám, mená a „toto nie je on" ostávajú zachované,
//  4) nespárované nové tváre sa PRIDAJÚ (to sú tie predtým nenájdené),
//  5) odtlačok sa prepočíta z upright výrezu (staré boli z otočených = nepoužiteľné); doplní sa
//     aj tváram, ktorým sa pri indexovaní odtlačok vypočítať nepodarilo (v appke boli neviditeľné).
// Staré záznamy bez páru sa NEMAŽÚ (radšej nechať niečo navyše, než prísť o menovku).
// Opakované spustenie (aj po prerušení) je bezpečné: už opravené rámčeky sa spárujú bez prepočtu.
object RefreshFaces {
    // A7: beh sa „privlastní“ cez compareAndSet(0, id) — dve súbežné volania nespustia dva behy naraz.
    // owner = id bežiaceho behu (0 = nebeží). stop() ho vynuluje; koniec behu ho vynuluje len vtedy,
    // keď je ešte jeho (compareAndSet), takže nový beh spustený hneď po stop() neprepíše.
    private val owner = AtomicLong(0)
    private val runIds = AtomicLong(0)

    val isRunning: Boolean
        get() = owner.get() != 0L

    @Volatile
    var lastResult: String = ""
        private set

    private const val MAX_DECODE = 1024
    private const val IOU_MATCH = 0.35f

    fun run(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (updated: Int, added: Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val appCtx = context.applicationContext
        // počas prepočtu na v2 by sa do faces.db dostali v2 vektory medzi v1 (FaceReembedV2)
        if (FaceEmbedVersion.isPending(appCtx)) {
            onError(appCtx.getString(R.string.tb_refresh_wait_v2))
            return
        }
        val myRun = runIds.incrementAndGet()
        if (!owner.compareAndSet(0, myRun)) return
        ensureBackgroundThread {
            var detector: FaceDetectionHelper? = null
            var embedder: FaceEmbedder? = null
            var landmarker: FaceLandmarkHelper? = null
            var updated = 0
            var added = 0
            try {
                val dao = FacesDatabase.getInstance(appCtx).FaceDao()
                // nové tváre len s id nad všetkými odkazmi z people.db/extras.db (poistka proti posunu id)
                if (!FacesDatabase.ensureIdsAboveReferences(appCtx)) {
                    throw IllegalStateException(appCtx.getString(R.string.c1_faces_refs_unreadable))
                }
                val det = FaceDetectionHelper(appCtx).also { detector = it }
                val emb = FaceEmbedder(appCtx).also { embedder = it }
                val lm = FaceLandmarkHelper(appCtx).also { landmarker = it }

                // spracúvame LEN fotky, ktoré už boli indexované (nové rieši bežné indexovanie);
                // VŠETKY ich tváre vrátane tých bez odtlačku — aby sa doplnili na mieste a aby nové
                // poradie (face_index) nekolidovalo so žiadnym existujúcim riadkom
                val byPath = dao.getEveryFace().groupBy { it.mediaFullPath }
                val paths = dao.getProcessedPaths()
                val total = paths.size
                var done = 0
                for (path in paths) {
                    if (owner.get() != myRun) break
                    try {
                        val res = UprightDecoder.decode(path, MAX_DECODE)
                        if (res != null) {
                            val bmp = res.bitmap
                            val full = FullResCrop.open(path, MAX_DECODE, bmp.width, bmp.height)
                            try {
                                val detected = det.detect(bmp).filter { FaceFilter.isUsable(it) }
                                val old = byPath[path].orEmpty()
                                val pair =
                                    matchAndStore(dao, path, old, detected, res, lm, emb, det, Target(full, null))
                                updated += pair.first
                                added += pair.second
                            } finally {
                                full?.close()
                                bmp.recycle()
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                    done++
                    if (done % 5 == 0 || done == total) onProgress(done, total)
                }
                lastResult = "aktualizované $updated, nové $added"
                onDone(updated, added)
            } catch (e: Throwable) {
                onError(e.javaClass.simpleName + (e.message?.let { ": " + it.take(140) } ?: ""))
            } finally {
                detector?.close()
                embedder?.close()
                landmarker?.close()
                owner.compareAndSet(myRun, 0)
            }
        }
    }

    fun stop() {
        owner.set(0)
    }

    // A6: prepracovanie LEN vybraných fotiek (po otočení/prepísaní, keď sa rámčeky nedali otočiť
    // bezpečne — FaceRotation). Rovnaké párovanie ako run() (IoU, obe hypotézy priestoru), takže
    // menovky ostanú na mieste. Vráti vybavené cesty (spracované alebo už neexistujúce). Volať z pozadia.
    internal fun refreshPaths(context: Context, paths: Collection<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        val myRun = runIds.incrementAndGet()
        if (!owner.compareAndSet(0, myRun)) return emptySet()
        val appCtx = context.applicationContext
        val done = HashSet<String>()
        var detector: FaceDetectionHelper? = null
        var embedder: FaceEmbedder? = null
        var landmarker: FaceLandmarkHelper? = null
        try {
            if (!FacesDatabase.ensureIdsAboveReferences(appCtx)) return emptySet()
            val dao = FacesDatabase.getInstance(appCtx).FaceDao()
            val det = FaceDetectionHelper(appCtx).also { detector = it }
            val emb = FaceEmbedder(appCtx).also { embedder = it }
            val lm = FaceLandmarkHelper(appCtx).also { landmarker = it }
            // počas prepočtu na v2 idú nové vektory bokom (FaceV2Staging), rámčeky sa opravia hneď
            val staging = if (FaceEmbedVersion.isPending(appCtx)) FaceV2Staging.get(appCtx) else null
            for (path in paths.asSequence().takeWhile { owner.get() == myRun }) {
                if (!java.io.File(path).exists() || refreshOne(dao, path, det, emb, lm, staging)) done.add(path)
            }
        } catch (ignored: Throwable) {
        } finally {
            detector?.close()
            embedder?.close()
            landmarker?.close()
            owner.compareAndSet(myRun, 0)
        }
        return done
    }

    private fun refreshOne(
        dao: FaceDao,
        path: String,
        det: FaceDetectionHelper,
        emb: FaceEmbedder,
        lm: FaceLandmarkHelper,
        staging: FaceV2Staging?,
    ): Boolean = try {
        val res = UprightDecoder.decode(path, MAX_DECODE)
        if (res != null) {
            val bmp = res.bitmap
            val full = FullResCrop.open(path, MAX_DECODE, bmp.width, bmp.height)
            try {
                val detected = det.detect(bmp).filter { FaceFilter.isUsable(it) }
                matchAndStore(
                    dao, path, dao.getFacesForPath(path), detected, res, lm, emb, det, Target(full, staging),
                )
            } finally {
                full?.close()
                bmp.recycle()
            }
        }
        res != null
    } catch (ignored: Throwable) {
        false
    }

    // Doplnenie odtlačku tváram, ktorým ho indexovanie nevypočítalo (výnimka v TFLite/MediaPipe) —
    // bez odtlačku sú v appke neviditeľné (getAllFaces ich filtruje) a nedajú sa označiť. Fotka sa
    // znova deteguje, staré záznamy sa spárujú ako pri prepracovaní a aktualizujú sa NA MIESTE LEN
    // tie bez odtlačku; nič sa nepridáva ani nemaže. Volá FaceIndexer s vlastnými modelmi.
    internal fun repairUnembedded(
        dao: FaceDao,
        detector: FaceDetectionHelper,
        embedder: FaceEmbedder,
        landmarker: FaceLandmarkHelper,
        maxPhotos: Int,
        running: () -> Boolean,
    ): Int {
        var repaired = 0
        for (path in dao.getPathsWithUnembeddedFaces(maxPhotos)) {
            if (!running()) break
            try {
                val res = UprightDecoder.decode(path, MAX_DECODE) ?: continue
                val bmp = res.bitmap
                var full: FullResCrop? = null
                try {
                    val old = dao.getFacesForPath(path)
                    val detected = detector.detect(bmp)
                    val match = matchOldFaces(old, detected, res, bmp.width, bmp.height)
                    full = FullResCrop.open(path, MAX_DECODE, bmp.width, bmp.height)
                    for ((di, d) in detected.withIndex()) {
                        val face = old.getOrNull(match[di]) ?: continue
                        val id = face.id ?: continue
                        if (face.embedding != null) continue
                        val e = FaceAligner.embedDetected(bmp, d, landmarker, embedder, detector, full) ?: continue
                        dao.updateFaceBox(id, d.left, d.top, d.right, d.bottom, d.score)
                        dao.updateEmbedding(id, e)
                        repaired++
                    }
                } finally {
                    full?.close()
                    bmp.recycle()
                }
            } catch (ignored: Throwable) {
            }
        }
        return repaired
    }

    // Kam sa zapíšu nové odtlačky: výrez z plného rozlíšenia (ak sa dá) a pri bežiacom prepočte na v2
    // staging (vo faces.db sú ešte v1 vektory, nové v2 preto čakajú vo faces_v2.db a do faces.db ich
    // zapíše až prepnutie vo FaceReembedV2; rámčeky sa menia hneď).
    private class Target(val full: FullResCrop?, val staging: FaceV2Staging?)

    private fun matchAndStore(
        dao: FaceDao,
        path: String,
        old: List<FaceEntity>,
        detected: List<FaceDetectionHelper.DetectedFace>,
        res: UprightDecoder.Result,
        landmarker: FaceLandmarkHelper,
        embedder: FaceEmbedder,
        detector: FaceDetectionHelper,
        target: Target,
    ): Pair<Int, Int> {
        if (detected.isEmpty()) return 0 to 0
        val bmp = res.bitmap
        val match = matchOldFaces(old, detected, res, bmp.width, bmp.height)
        var updated = 0
        val toInsert = ArrayList<FaceEntity>()
        val newVectors = HashMap<Int, ByteArray>()
        var nextIndex = (old.maxOfOrNull { it.faceIndex } ?: -1) + 1

        for ((di, d) in detected.withIndex()) {
            val emb = FaceAligner.embedDetected(bmp, d, landmarker, embedder, detector, target.full)
            val oi = match[di]
            if (oi >= 0) {
                // rovnaká tvár ako predtým -> UPDATE na mieste (id, a teda aj menovka, ostáva)
                val id = old[oi].id ?: continue
                if (updateMatched(dao, id, d, emb, target.staging)) updated++
            } else {
                val index = nextIndex++
                toInsert.add(
                    FaceEntity(
                        id = null, mediaFullPath = path,
                        mediaStoreId = old.firstOrNull()?.mediaStoreId ?: 0L,
                        faceIndex = index,
                        bboxLeft = d.left, bboxTop = d.top, bboxRight = d.right, bboxBottom = d.bottom,
                        score = d.score, embedding = if (target.staging != null) null else emb,
                        detectedAt = System.currentTimeMillis(),
                    )
                )
                if (emb != null) newVectors[index] = emb
            }
        }
        val added = if (insertNew(dao, path, toInsert, newVectors, target.staging)) toInsert.size else 0
        return updated to added
    }

    private fun updateMatched(
        dao: FaceDao,
        id: Long,
        d: FaceDetectionHelper.DetectedFace,
        emb: ByteArray?,
        staging: FaceV2Staging?,
    ): Boolean = try {
        dao.updateFaceBox(id, d.left, d.top, d.right, d.bottom, d.score)
        if (emb != null) {
            if (staging != null) staging.putDone(listOf(Triple(id, emb, null))) else dao.updateEmbedding(id, emb)
        }
        true
    } catch (ignored: Throwable) {
        false
    }

    // nové tváre; pri prepočte na v2 bez odtlačku a ich v2 vektor do stagingu (podľa poradia na fotke)
    private fun insertNew(
        dao: FaceDao,
        path: String,
        toInsert: List<FaceEntity>,
        vectors: Map<Int, ByteArray>,
        staging: FaceV2Staging?,
    ): Boolean {
        if (toInsert.isEmpty()) return true
        return try {
            dao.insertFaces(toInsert)
            if (staging != null && vectors.isNotEmpty()) {
                val items = dao.getFacesForPath(path).mapNotNull { f ->
                    val id = f.id ?: return@mapNotNull null
                    vectors[f.faceIndex]?.let { Triple(id, it, null as ByteArray?) }
                }
                staging.putDone(items)
            }
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    // Spáruje staré záznamy fotky s novými detekciami (IoU, pažravo). Staré rámčeky môžu byť
    // v DVOCH priestoroch a DB to nerozlišuje (zmena schémy by pri fallbacku zmazala faces.db):
    //  - upright = všetko indexované od 0.47 (FaceIndexer dekóduje cez UprightDecoder) a všetko,
    //    čo toto prepracovanie už raz aktualizovalo,
    //  - surový neotočený priestor = indexované pred 0.47 (BitmapFactory ignoroval EXIF).
    // Vyskúšajú sa obe hypotézy a vyhrá tá s viac pármi (pri rovnosti vyšší súčet IoU, potom upright).
    // Predtým sa staré rámčeky VŽDY prepočítavali ako surové — pri fotkách na výšku sa súčasné tváre
    // nespárovali, vznikali duplikáty a menovky ostávali na starých záznamoch.
    // Vráti pre každú detekciu index starého záznamu alebo -1.
    internal fun matchOldFaces(
        old: List<FaceEntity>,
        detected: List<FaceDetectionHelper.DetectedFace>,
        res: UprightDecoder.Result,
        uprightW: Int,
        uprightH: Int,
    ): IntArray {
        val asIs = greedyMatch(old.map { intArrayOf(it.bboxLeft, it.bboxTop, it.bboxRight, it.bboxBottom) }, detected)
        if (res.rotationDegrees == 0 && !res.mirrored) return asIs.pairs
        // rozmery neotočenej bitmapy v rovnakom zmenšení (otočenie o 90/270 vymení šírku a výšku)
        val swap = res.rotationDegrees == 90 || res.rotationDegrees == 270
        val rawW = if (swap) uprightH else uprightW
        val rawH = if (swap) uprightW else uprightH
        val mapped = greedyMatch(
            old.map {
                UprightDecoder.mapBox(
                    it.bboxLeft, it.bboxTop, it.bboxRight, it.bboxBottom, rawW, rawH, res.rotationDegrees, res.mirrored,
                )
            },
            detected,
        )
        val mappedWins = mapped.count > asIs.count || (mapped.count == asIs.count && mapped.iouSum > asIs.iouSum)
        return if (mappedWins) mapped.pairs else asIs.pairs
    }

    private class Matching(val pairs: IntArray, val count: Int, val iouSum: Float)

    private fun greedyMatch(oldBoxes: List<IntArray>, detected: List<FaceDetectionHelper.DetectedFace>): Matching {
        val pairs = IntArray(detected.size) { -1 }
        val used = BooleanArray(oldBoxes.size)
        var count = 0
        var iouSum = 0f
        for ((di, d) in detected.withIndex()) {
            val nb = intArrayOf(d.left, d.top, d.right, d.bottom)
            var bestI = -1
            var bestIou = 0f
            for (i in oldBoxes.indices) {
                if (used[i]) continue
                val v = UprightDecoder.iou(nb, oldBoxes[i])
                if (v > bestIou) {
                    bestIou = v
                    bestI = i
                }
            }
            if (bestI >= 0 && bestIou >= IOU_MATCH) {
                used[bestI] = true
                pairs[di] = bestI
                count++
                iouSum += bestIou
            }
        }
        return Matching(pairs, count, iouSum)
    }
}
