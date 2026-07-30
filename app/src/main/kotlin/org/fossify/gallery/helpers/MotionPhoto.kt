package org.fossify.gallery.helpers

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile

// Pohyblivé fotky (Xiaomi/HyperOS „motion photo", Samsung, Pixel): v jednom JPEG súbore je za
// obrázkom prilepené krátke MP4 video. Zisťujeme, KDE video začína, aby sa dalo prehrať priamo
// zo súboru (bez rozbaľovania do dočasného súboru).
//
// Podporované varianty:
//  1) Xiaomi legacy XMP: GCamera:MicroVideoOffset = dĺžka videa -> začiatok = veľkosť súboru - offset
//  2) novšia schéma: GCamera:MotionPhoto + Container:Directory s Item:Length pre video položku
//  3) Samsung: marker "MotionPhoto_Data" v chvoste súboru
//  4) fallback: spätné hľadanie MP4 hlavičky "ftyp" v poslednej časti súboru
object MotionPhoto {

    data class Info(val videoOffset: Long, val videoLength: Long)

    private val MICRO_VIDEO_OFFSET = Regex("""MicroVideoOffset\s*=\s*"(\d+)"""")
    private val MICRO_VIDEO_FLAG = Regex("""MicroVideo\s*=\s*"1"""")
    private val MOTION_PHOTO_FLAG = Regex("""MotionPhoto\s*=\s*"1"""")
    private val ITEM_LENGTH = Regex("""Item:Length\s*=\s*"(\d+)"""")
    private const val SAMSUNG_MARKER = "MotionPhoto_Data"
    private const val TAIL_SCAN = 6 * 1024 * 1024 // koľko bajtov z konca prehľadať pri fallbacku

    fun isSupportedFile(path: String): Boolean {
        val p = path.lowercase()
        return p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".heic") || p.endsWith(".heif")
    }

    // vráti info o vloženom videu, alebo null ak fotka pohyblivá nie je
    fun read(path: String): Info? {
        if (!isSupportedFile(path)) return null
        val f = File(path)
        if (!f.exists() || f.length() < 100_000) return null
        val size = f.length()

        val xmp = try {
            ExifInterface(path).getAttributeBytes(ExifInterface.TAG_XMP)?.toString(Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
        if (xmp != null) {
            // 1) Xiaomi legacy — offset je DĹŽKA videa meraná od konca súboru
            MICRO_VIDEO_OFFSET.find(xmp)?.let { m ->
                val len = m.groupValues[1].toLongOrNull() ?: 0L
                if (len in 1 until size) {
                    val start = size - len
                    if (looksLikeMp4(f, start)) return Info(start, len)
                }
            }
            // 2) novšia schéma s Container:Directory — video je posledná položka
            if (MOTION_PHOTO_FLAG.containsMatchIn(xmp) || MICRO_VIDEO_FLAG.containsMatchIn(xmp)) {
                val lengths = ITEM_LENGTH.findAll(xmp).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()
                val videoLen = lengths.lastOrNull { it > 0 }
                if (videoLen != null && videoLen < size) {
                    val start = size - videoLen
                    if (looksLikeMp4(f, start)) return Info(start, videoLen)
                }
            }
        }
        // 3) Samsung marker + 4) spätné hľadanie ftyp
        return scanTail(f, size)
    }

    private fun looksLikeMp4(f: File, offset: Long): Boolean {
        if (offset <= 0 || offset >= f.length() - 8) return false
        return try {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(offset + 4)
                val b = ByteArray(4)
                raf.readFully(b)
                String(b, Charsets.US_ASCII) == "ftyp"
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun scanTail(f: File, size: Long): Info? {
        val readLen = minOf(size, TAIL_SCAN.toLong()).toInt()
        val start = size - readLen
        val buf = ByteArray(readLen)
        try {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                raf.readFully(buf)
            }
        } catch (e: Throwable) {
            return null
        }
        // Samsung: video nasleduje hneď za markerom
        val marker = SAMSUNG_MARKER.toByteArray(Charsets.US_ASCII)
        val mi = indexOf(buf, marker, 0)
        if (mi >= 0) {
            val after = start + mi + marker.size
            for (cand in after until minOf(after + 64, size - 8)) {
                if (looksLikeMp4(f, cand)) return Info(cand, size - cand)
            }
        }
        // generický fallback: prvé „....ftyp" v chvoste
        val ftyp = "ftyp".toByteArray(Charsets.US_ASCII)
        var i = indexOf(buf, ftyp, 0)
        while (i >= 4) {
            val cand = start + i - 4
            if (cand > 0 && looksLikeMp4(f, cand)) return Info(cand, size - cand)
            i = indexOf(buf, ftyp, i + 1)
        }
        return null
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
