package org.fossify.gallery.faces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.File

// Výrez tváre z PLNÉHO rozlíšenia fotky (B3) cez BitmapRegionDecoder — dekóduje sa len okolie tváre.
// Detekcia beží nad fotkou zmenšenou na ≤ 1024 px, kde má malá tvár (skupinová fotka) len pár desiatok
// pixelov; odtlačok z nej bol rozmazaný. Vzor mapovania: FaceCropLoader.decodeCropRegion.
// Jedna inštancia na fotku (dekodér sa otvorí raz pre všetky jej tváre), po spracovaní close().
// Zrkadlené EXIF orientácie a formáty bez region dekodéra vrátia null — volajúci použije zmenšenú fotku.
// MagicNumber: indexy rámčeka [left, top, right, bottom] a stupne otočenia EXIF
@Suppress("MagicNumber")
class FullResCrop private constructor(
    private val path: String,
    private val rawW: Int,
    private val rawH: Int,
    private val deg: Int,
    private val sample: Int,
    private val uprightW: Int,
    private val uprightH: Int,
) : Closeable {
    private var decoder: BitmapRegionDecoder? = null
    private var decoderFailed = false

    // výrez (upright) a očakávaný rámček tváre v jeho pixloch
    class Crop(val bitmap: Bitmap, val expected: FloatArray)

    // Výrez okolo rámčeka tváre (upright priestor zmenšenej fotky) s okrajom [marginFrac]. null = výrez
    // z plného rozlíšenia by nebol ostrejší než zo zmenšenej fotky (veľká tvár) alebo sa nedá dekódovať.
    @Suppress("ReturnCount")
    fun crop(l: Int, t: Int, r: Int, b: Int, marginFrac: Float): Crop? {
        val region = FullResMath.regionWithMargin(l, t, r, b, marginFrac, uprightW, uprightH) ?: return null
        val regionSample = FullResMath.regionSample((r - l) * sample)
        if (regionSample >= sample) return null
        val rect = FullResMath.rawRect(region, deg, rawW / sample, rawH / sample, sample, rawW, rawH) ?: return null
        val dec = decoder() ?: return null
        val raw = try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = regionSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            dec.decodeRegion(Rect(rect[0], rect[1], rect[2], rect[3]), opts)
        } catch (ignored: Throwable) {
            null
        } ?: return null
        val up = if (deg == 0) {
            raw
        } else {
            try {
                val rotation = Matrix().apply { postRotate(deg.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, rotation, true)
                    .also { if (it !== raw) raw.recycle() }
            } catch (ignored: Throwable) {
                raw.recycle()
                return null
            }
        }
        val fx = up.width.toFloat() / (region[2] - region[0])
        val fy = up.height.toFloat() / (region[3] - region[1])
        val expected = floatArrayOf(
            (l - region[0]) * fx, (t - region[1]) * fy,
            (r - region[0]) * fx, (b - region[1]) * fy,
        )
        return Crop(up, expected)
    }

    private fun decoder(): BitmapRegionDecoder? {
        if (decoderFailed) return null
        decoder?.let { return it }
        return try {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(path, false).also { decoder = it }
        } catch (ignored: Throwable) {
            decoderFailed = true
            null
        }
    }

    override fun close() {
        try {
            decoder?.recycle()
        } catch (ignored: Throwable) {
        }
        decoder = null
    }

    companion object {
        // [expectW]×[expectH] = rozmery zmenšenej upright fotky, v ktorej sú rámčeky (0 = nekontrolovať).
        // Keď nesedia (UprightDecoder pri nedostatku pamäte neotočil), plné rozlíšenie sa nepoužije.
        fun open(path: String, maxSize: Int, expectW: Int, expectH: Int): FullResCrop? = try {
            openOrNull(path, maxSize, expectW, expectH)
        } catch (ignored: Throwable) {
            null
        }

        private fun openOrNull(path: String, maxSize: Int, expectW: Int, expectH: Int): FullResCrop? {
            if (!File(path).exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            val deg = rotationOf(path) ?: return null
            val sample = if (rawW > 0 && rawH > 0) FullResMath.sampleFor(rawW, rawH, maxSize) else 0
            // sample 1 = fotka už je v plnom rozlíšení, výrez by nebol ostrejší
            if (sample <= 1) return null
            val dims = FullResMath.uprightDims(rawW, rawH, sample, deg)
            if (expectW > 0 && (dims[0] != expectW || dims[1] != expectH)) return null
            return FullResCrop(path, rawW, rawH, deg, sample, dims[0], dims[1])
        }

        // otočenie podľa EXIF; null = zrkadlená orientácia (zriedkavé) → použije sa zmenšená fotka
        private fun rotationOf(path: String): Int? {
            val orientation = try {
                ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (ignored: Throwable) {
                ExifInterface.ORIENTATION_NORMAL
            }
            return when (orientation) {
                ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> 0
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> null
            }
        }
    }
}
