package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap

// Spoločná logika QR skenu nad bitmapou + zápis do qr.db. Používa QrIndexer (samostatný beh)
// aj OcrIndexer (oportunisticky pri OCR pasáži, na tej istej už dekódovanej bitmape).
object QrScanner {
    // Rýchly jeden prechod (decodeFirst). Vráti dekódovaný text alebo "" ak kód nie je.
    fun scanText(decoder: ZxingDecoder, bmp: Bitmap): String {
        return try {
            decoder.decodeFirst(bmp)?.text ?: ""
        } catch (e: Throwable) {
            ""
        }
    }

    fun store(context: Context, path: String, text: String) {
        try {
            QrDatabase.getInstance(context).QrDao()
                .insert(QrEntity(path, text.take(MAX_TEXT), System.currentTimeMillis()))
        } catch (ignored: Throwable) {
        }
    }

    private const val MAX_TEXT = 4000
}
