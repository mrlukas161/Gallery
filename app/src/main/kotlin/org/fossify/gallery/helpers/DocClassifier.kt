package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.faces.OcrDatabase

// Klasifikácia „toto je dokument" LEN z už existujúceho OCR textu (ocr.db) — žiadne ďalšie
// dekódovanie obrázkov, takže album Dokumenty sa poskladá okamžite.
// Kaskáda (podľa rešerše): screenshot heuristika -> hustota textu -> typ dokladu (bloček/faktúra/ID).
object DocClassifier {

    enum class Kind { DOCUMENT, RECEIPT, SCREENSHOT, ID_CARD, NONE }

    data class Doc(val path: String, val kind: Kind, val chars: Int, val norm: String)

    private const val MIN_CHARS = 100      // overený prah (precision ~93 %)
    private const val MIN_LINES = 8

    // kľúčové slová dokladov (bez diakritiky, malé písmená — norm_text je tak uložený)
    private val RECEIPT_WORDS = listOf("spolu", "dph", "ico", "dic", "eur", "celkom", "uhrada", "pokladnicny", "doklad", "faktura", "vrateny")
    private val ID_WORDS = listOf("obciansky preukaz", "vodicsky preukaz", "rodne cislo", "identity card", "cestovny pas", "preukaz totoznosti")
    private val LINE_PRICE = Regex("""\d+[.,]\d{2}\s*(€|eur)?\s*$""", RegexOption.IGNORE_CASE)

    fun isScreenshotPath(path: String): Boolean {
        val p = path.lowercase()
        return p.contains("/screenshot") || p.contains("/screencap") || p.contains("screenshots/")
    }

    fun classify(path: String, text: String, normText: String): Kind {
        val t = text.trim()
        if (t.length < MIN_CHARS) {
            return if (isScreenshotPath(path)) Kind.SCREENSHOT else Kind.NONE
        }
        val lines = t.lines().filter { it.isNotBlank() }
        if (lines.size < MIN_LINES) {
            return if (isScreenshotPath(path)) Kind.SCREENSHOT else Kind.NONE
        }
        val n = normText.ifEmpty { TextNormalizer.normalize(t, true) }
        if (ID_WORDS.any { n.contains(it) }) return Kind.ID_CARD
        val receiptWords = RECEIPT_WORDS.count { n.contains(it) }
        val priceLines = lines.count { LINE_PRICE.containsMatchIn(it) }
        if (receiptWords >= 2 && priceLines >= 1) return Kind.RECEIPT
        if (isScreenshotPath(path)) return Kind.SCREENSHOT
        return Kind.DOCUMENT
    }

    // všetky dokumenty z ocr.db (rýchle — jeden dopyt, klasifikácia v pamäti)
    fun loadAll(context: Context): List<Doc> {
        val rows = try {
            OcrDatabase.getInstance(context).OcrDao().getAllForDocs()
        } catch (e: Throwable) {
            emptyList()
        }
        val out = ArrayList<Doc>(rows.size / 4 + 8)
        for (r in rows) {
            val kind = classify(r.path, r.text, r.normText)
            if (kind != Kind.NONE) out.add(Doc(r.path, kind, r.text.length, r.normText))
        }
        return out
    }

    fun label(context: Context, kind: Kind): String = context.getString(
        when (kind) {
            Kind.RECEIPT -> org.fossify.gallery.R.string.doc_kind_receipt
            Kind.SCREENSHOT -> org.fossify.gallery.R.string.doc_kind_screenshot
            Kind.ID_CARD -> org.fossify.gallery.R.string.doc_kind_id
            else -> org.fossify.gallery.R.string.doc_kind_document
        }
    )
}
