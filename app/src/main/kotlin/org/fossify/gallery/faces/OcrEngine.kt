package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

// Tesseract OCR (slovenčina – pokrýva aj anglické znaky/čísla). FOSS, offline.
class OcrEngine(context: Context) {
    private val tess = TessBaseAPI()
    private var ready = false

    init {
        try {
            val tessDir = File(context.filesDir, "tessdata")
            tessDir.mkdirs()
            val slk = File(tessDir, "slk.traineddata")
            if (!slk.exists() || slk.length() == 0L) {
                context.assets.open("tessdata/slk.traineddata").use { input ->
                    slk.outputStream().use { out -> input.copyTo(out) }
                }
            }
            // init dostáva RODIČA priečinka tessdata + jazyk
            ready = tess.init(context.filesDir.absolutePath, "slk")
        } catch (e: Throwable) {
            ready = false
        }
    }

    fun isReady() = ready

    // Vráti text LEN ak je Tesseract dostatočne istý A výsledok vyzerá ako skutočný text.
    // Bez týchto dvoch kontrol vyrobí OCR „text" úplne z každej fotky (aj z mačky či krajiny).
    fun recognize(bitmap: Bitmap): String {
        if (!ready) return ""
        return try {
            tess.setImage(bitmap)
            val raw = tess.getUTF8Text() ?: ""
            val confidence = try {
                tess.meanConfidence()
            } catch (e: Throwable) {
                0
            }
            tess.clear()
            if (confidence < OcrText.MIN_CONFIDENCE) return ""
            val cleaned = OcrText.clean(raw)
            if (!OcrText.isMeaningful(cleaned)) "" else cleaned
        } catch (e: Throwable) {
            ""
        }
    }


    data class WordBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val conf: Float)

    // Rozpozná text so SÚRADNICAMI slov (pre výber priamo na fotke). Boxy sú v pixeloch VSTUPNEJ
    // bitmapy — na presné mapovanie treba tú istú bitmapu aj zobraziť.
    fun recognizeWords(bitmap: Bitmap): List<WordBox> {
        if (!ready) return emptyList()
        return try {
            tess.setImage(bitmap)
            tess.getUTF8Text() // vynúti rozpoznanie, inak je iterator prázdny
            val out = ArrayList<WordBox>()
            val iter = tess.resultIterator
            if (iter != null) {
                iter.begin()
                do {
                    try {
                        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                        val txt = iter.getUTF8Text(level)
                        val conf = iter.confidence(level)
                        val r = iter.getBoundingRect(level)
                        if (!txt.isNullOrBlank() && conf >= 40f && r != null && r.width() > 1 && r.height() > 1) {
                            out.add(WordBox(txt.trim(), r.left, r.top, r.right, r.bottom, conf))
                        }
                    } catch (ignored: Throwable) {
                    }
                } while (iter.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                iter.delete()
            }
            tess.clear()
            out
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun close() {
        try {
            tess.recycle()
        } catch (ignored: Throwable) {
        }
    }
}
