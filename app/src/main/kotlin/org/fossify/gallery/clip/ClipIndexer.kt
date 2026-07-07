package org.fossify.gallery.clip

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

// CLIP indexovanie: stiahne modely (ak treba) a pre každú fotku spočíta 512-dim vektor (clip.db).
// Resumovateľné. Vizuál enkodér beží ~50-150 ms/fotka na výkonnom telefóne.
object ClipIndexer {
    @Volatile
    var isRunning = false
        private set

    private const val MAX_DECODE = 512
    private const val CHANNEL_ID = "clip_indexing"
    private const val NOTIF_ID = 49235

    fun index(
        context: Context,
        notify: Boolean = true,
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
        onDone: (indexed: Int) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            var encoder: ClipEncoder? = null
            try {
                if (notify) ensureChannel(appCtx)
                // 1) modely (stiahnutie pri prvom behu)
                val ok = ClipModels.ensure(appCtx, { isRunning }) { phase, pct ->
                    onProgress("Sťahujem $phase", pct, 100)
                    if (notify) notifyProgress(appCtx, "Sťahujem CLIP $phase", pct, 100)
                }
                if (!isRunning) throw InterruptedException("zastavené")
                if (!ok) throw IllegalStateException("CLIP model sa nestiahol (skontroluj WiFi/pripojenie)")

                // 2) indexovanie fotiek
                val dao = ClipDatabase.getInstance(appCtx).ClipDao()
                encoder = ClipEncoder(appCtx)
                if (!encoder.visualReady()) throw IllegalStateException("Vizuálny model sa nenačítal")

                val processed = dao.getIndexedPaths().toHashSet()
                val todo = queryImages(appCtx).filter { it !in processed }
                val total = todo.size
                var done = 0
                for (path in todo) {
                    if (!isRunning) break
                    try {
                        val bmp = decode(path)
                        if (bmp != null) {
                            val emb = encoder.encodeImage(bmp)
                            bmp.recycle()
                            if (emb != null) {
                                dao.insert(ClipEntity(path, ClipEncoder.toBytes(emb), System.currentTimeMillis()))
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                    done++
                    if (done % 5 == 0 || done == total) {
                        onProgress("CLIP", done, total)
                        if (notify) notifyProgress(appCtx, "CLIP indexujem", done, total)
                    }
                }
                cancelNotification(appCtx)
                onDone(safeCount(dao))
            } catch (e: Throwable) {
                cancelNotification(appCtx)
                onError(e.javaClass.simpleName + (e.message?.let { ": " + it.take(140) } ?: ""))
            } finally {
                encoder?.close()
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun safeCount(dao: ClipDao) = try { dao.count() } catch (e: Throwable) { 0 }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "CLIP hľadanie", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun notifyProgress(context: Context, title: String, done: Int, total: Int) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
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

    private fun queryImages(context: Context): List<String> {
        val list = ArrayList<String>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null,
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (cursor.moveToNext()) {
                    cursor.getString(dataCol)?.let { list.add(it) }
                }
            }
        } catch (ignored: Throwable) {
        }
        return list
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
