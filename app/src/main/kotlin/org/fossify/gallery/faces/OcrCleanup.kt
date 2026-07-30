package org.fossify.gallery.faces

import android.content.Context
import org.fossify.commons.helpers.ensureBackgroundThread

// Jednorazové vyčistenie starých OCR záznamov: staršie verzie ukladali „text" aj z fotiek, na
// ktorých žiadny text nie je (Tesseract vyrobí zhluky znakov zo šumu). Toto prejde uloženú
// databázu a nezmyselné záznamy vyprázdni — BEZ opätovného rozpoznávania, takže to trvá sekundy.
// Beží automaticky raz po aktualizácii appky.
object OcrCleanup {
    private const val PREFS = "galeria_faces"
    private const val KEY_DONE = "ocr_cleanup_v2"

    @Volatile
    var isRunning = false
        private set

    fun runIfNeeded(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false) || isRunning) return
        isRunning = true
        ensureBackgroundThread {
            try {
                val dao = OcrDatabase.getInstance(appCtx).OcrDao()
                val rows = dao.getAllForDocs()
                val bad = ArrayList<String>()
                for (r in rows) {
                    val cleaned = OcrText.clean(r.text)
                    if (!OcrText.isMeaningful(cleaned)) bad.add(r.path)
                }
                // po dávkach, aby SQLite nedostal priveľký IN (...)
                bad.chunked(400).forEach { chunk ->
                    try {
                        dao.clearTexts(chunk)
                    } catch (ignored: Throwable) {
                    }
                }
                prefs.edit().putBoolean(KEY_DONE, true).apply()
            } catch (ignored: Throwable) {
            } finally {
                isRunning = false
            }
        }
    }

    // ručné opakovanie (Nastavenia) — zopakuje čistenie aj keď už raz prebehlo
    fun forceRun(context: Context, onDone: (cleared: Int) -> Unit) {
        val appCtx = context.applicationContext
        if (isRunning) return
        isRunning = true
        ensureBackgroundThread {
            var cleared = 0
            try {
                val dao = OcrDatabase.getInstance(appCtx).OcrDao()
                val rows = dao.getAllForDocs()
                val bad = ArrayList<String>()
                for (r in rows) {
                    val cleaned = OcrText.clean(r.text)
                    if (!OcrText.isMeaningful(cleaned)) bad.add(r.path)
                }
                cleared = bad.size
                bad.chunked(400).forEach { chunk ->
                    try {
                        dao.clearTexts(chunk)
                    } catch (ignored: Throwable) {
                    }
                }
                appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
            } catch (ignored: Throwable) {
            } finally {
                isRunning = false
                onDone(cleared)
            }
        }
    }
}
