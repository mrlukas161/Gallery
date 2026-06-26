package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.File

// Prepíše embeddingy VŠETKÝCH existujúcich tvárí presnejším 5-bodovým zarovnaním — NA MIESTE.
// FaceEntity.id sa nemení -> priradenia/skupiny/ignorované (cez faceId) ostávajú nedotknuté.
// Staré Picasa anchory (iný priestor) sa zmažú -> treba re-import Picasy.
object ReindexFaces {
    @Volatile
    var isRunning = false
        private set

    private const val MAX_DECODE = 1024

    fun run(context: Context, onProgress: (Int, Int) -> Unit, onDone: (Int) -> Unit, onError: (String) -> Unit) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            var landmarker: FaceLandmarkHelper? = null
            var embedder: FaceEmbedder? = null
            try {
                val dao = FacesDatabase.getInstance(appCtx).FaceDao()
                landmarker = FaceLandmarkHelper(appCtx)
                embedder = FaceEmbedder(appCtx)
                val faces = dao.getAllFaces()
                val total = faces.size
                var done = 0
                for ((path, fs) in faces.groupBy { it.mediaFullPath }) {
                    if (!isRunning) break
                    val bmp = decode(path)
                    if (bmp != null) {
                        for (f in fs) {
                            val id = f.id
                            if (id != null) {
                                try {
                                    val crop = FaceAligner.cropRegion(bmp, f.bboxLeft, f.bboxTop, f.bboxRight, f.bboxBottom, 0.3f)
                                    if (crop != null) {
                                        val emb = FaceAligner.embedCrop(crop, landmarker!!, embedder!!)
                                        dao.updateEmbedding(id, FaceEmbedder.toBytes(emb))
                                        crop.recycle()
                                    }
                                } catch (ignored: Throwable) {
                                }
                            }
                            done++
                            if (done % 10 == 0 || done == total) onProgress(done, total)
                        }
                        bmp.recycle()
                    } else {
                        done += fs.size
                        onProgress(done.coerceAtMost(total), total)
                    }
                }
                try {
                    PeopleDatabase.getInstance(appCtx).PeopleDao().deleteAllAnchors()
                } catch (ignored: Throwable) {
                }
                onDone(done)
            } catch (e: Throwable) {
                onError(e.javaClass.simpleName + (e.message?.let { ": " + it.take(160) } ?: ""))
            } finally {
                landmarker?.close()
                embedder?.close()
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun decode(path: String): Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > MAX_DECODE || h / sample > MAX_DECODE) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }
}
