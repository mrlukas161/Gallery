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

// QR/čiarové kódy: prejde fotky a uloží dekódovaný obsah (qr.db). RÝCHLE (ZXing jeden prechod ~0,1 s/fotka),
// resumovateľné (preskočí už spracované). Beží v automatickom indexovaní na pozadí aj na tlačidlo.
object QrIndexer {
    @Volatile
    var isRunning = false
        private set

    private const val MAX_DECODE = 1600
    private const val CHANNEL_ID = "qr_indexing"
    private const val NOTIF_ID = 49233

    fun index(
        context: Context,
        notify: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (indexed: Int, withContent: Int) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            try {
                val dao = QrDatabase.getInstance(appCtx).QrDao()
                val decoder = ZxingDecoder()
                if (notify) ensureChannel(appCtx)

                val processed = dao.getIndexedPaths().toHashSet()
                val todo = queryImages(appCtx).filter { it !in processed }
                val total = todo.size
                var done = 0
                for (path in todo) {
                    if (!isRunning) break
                    var text = ""
                    try {
                        val bmp = decodeDownscaled(path)
                        if (bmp != null) {
                            text = QrScanner.scanText(decoder, bmp)
                            bmp.recycle()
                        }
                    } catch (ignored: Throwable) {
                    }
                    QrScanner.store(appCtx, path, text)
                    done++
                    if (done % 10 == 0 || done == total) {
                        onProgress(done, total)
                        if (notify) notifyProgress(appCtx, done, total)
                    }
                }
                cancelNotification(appCtx)
                onDone(safeCount(dao), safeWithContent(dao))
            } catch (e: Throwable) {
                cancelNotification(appCtx)
                onError(describe(e))
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun describe(t: Throwable): String {
        val chain = ArrayList<Throwable>()
        var cur: Throwable? = t
        while (cur != null && chain.size < 5) {
            chain.add(cur)
            cur = cur.cause
        }
        return chain.asReversed().joinToString(" <- ") { e ->
            e.javaClass.simpleName + (e.message?.let { ": " + it.take(160) } ?: "")
        }
    }

    private fun safeCount(dao: QrDao) = try { dao.count() } catch (e: Throwable) { 0 }
    private fun safeWithContent(dao: QrDao) = try { dao.countWithContent() } catch (e: Throwable) { 0 }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "QR kódy", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun notifyProgress(context: Context, done: Int, total: Int) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Hľadám QR kódy")
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

    private fun decodeDownscaled(path: String): Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > MAX_DECODE || h / sample > MAX_DECODE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }
}
