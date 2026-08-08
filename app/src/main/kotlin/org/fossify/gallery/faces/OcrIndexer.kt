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
import org.fossify.gallery.helpers.TextNormalizer
import java.io.File

// OCR indexovanie: prejde fotky a uloží rozpoznaný text (ocr.db). Pomalé (Tesseract) -> beží na pozadí,
// resumovateľné (preskočí už spracované), s priebehovou notifikáciou. Spúšťa Lukáš (najlepšie pri nabíjaní).
object OcrIndexer {
    @Volatile
    var isRunning = false
        private set

    private const val MAX_DECODE = 2000
    private const val CHANNEL_ID = "ocr_indexing"
    private const val NOTIF_ID = 49232
    private const val MAX_TEXT = 8000

    fun index(
        context: Context,
        notify: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (indexed: Int, withText: Int) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            var engine: OcrEngine? = null
            try {
                val dao = OcrDatabase.getInstance(appCtx).OcrDao()
                engine = OcrEngine(appCtx)
                if (!engine.isReady()) throw IllegalStateException("Tesseract sa nepodarilo inicializovať (slk)")
                if (notify) ensureChannel(appCtx)
                // Pri už dekódovanej bitmape rovno skontroluj aj QR/čiarové kódy (zápis do qr.db).
                val qrDecoder = ZxingDecoder()

                val processed = dao.getIndexedPaths().toHashSet()
                val todo = queryImages(appCtx).filter { it !in processed }
                val total = todo.size
                var done = 0
                for (path in todo) {
                    if (!isRunning) break
                    var text = ""
                    var qr = ""
                    try {
                        val bmp = decodeDownscaled(path)
                        if (bmp != null) {
                            text = engine.recognize(bmp)
                            qr = QrScanner.scanText(qrDecoder, bmp)
                            bmp.recycle()
                        }
                    } catch (ignored: Throwable) {
                    }
                    saveResult(appCtx, path, text)
                    QrScanner.store(appCtx, path, qr)
                    done++
                    if (done % 3 == 0 || done == total) {
                        onProgress(done, total)
                        if (notify) notifyProgress(appCtx, done, total)
                    }
                }
                cancelNotification(appCtx)
                onDone(safeCount(dao), safeWithText(dao))
            } catch (e: Throwable) {
                cancelNotification(appCtx)
                onError(describe(e))
            } finally {
                engine?.close()
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    // OCR JEDNEJ fotky na požiadanie (Live Text — podržanie prsta v prehliadači). Ak text už v DB je,
    // vráti ho okamžite; inak rozpozná teraz a uloží, takže sa zaradí aj do albumu Dokumenty.
    fun textForPhoto(context: Context, path: String): String {
        val appCtx = context.applicationContext
        val dao = try {
            OcrDatabase.getInstance(appCtx).OcrDao()
        } catch (e: Throwable) {
            return ""
        }
        try {
            val cached = dao.getText(path)
            if (cached != null) return cached
        } catch (ignored: Throwable) {
        }
        var engine: OcrEngine? = null
        return try {
            engine = OcrEngine(appCtx)
            if (!engine.isReady()) return ""
            val bmp = decodeDownscaled(path) ?: return ""
            val text = engine.recognize(bmp)
            bmp.recycle()
            saveResult(appCtx, path, text)
            text
        } catch (e: Throwable) {
            ""
        } finally {
            try {
                engine?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    // [27] JEDNOTNÁ cesta zápisu výsledku OCR do ocr.db — volá ju indexer, textForPhoto aj
    // on-demand rozpoznanie v TextSelectActivity (dlhé podržanie). Rovnaké čistenie
    // (OcrText.clean + isMeaningful) a normalizácia ako pri indexovaní; nezmyselný alebo žiadny
    // text sa uloží ako PRÁZDNY záznam (konvencia indexera) — fotka sa už nebude OCR-ovať znova.
    // overwrite=false ponechá existujúci záznam nedotknutý (on-demand výsledok poskladaný
    // z boxov slov nesmie prepísať plný text z indexera).
    fun saveResult(context: Context, path: String, text: String, overwrite: Boolean = true): Boolean {
        return try {
            val dao = OcrDatabase.getInstance(context.applicationContext).OcrDao()
            if (!overwrite && dao.getText(path) != null) return true
            val cleaned = OcrText.clean(text)
            val stored = if (OcrText.isMeaningful(cleaned)) cleaned else ""
            dao.insert(
                OcrEntity(
                    path,
                    stored.take(MAX_TEXT),
                    TextNormalizer.normalize(stored, true).take(MAX_TEXT),
                    System.currentTimeMillis(),
                )
            )
            true
        } catch (e: Throwable) {
            false
        }
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

    private fun safeCount(dao: OcrDao) = try { dao.count() } catch (e: Throwable) { 0 }
    private fun safeWithText(dao: OcrDao) = try { dao.countWithText() } catch (e: Throwable) { 0 }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "OCR textu", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun notifyProgress(context: Context, done: Int, total: Int) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Čítam text z fotiek")
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
