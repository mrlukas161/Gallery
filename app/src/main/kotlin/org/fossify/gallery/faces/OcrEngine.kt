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

    fun recognize(bitmap: Bitmap): String {
        if (!ready) return ""
        return try {
            tess.setImage(bitmap)
            val text = tess.getUTF8Text() ?: ""
            tess.clear()
            text
        } catch (e: Throwable) {
            ""
        }
    }

    fun close() {
        try {
            tess.recycle()
        } catch (ignored: Throwable) {
        }
    }
}
