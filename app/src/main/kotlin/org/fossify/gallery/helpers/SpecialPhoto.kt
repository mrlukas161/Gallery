package org.fossify.gallery.helpers

import androidx.exifinterface.media.ExifInterface
import java.io.File

// Rozpoznanie „špeciálnych" fotiek priamo z metadát (rýchle — číta sa len hlavička):
//  • PANORÁMA — XMP GPano (alebo veľmi široký pomer strán),
//  • POHYBLIVÁ FOTKA — vložené video (MotionPhoto),
//  • HDR / Ultra HDR — XMP Container s GainMap,
//  • RAW — podľa prípony.
object SpecialPhoto {

    enum class Kind { PANORAMA, MOTION, HDR, RAW }

    private val RAW_EXT = setOf("dng", "raw", "arw", "cr2", "cr3", "nef", "nrw", "orf", "rw2", "srw", "pef")

    fun detect(path: String): Set<Kind> {
        val out = HashSet<Kind>()
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in RAW_EXT) out.add(Kind.RAW)

        val xmp = try {
            ExifInterface(path).getAttributeBytes(ExifInterface.TAG_XMP)?.toString(Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
        if (xmp != null) {
            if (xmp.contains("GPano:") || xmp.contains("UsePanoramaViewer")) out.add(Kind.PANORAMA)
            if (xmp.contains("GainMap") || xmp.contains("hdrgm:")) out.add(Kind.HDR)
        }
        if (Kind.PANORAMA !in out && isWide(path)) out.add(Kind.PANORAMA)
        if (MotionPhoto.read(path) != null) out.add(Kind.MOTION)
        return out
    }

    // veľmi široká fotka (pomer > 2.2) = takmer isto panoráma, aj keď XMP chýba
    private fun isWide(path: String): Boolean {
        return try {
            if (!File(path).exists()) return false
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, o)
            val w = o.outWidth
            val h = o.outHeight
            if (w <= 0 || h <= 0) return false
            val ratio = maxOf(w, h).toFloat() / minOf(w, h).toFloat()
            ratio >= 2.2f && maxOf(w, h) >= 3000
        } catch (e: Throwable) {
            false
        }
    }

    fun labelRes(kind: Kind): Int = when (kind) {
        Kind.PANORAMA -> org.fossify.gallery.R.string.special_panorama
        Kind.MOTION -> org.fossify.gallery.R.string.special_motion
        Kind.HDR -> org.fossify.gallery.R.string.special_hdr
        Kind.RAW -> org.fossify.gallery.R.string.special_raw
    }
}
