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

    // Má fotka už výsledok v qr.db (QrIndexer, OCR alebo prepojený telefón)? Každá fotka sa skenuje raz:
    // QrIndexer preskočí fotky, ktoré oskenovalo OCR (getIndexedPaths), a OCR preskočí tie od QrIndexera.
    // Pri chybe DB radšej „nie“ (oskenuje sa znova, ako predtým).
    fun alreadyScanned(context: Context, path: String): Boolean = try {
        QrDatabase.getInstance(context).QrDao().getByPath(path) != null
    } catch (ignored: Throwable) {
        false
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
