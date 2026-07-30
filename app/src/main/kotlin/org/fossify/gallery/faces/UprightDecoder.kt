package org.fossify.gallery.faces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

// Dekódovanie fotky VŽDY „na stojato" (podľa EXIF orientácie).
//
// PREČO: BitmapFactory EXIF orientáciu IGNORUJE, ale Glide (zobrazenie) ju aplikuje. Dôsledky doteraz:
//  1) výrezy tvárí z fotiek na výšku boli otočené o 90° (Lukáš to videl v návrhoch),
//  2) detektor BlazeFace zvláda nakláňanie len ~±30–45°, takže na otočených fotkách tváre VÔBEC
//     nenašiel — časť „chýbajúcich/vzdialených" tvárí je práve toto.
// Preto všetko (detekcia, výrez, odtlačok) beží nad upright bitmapou a bboxy sa ukladajú v tomto
// priestore — konzistentne so zobrazením.
object UprightDecoder {

    data class Result(val bitmap: Bitmap, val rotationDegrees: Int, val mirrored: Boolean)

    fun decode(path: String, maxSize: Int): Result? {
        val f = File(path)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > maxSize || h / sample > maxSize) sample *= 2
        val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null

        val orientation = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return Result(raw, 0, false)
        }

        val m = Matrix()
        var deg = 0
        var mirrored = false
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> deg = 90
            ExifInterface.ORIENTATION_ROTATE_180 -> deg = 180
            ExifInterface.ORIENTATION_ROTATE_270 -> deg = 270
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> mirrored = true
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                mirrored = true
                deg = 180
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                mirrored = true
                deg = 90
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                mirrored = true
                deg = 270
            }
        }
        if (mirrored) m.postScale(-1f, 1f)
        if (deg != 0) m.postRotate(deg.toFloat())
        return try {
            val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (out != raw) raw.recycle()
            Result(out, deg, mirrored)
        } catch (e: Throwable) {
            Result(raw, 0, false) // pri nedostatku pamäte radšej neotočená než žiadna
        }
    }

    // prepočet starého bboxu (v neotočenom priestore W×H) do upright priestoru — používa sa LEN ako
    // kľúč na spárovanie starých záznamov s novo detegovanými tvárami (IoU)
    fun mapBox(
        left: Int, top: Int, right: Int, bottom: Int,
        rawW: Int, rawH: Int, rotationDegrees: Int,
    ): IntArray = when (rotationDegrees) {
        90 -> intArrayOf(rawH - bottom, left, rawH - top, right)
        180 -> intArrayOf(rawW - right, rawH - bottom, rawW - left, rawH - top)
        270 -> intArrayOf(top, rawW - right, bottom, rawW - left)
        else -> intArrayOf(left, top, right, bottom)
    }

    fun iou(a: IntArray, b: IntArray): Float {
        val x1 = maxOf(a[0], b[0])
        val y1 = maxOf(a[1], b[1])
        val x2 = minOf(a[2], b[2])
        val y2 = minOf(a[3], b[3])
        val inter = maxOf(0, x2 - x1).toLong() * maxOf(0, y2 - y1).toLong()
        if (inter <= 0L) return 0f
        val areaA = (a[2] - a[0]).toLong() * (a[3] - a[1]).toLong()
        val areaB = (b[2] - b[0]).toLong() * (b[3] - b[1]).toLong()
        val union = areaA + areaB - inter
        return if (union <= 0L) 0f else inter.toFloat() / union.toFloat()
    }
}
