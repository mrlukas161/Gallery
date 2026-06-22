package org.fossify.gallery.faces

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.File

// B1: manuálne spustené indexovanie tvárí na pozadí (bežné vlákno). Prejde fotky z MediaStore,
// zmenší ich, spustí detekciu a uloží tváre do faces.db. Resumovateľné (preskočí už spracované).
// Všetko je obalené v zachytení chýb, aby pád nepoložil apku. Robustnosť (foreground service /
// WorkManager) príde v B4.
object FaceIndexer {
    @Volatile
    var isRunning = false
        private set

    private const val MAX_DECODE_SIZE = 1024
    private const val CHANNEL_ID = "face_indexing"
    private const val NOTIF_ID = 49231

    fun index(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (faces: Int, photos: Int) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            var detector: FaceDetectionHelper? = null
            try {
                val dao = FacesDatabase.getInstance(appCtx).FaceDao()
                detector = FaceDetectionHelper(appCtx)
                ensureChannel(appCtx)

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
                                dao.insertFaces(detected.mapIndexed { i, f ->
                                    FaceEntity(
                                        id = null, mediaFullPath = path, mediaStoreId = mediaStoreId, faceIndex = i,
                                        bboxLeft = f.left, bboxTop = f.top, bboxRight = f.right, bboxBottom = f.bottom,
                                        score = f.score, detectedAt = System.currentTimeMillis(),
                                    )
                                })
                            }
                            bmp.recycle()
                        }
                        dao.insertIndexedPhoto(IndexedPhotoEntity(path, faceCount, System.currentTimeMillis()))
                    } catch (ignored: Throwable) {
                        // jedna pokazená fotka nesmie zastaviť celé indexovanie
                    }
                    done++
                    if (done % 5 == 0 || done == total) {
                        onProgress(done, total)
                        notifyProgress(appCtx, done, total)
                    }
                }
                cancelNotification(appCtx)
                onDone(safeFaceCount(dao), safePhotoCount(dao))
            } catch (e: Throwable) {
                cancelNotification(appCtx)
                onError(e.message ?: e.javaClass.simpleName)
            } finally {
                detector?.close()
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun safeFaceCount(dao: FaceDao) = try { dao.getFaceCount() } catch (e: Throwable) { 0 }
    private fun safePhotoCount(dao: FaceDao) = try { dao.getPhotosWithFacesCount() } catch (e: Throwable) { 0 }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Rozpoznávanie tvárí", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun notifyProgress(context: Context, done: Int, total: Int) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Hľadám tváre")
                .setContentText("$done / $total")
                .setProgress(total, done, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (ignored: Throwable) {
        }
    }

    private fun cancelNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID)
        } catch (ignored: Throwable) {
        }
    }

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
        } catch (ignored: Throwable) {
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
