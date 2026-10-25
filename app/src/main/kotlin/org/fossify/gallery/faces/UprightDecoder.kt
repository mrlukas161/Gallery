package org.fossify.gallery.faces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.fossify.gallery.family.RelBox
import java.io.File
import java.io.IOException

// Dekódovanie fotky VŽDY „na stojato" (podľa EXIF orientácie).
//
// PREČO: BitmapFactory EXIF orientáciu IGNORUJE, ale Glide (zobrazenie) ju aplikuje. Dôsledky doteraz:
//  1) výrezy tvárí z fotiek na výšku boli otočené o 90° (Lukáš to videl v návrhoch),
//  2) detektor BlazeFace zvláda nakláňanie len ~±30–45°, takže na otočených fotkách tváre VÔBEC
//     nenašiel — časť „chýbajúcich/vzdialených" tvárí je práve toto.
// Preto všetko (detekcia, výrez, odtlačok) beží nad upright bitmapou a bboxy sa ukladajú v tomto
// priestore — konzistentne so zobrazením.
object UprightDecoder {

    // sample = inSampleSize dekódovania (pôvodné rozlíšenie ≈ bitmapa × sample), E1 veľkosť tváre
    data class Result(val bitmap: Bitmap, val rotationDegrees: Int, val mirrored: Boolean, val sample: Int = 1)

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
            return Result(raw, 0, false, sample)
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
            // E1: 5/7 rovnako ako Glide (TransformationUtils: TRANSPOSE = otočenie o 90° a zrkadlenie,
            // teda preklopenie podľa uhlopriečky (x, y) → (y, x); TRANSVERSE = −90° a zrkadlenie). Tu sa
            // zrkadlí PRED otočením, preto TRANSPOSE = zrkadlenie + 270° a TRANSVERSE = zrkadlenie + 90°.
            // Predtým to bolo naopak a obraz (aj rámčeky tvárí, box_space = 1) bol o 180° otočený oproti
            // zobrazeniu; staré rámčeky preklopí FaceMetaBackfill (FaceBoxSpace).
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                mirrored = true
                deg = 270
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                mirrored = true
                deg = 90
            }
        }
        if (mirrored) m.postScale(-1f, 1f)
        if (deg != 0) m.postRotate(deg.toFloat())
        return try {
            val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (out != raw) raw.recycle()
            Result(out, deg, mirrored, sample)
        } catch (e: Throwable) {
            Result(raw, 0, false, sample) // pri nedostatku pamäte radšej neotočená než žiadna
        }
    }

    // Rozmery [šírka, výška] bitmapy, ktorú by vrátil decode(path, maxSize), bez dekódovania pixelov
    // (len hlavička + EXIF orientácia). V tomto priestore sú uložené rámčeky tvárí, takže slúži na
    // prevod do relatívnych súradníc (family.RelBox). decode() sa tým nemení. null = súbor sa nedá prečítať.
    fun uprightSize(path: String, maxSize: Int): IntArray? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (ignored: IOException) {
            ExifInterface.ORIENTATION_NORMAL // rovnako ako decode(): bez čitateľného EXIF sa neotáča
        }
        val transform = RelBox.exifTransform(orientation)
        return RelBox.decodedUprightSize(bounds.outWidth, bounds.outHeight, maxSize, transform.rotationDegrees)
    }

    // prepočet starého bboxu (v neotočenom priestore W×H) do upright priestoru — používa sa LEN ako
    // kľúč na spárovanie starých záznamov s novo detegovanými tvárami (IoU). Presne ako decode():
    // najprv vodorovné zrkadlenie (postScale -1,1), potom otočenie v smere hodín o rotationDegrees
    // a posun späť do kladných súradníc (createBitmap).
    fun mapBox(
        left: Int, top: Int, right: Int, bottom: Int,
        rawW: Int, rawH: Int, rotationDegrees: Int,
        mirrored: Boolean = false,
    ): IntArray {
        val l = if (mirrored) rawW - right else left
        val r = if (mirrored) rawW - left else right
        return when (rotationDegrees) {
            90 -> intArrayOf(rawH - bottom, l, rawH - top, r)
            180 -> intArrayOf(rawW - r, rawH - bottom, rawW - l, rawH - top)
            270 -> intArrayOf(top, rawW - r, bottom, rawW - l)
            else -> intArrayOf(l, top, r, bottom)
        }
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
