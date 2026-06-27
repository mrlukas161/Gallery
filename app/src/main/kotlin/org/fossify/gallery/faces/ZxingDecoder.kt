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
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader

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

    // Robustný viac-kódový variant pre MALÉ kódy vo veľkých skenoch dokumentov.
    // 1) celý obraz (QRCodeMultiReader + GenericMultipleBarcodeReader), 2) ak nič -> dlaždice NxN s prekrytím.
    // Pozor: dlaždicový fallback je POMALÝ (1-3 s) — NEpoužívať na hromadný sken, len pre cielený sken dokumentu.
    fun decodeAll(bitmap: Bitmap, grid: Int = 3, overlap: Float = 0.15f): List<DecodedCode> {
        val found = LinkedHashSet<DecodedCode>()
        decodeMultiInto(toBinaryBitmap(bitmap), found)
        if (found.isNotEmpty()) return found.toList()

        if (grid >= 2) {
            val w = bitmap.width
            val h = bitmap.height
            val cellW = w / grid
            val cellH = h / grid
            if (cellW > 0 && cellH > 0) {
                val padX = (cellW * overlap).toInt()
                val padY = (cellH * overlap).toInt()
                for (gy in 0 until grid) {
                    for (gx in 0 until grid) {
                        val x = (gx * cellW - padX).coerceAtLeast(0)
                        val y = (gy * cellH - padY).coerceAtLeast(0)
                        val tileW = (cellW + 2 * padX).coerceAtMost(w - x)
                        val tileH = (cellH + 2 * padY).coerceAtMost(h - y)
                        if (tileW <= 0 || tileH <= 0) continue
                        try {
                            val tilePixels = IntArray(tileW * tileH)
                            bitmap.getPixels(tilePixels, 0, tileW, x, y, tileW, tileH)
                            val src = RGBLuminanceSource(tileW, tileH, tilePixels)
                            decodeMultiInto(BinaryBitmap(HybridBinarizer(src)), found)
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        }
        return found.toList()
    }

    private fun decodeMultiInto(image: BinaryBitmap, sink: MutableSet<DecodedCode>) {
        val qrMulti = QRCodeMultiReader()
        try {
            qrMulti.decodeMultiple(image, HINTS).forEach { sink.add(DecodedCode(it.text, it.barcodeFormat)) }
        } catch (e: Exception) {
        } finally {
            qrMulti.reset()
        }
        val delegate = MultiFormatReader().apply { setHints(HINTS) }
        val generic = GenericMultipleBarcodeReader(delegate)
        try {
            generic.decodeMultiple(image, HINTS).forEach { sink.add(DecodedCode(it.text, it.barcodeFormat)) }
        } catch (e: Exception) {
        } finally {
            delegate.reset()
        }
    }
}
