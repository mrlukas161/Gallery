package org.fossify.gallery.faces

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer

// Offline FOSS čítanie QR/čiarových kódov z android.graphics.Bitmap cez ZXing core (3.5.4).
// Čisto Java, žiadne Google služby / ML Kit. NIE je thread-safe: jednu inštanciu drž na jedno
// pracovné vlákno (cachovaný MultiFormatReader nie je thread-safe). Nikdy nevyhodí výnimku volajúcemu.
class ZxingDecoder {

    data class DecodedCode(val text: String, val format: BarcodeFormat)

    // Znovupoužívaný; reset() medzi pokusmi. Nie je thread-safe.
    private val reader = MultiFormatReader().apply { setHints(HINTS) }

    private companion object {
        // Formáty relevantné pre dokumenty (QR pay-by-square, faktúry, produktové kódy).
        val FORMATS: List<BarcodeFormat> = listOf(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.AZTEC,
            BarcodeFormat.PDF_417,
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39,
            BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8,
        )

        val HINTS: Map<DecodeHintType, Any> = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to FORMATS,
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
    }

    // ZXing BinaryBitmap z Android Bitmap (celý obraz). HARDWARE bitmapy najprv prekopíruj na ARGB_8888.
    private fun toBinaryBitmap(bitmap: Bitmap): BinaryBitmap {
        // HARDWARE bitmapy nevedia getPixels -> prekopíruj na ARGB_8888 (bežní volajúci posielajú ARGB).
        val safe = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val width = safe.width
        val height = safe.height
        val pixels = IntArray(width * height)
        safe.getPixels(pixels, 0, width, 0, 0, width, height)
        if (safe !== bitmap) safe.recycle()
        val source = RGBLuminanceSource(width, height, pixels)
        return BinaryBitmap(HybridBinarizer(source))
    }

    // Rýchly jeden prechod celým obrazom (~30-150 ms). Vráti PRVÝ nájdený kód alebo null. Nevyhadzuje.
    // Toto je vhodné pre hromadný sken (väčšina fotiek kód nemá -> rýchlo skončí na NotFoundException).
    fun decodeFirst(bitmap: Bitmap): DecodedCode? {
        return try {
            val result: Result = reader.decode(toBinaryBitmap(bitmap), HINTS)
            DecodedCode(result.text, result.barcodeFormat)
        } catch (e: NotFoundException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
