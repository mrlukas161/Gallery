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

// Perceptuálny hash (average hash): každú fotku zmenší na PEVNÝCH 8x8, spočíta 64-bit hash (phash.db).
// Rýchle, resumovateľné. Slúži na hľadanie podobných/duplicitných fotiek naprieč celou knižnicou.
object PhashIndexer {
    @Volatile
    var isRunning = false
        private set

    private const val HASH = 8
    private const val CHANNEL_ID = "phash_indexing"
    private const val NOTIF_ID = 49234

    fun index(
        context: Context,
        notify: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (indexed: Int) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            try {
                val dao = PhashDatabase.getInstance(appCtx).PhashDao()
                if (notify) ensureChannel(appCtx)
                val processed = dao.getIndexedPaths().toHashSet()
                val todo = queryImages(appCtx).filter { it !in processed }
                val total = todo.size
                var done = 0
                for (path in todo) {
                    if (!isRunning) break
                    val hash = try {
                        computeHash(path)
                    } catch (e: Throwable) {
                        0L
                    }
                    try {
                        dao.insert(PhashEntity(path, hash, System.currentTimeMillis()))
                    } catch (ignored: Throwable) {
                    }
                    done++
                    if (done % 20 == 0 || done == total) {
                        onProgress(done, total)
                        if (notify) notifyProgress(appCtx, done, total)
                    }
                }
                cancelNotification(appCtx)
                onDone(safeCount(dao))
            } catch (e: Throwable) {
                cancelNotification(appCtx)
                onError(e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: ""))
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    // average hash na PEVNOM 8x8 (nie s pomerom strán) -> vždy 64 bitov, porovnateľné medzi fotkami
    private fun computeHash(path: String): Long {
        val bmp = decode8x8(path) ?: return 0L
        val n = bmp.width * bmp.height
        val px = IntArray(n)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        if (n < 64) return 0L
        val gray = IntArray(64) { i ->
            val p = px[i]
            ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        var sum = 0L
        for (g in gray) sum += g
        val avg = sum / 64
        var hash = 0L
        for (i in 0 until 64) {
            if (gray[i] >= avg) hash = hash or (1L shl i)
        }
        return hash
    }

    private fun decode8x8(path: String): Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) return null
        var sample = 1
        while (w0 / sample > 64 || h0 / sample > 64) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val small = Bitmap.createScaledBitmap(bmp, HASH, HASH, true)
        if (small != bmp) bmp.recycle()
        return small
    }

    private fun safeCount(dao: PhashDao) = try { dao.count() } catch (e: Throwable) { 0 }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Duplikáty", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun notifyProgress(context: Context, done: Int, total: Int) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Hľadám podobné fotky")
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
}
