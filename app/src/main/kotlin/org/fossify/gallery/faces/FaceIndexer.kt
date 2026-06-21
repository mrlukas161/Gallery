package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.File

// B1: manuálne spustené indexovanie tvárí na pozadí (bežné vlákno). Prejde fotky z MediaStore,
// zmenší ich, spustí detekciu a uloží tváre do faces.db. Resumovateľné (preskočí už spracované).
// Robustnosť (WorkManager + foreground service) príde v B4.
object FaceIndexer {
    @Volatile
    var isRunning = false
        private set

    private const val MAX_DECODE_SIZE = 1024

    fun index(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (faces: Int, photos: Int) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            val dao = FacesDatabase.getInstance(appCtx).FaceDao()
            val detector = try {
                FaceDetectionHelper(appCtx)
            } catch (e: Exception) {
                isRunning = false
                onDone(safeFaceCount(dao), safePhotoCount(dao))
                return@ensureBackgroundThread
            }

            try {
                val processed = dao.getProcessedPaths().toHashSet()
                val todo = queryImages(appCtx).filter { it.second !in processed }
                val total = todo.size
                var done = 0
                for ((mediaStoreId, path) in todo) {
                    if (!isRunning) break
                    var faceCount = 0
                    try {
                        val bmp = decodeDownscaled(path)
                        if (bmp != null) {
                            val detected = detector.detect(bmp)
                            faceCount = detected.size
                            if (detected.isNotEmpty()) {
                                val faces = detected.mapIndexed { i, f ->
                                    FaceEntity(
                                        id = null,
                                        mediaFullPath = path,
                                        mediaStoreId = mediaStoreId,
                                        faceIndex = i,
                                        bboxLeft = f.left,
                                        bboxTop = f.top,
                                        bboxRight = f.right,
                                        bboxBottom = f.bottom,
                                        score = f.score,
                                        detectedAt = System.currentTimeMillis(),
                                    )
                                }
                                dao.insertFaces(faces)
                            }
                            bmp.recycle()
                        }
                        dao.insertIndexedPhoto(IndexedPhotoEntity(path, faceCount, System.currentTimeMillis()))
                    } catch (ignored: Exception) {
                    }
                    done++
                    if (done % 20 == 0 || done == total) {
                        onProgress(done, total)
                    }
                }
            } finally {
                detector.close()
                isRunning = false
            }
            onDone(safeFaceCount(dao), safePhotoCount(dao))
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun safeFaceCount(dao: FaceDao) = try { dao.getFaceCount() } catch (e: Exception) { 0 }
    private fun safePhotoCount(dao: FaceDao) = try { dao.getPhotosWithFacesCount() } catch (e: Exception) { 0 }

    private fun queryImages(context: Context): List<Pair<Long, String>> {
        val list = ArrayList<Pair<Long, String>>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val data = cursor.getString(dataCol) ?: continue
                    list.add(id to data)
                }
            }
        } catch (ignored: Exception) {
        }
        return list
    }

    private fun decodeDownscaled(path: String): Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > MAX_DECODE_SIZE || h / sample > MAX_DECODE_SIZE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }
}
