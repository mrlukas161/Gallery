package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import org.fossify.commons.helpers.ensureBackgroundThread

// „Prepracovať tváre" — jednorazová oprava + doplnenie:
//  1) fotka sa dekóduje SPRÁVNE OTOČENÁ (EXIF) -> detektor nájde aj tváre na fotkách na výšku,
//     ktoré predtým prehliadol, a výrezy už nie sú otočené o 90°,
//  2) tváre sa detegujú nanovo (aj menšie/vzdialené),
//  3) STARÉ záznamy sa spárujú s novými cez prekryv rámčekov (IoU) a AKTUALIZUJÚ SA NA MIESTE
//     (rovnaké id) -> priradenia k osobám, mená a „toto nie je on" ostávajú zachované,
//  4) nespárované nové tváre sa PRIDAJÚ (to sú tie predtým nenájdené),
//  5) odtlačok sa prepočíta z upright výrezu (staré boli z otočených = nepoužiteľné).
// Staré záznamy bez páru sa NEMAŽÚ (radšej nechať niečo navyše, než prísť o menovku).
object RefreshFaces {
    @Volatile
    var isRunning = false
        private set

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
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            var detector: FaceDetectionHelper? = null
            var embedder: FaceEmbedder? = null
            var landmarker: FaceLandmarkHelper? = null
            var updated = 0
            var added = 0
            try {
                val dao = FacesDatabase.getInstance(appCtx).FaceDao()
                detector = FaceDetectionHelper(appCtx)
                embedder = FaceEmbedder(appCtx)
                landmarker = FaceLandmarkHelper(appCtx)

                // spracúvame LEN fotky, ktoré už boli indexované (nové rieši bežné indexovanie)
                val byPath = dao.getAllFaces().groupBy { it.mediaFullPath }
                val paths = dao.getProcessedPaths()
                val total = paths.size
                var done = 0
                for (path in paths) {
                    if (!isRunning) break
                    try {
                        val res = UprightDecoder.decode(path, MAX_DECODE)
                        if (res != null) {
                            val bmp = res.bitmap
                            val detected = detector.detect(bmp).filter { FaceFilter.isUsable(it) }
                            val old = byPath[path].orEmpty()
                            val pair = matchAndStore(dao, path, old, detected, bmp, res, landmarker, embedder, detector)
                            updated += pair.first
                            added += pair.second
                            bmp.recycle()
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
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun matchAndStore(
        dao: FaceDao,
        path: String,
        old: List<FaceEntity>,
        detected: List<FaceDetectionHelper.DetectedFace>,
        bmp: Bitmap,
        res: UprightDecoder.Result,
        landmarker: FaceLandmarkHelper?,
        embedder: FaceEmbedder?,
        detector: FaceDetectionHelper?,
    ): Pair<Int, Int> {
        if (detected.isEmpty()) return 0 to 0
        // staré bboxy prepočítaj do upright priestoru (len ako kľúč na párovanie).
        // POZOR: staré bboxy sú v priestore NEotočenej bitmapy; jej rozmery vieme spätne odvodiť
        // z upright rozmerov a uhla otočenia.
        val rawW = if (res.rotationDegrees == 90 || res.rotationDegrees == 270) bmp.height else bmp.width
        val rawH = if (res.rotationDegrees == 90 || res.rotationDegrees == 270) bmp.width else bmp.height
        val oldBoxes = old.map {
            UprightDecoder.mapBox(it.bboxLeft, it.bboxTop, it.bboxRight, it.bboxBottom, rawW, rawH, res.rotationDegrees)
        }
        val usedOld = BooleanArray(old.size)
        var updated = 0
        var added = 0
        val toInsert = ArrayList<FaceEntity>()
        var nextIndex = (old.maxOfOrNull { it.faceIndex } ?: -1) + 1

        for (d in detected) {
            val nb = intArrayOf(d.left, d.top, d.right, d.bottom)
            var bestI = -1
            var bestIou = 0f
            for (i in old.indices) {
                if (usedOld[i]) continue
                val v = UprightDecoder.iou(nb, oldBoxes[i])
                if (v > bestIou) {
                    bestIou = v
                    bestI = i
                }
            }
            val emb = try {
                val crop = FaceAligner.cropRegion(bmp, d.left, d.top, d.right, d.bottom, 0.3f)
                if (crop != null && landmarker != null && embedder != null) {
                    val e = FaceEmbedder.toBytes(FaceAligner.embedCrop(crop, landmarker, embedder, detector).vec)
                    crop.recycle()
                    e
                } else {
                    null
                }
            } catch (e: Throwable) {
                null
            }
            if (bestI >= 0 && bestIou >= IOU_MATCH) {
                // rovnaká tvár ako predtým -> UPDATE na mieste (id, a teda aj menovka, ostáva)
                usedOld[bestI] = true
                val id = old[bestI].id
                if (id != null) {
                    try {
                        dao.updateFaceBox(id, d.left, d.top, d.right, d.bottom, d.score)
                        if (emb != null) dao.updateEmbedding(id, emb)
                        updated++
                    } catch (ignored: Throwable) {
                    }
                }
            } else {
                toInsert.add(
                    FaceEntity(
                        id = null, mediaFullPath = path,
                        mediaStoreId = old.firstOrNull()?.mediaStoreId ?: 0L,
                        faceIndex = nextIndex++,
                        bboxLeft = d.left, bboxTop = d.top, bboxRight = d.right, bboxBottom = d.bottom,
                        score = d.score, embedding = emb, detectedAt = System.currentTimeMillis(),
                    )
                )
                added++
            }
        }
        if (toInsert.isNotEmpty()) {
            try {
                dao.insertFaces(toInsert)
            } catch (ignored: Throwable) {
                added -= toInsert.size
            }
        }
        return updated to added
    }
}
