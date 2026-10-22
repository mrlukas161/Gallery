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
import org.fossify.gallery.R
import org.fossify.gallery.family.FamilyInbox
import org.fossify.gallery.helpers.BackgroundPriority
import org.fossify.gallery.helpers.HeavyGate
import org.fossify.gallery.helpers.HeavySlot
import org.fossify.gallery.helpers.IdleCloser
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
    private const val LIVE_IDLE_MS = 60_000L

    // Tesseract sa vytvorí až pri prvej fotke, ktorú treba naozaj rozpoznať (s miestom v HeavyGate);
    // keď nie je čo spracovať, nenačíta sa. onDone/onError až po jeho zatvorení.
    fun index(
        context: Context,
        notify: Boolean = true,
        onWaiting: () -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (indexed: Int, withText: Int) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            var error: String? = null
            var total = 0
            var done = 0
            BackgroundPriority.run {
                val slot = HeavySlot({ isRunning }, onWaiting, onResume = { onProgress(done, total) })
                var engine: OcrEngine? = null
                try {
                    val dao = OcrDatabase.getInstance(appCtx).OcrDao()
                    if (notify) ensureChannel(appCtx)
                    val qrDecoder = lazy { ZxingDecoder() }

                    // vylúčené a zamknuté priečinky sa nespracúvajú; ich staré riadky sa z indexu zmažú
                    val scope = IndexScope(appCtx)
                    val processed = scope.keepAllowed(dao.getIndexedPaths()) { dao.deletePaths(it) }
                    val candidates = queryImages(appCtx).filter { it !in processed && scope.allows(it) }
                    // výsledky z prepojeného telefónu (F4) sa zapíšu hneď, fotka sa druhýkrát nespracúva
                    val todo = FamilyInbox.withoutSupplied(appCtx, candidates, FamilyInbox.OCR)
                    total = todo.size
                    for (path in todo) {
                        if (!isRunning) break
                        val eng = engine ?: loadEngine(appCtx, slot).also { engine = it }
                        indexPhoto(appCtx, eng, qrDecoder, path)
                        done++
                        reportProgress(appCtx, notify, done, total, onProgress)
                    }
                    cancelNotification(appCtx)
                } catch (ignored: HeavyGate.Stopped) {
                    cancelNotification(appCtx)
                } catch (e: Throwable) {
                    cancelNotification(appCtx)
                    error = describe(e)
                } finally {
                    engine?.close()
                    slot.close()
                    isRunning = false
                }
            }
            report(appCtx, error, onDone, onError)
        }
    }

    private fun reportProgress(
        context: Context,
        notify: Boolean,
        done: Int,
        total: Int,
        onProgress: (Int, Int) -> Unit,
    ) {
        if (done % 3 != 0 && done != total) return
        onProgress(done, total)
        if (notify) notifyProgress(context, done, total)
    }

    // výsledok behu až po zatvorení Tesseractu (ďalšia úloha spustená z callbacku má pamäť voľnú)
    private fun report(context: Context, error: String?, onDone: (Int, Int) -> Unit, onError: (String) -> Unit) {
        if (error != null) {
            onError(error)
        } else {
            onDone(safeCount(context), safeWithText(context))
        }
    }

    fun stop() {
        isRunning = false
    }

    // Tesseract až pri prvej fotke, s miestom v HeavyGate (zastavenie počas čakania → HeavyGate.Stopped)
    private fun loadEngine(context: Context, slot: HeavySlot): OcrEngine {
        slot.require()
        val engine = OcrEngine(context)
        if (!engine.isReady()) {
            engine.close()
            throw IllegalStateException("Tesseract sa nepodarilo inicializovať (slk)")
        }
        return engine
    }

    // Jedna fotka: text + pri už dekódovanej bitmape aj QR/čiarové kódy (zápis do qr.db) — QR len vtedy,
    // ak ho fotka ešte nemá (QrIndexer ju už oskenoval): každá fotka sa skenuje raz, výsledok je rovnaký
    // a v špičke (Tesseract + 2000 px bitmapa) netreba ešte pamäť pre ZXing.
    private fun indexPhoto(context: Context, engine: OcrEngine, qrDecoder: Lazy<ZxingDecoder>, path: String) {
        val needQr = !QrScanner.alreadyScanned(context, path)
        var text = ""
        var qr = ""
        try {
            val bmp = decodeDownscaled(path)
            if (bmp != null) {
                text = engine.recognize(bmp)
                if (needQr) qr = QrScanner.scanText(qrDecoder.value, bmp)
                bmp.recycle()
            }
        } catch (ignored: Throwable) {
        }
        saveResult(context, path, text)
        if (needQr) QrScanner.store(context, path, qr)
    }

    // Live Text (podržanie prsta v prehliadači, výber textu): JEDEN Tesseract pre všetky podržania,
    // zavrie sa LIVE_IDLE_MS po poslednom použití (alebo pri nedostatku pamäte). Tesseract nie je
    // thread-safe, preto sa naraz používa len z jedného vlákna (synchronized na inštancii).
    @Volatile
    private var liveCloser: IdleCloser<OcrEngine>? = null

    private fun liveCloser(context: Context): IdleCloser<OcrEngine> {
        liveCloser?.let { return it }
        val app = context.applicationContext
        return synchronized(this) {
            liveCloser ?: IdleCloser(
                LIVE_IDLE_MS,
                open = {
                    val engine = OcrEngine(app)
                    if (engine.isReady()) {
                        engine
                    } else {
                        engine.close()
                        null
                    }
                },
                close = { it.close() },
            ).also { liveCloser = it }
        }
    }

    // Rozpoznanie zdieľaným Tesseractom pre Live Text. null = Tesseract sa nepodarilo inicializovať.
    // Volať len z pozadia.
    fun <R> withLiveEngine(context: Context, block: (OcrEngine) -> R): R? =
        liveCloser(context).use { engine -> synchronized(engine) { block(engine) } }

    // onTrimMemory: Tesseract pre Live Text preč (hneď, alebo po dobehnutí rozpoznávania)
    fun releaseLiveEngine() {
        liveCloser?.release()
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
        return try {
            val bmp = decodeDownscaled(path) ?: return ""
            val text = try {
                withLiveEngine(appCtx) { it.recognize(bmp) }
            } finally {
                bmp.recycle()
            }
            if (text == null) return ""
            saveResult(appCtx, path, text)
            text
        } catch (e: Throwable) {
            ""
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
            // fotka zo zamknutého/vylúčeného priečinka: text sa ukáže, ale do ocr.db (Dokumenty,
            // hľadanie) sa NEULOŽÍ — inak by bol čitateľný bez odomknutia
            if (!IndexScope(context).allows(path)) return false
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

    // aj otvorenie DB v try — callback sa volá mimo hlavného try behu
    private fun safeCount(context: Context) =
        try { OcrDatabase.getInstance(context).OcrDao().count() } catch (e: Throwable) { 0 }
    private fun safeWithText(context: Context) =
        try { OcrDatabase.getInstance(context).OcrDao().countWithText() } catch (e: Throwable) { 0 }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // vždy (aj keď kanál existuje) — systém tak premenuje kanál vytvorený staršou verziou
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.q1_channel_ocr), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notifyProgress(context: Context, done: Int, total: Int) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.q1_notif_ocr))
                .setContentText("$done / $total")
                .setProgress(total, done, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (ignored: SecurityException) {
            // bez povolenia na notifikácie (Android 13+) — priebeh len nezobrazíme
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
