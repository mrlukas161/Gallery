package org.fossify.gallery.helpers

import android.content.Context
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
        // readCached: bez pohyblivého videa v XMP by sa inak pri každej fotke čítalo až 6 MB z konca súboru
        if (MotionPhoto.readCached(path) != null) out.add(Kind.MOTION)
        return out
    }

    // Ako detect(), ale s trvalou cache skenu: súbor, ktorý sa od minulého skenu nezmenil (rovnaký čas
    // úpravy aj veľkosť), sa znova nečíta. Ďalšie otvorenie „Špeciálnych fotiek" tak stojí len jedno
    // zistenie veľkosti súboru namiesto čítania tisícok súborov.
    fun detectCached(cache: ScanCache, path: String): Set<Kind> {
        val file = File(path)
        val modified = file.lastModified()
        val size = file.length()
        if (modified > 0L) {
            cache.lookup(path, modified, size)?.let { return it }
        }

        val kinds = detect(path)
        if (modified > 0L) {
            cache.put(path, modified, size, kinds)
        }
        return kinds
    }

    // Výsledky skenu uložené v cache aplikácie (text: cesta, čas úpravy, veľkosť, druhy). Pri chybe čítania
    // alebo zápisu sa cache jednoducho ignoruje — sken potom len prebehne celý znova.
    class ScanCache private constructor(private val file: File) {
        private class Entry(val modified: Long, val size: Long, val kinds: Set<Kind>)

        private val entries = HashMap<String, Entry>()
        private val seen = HashSet<String>()

        init {
            load()
        }

        @Synchronized
        fun lookup(path: String, modified: Long, size: Long): Set<Kind>? {
            val entry = entries[path] ?: return null
            if (entry.modified != modified || entry.size != size) {
                return null
            }

            seen.add(path)
            return entry.kinds
        }

        @Synchronized
        fun put(path: String, modified: Long, size: Long, kinds: Set<Kind>) {
            if (path.indexOf('\t') >= 0 || path.indexOf('\n') >= 0) {
                return // oddeľovače formátu — takú cestu radšej necachujeme
            }

            entries[path] = Entry(modified, size, kinds)
            seen.add(path)
        }

        // pruneUnseen = skenom prešla celá knižnica, takže záznamy o zmazaných fotkách sa zahodia
        @Synchronized
        fun save(pruneUnseen: Boolean) {
            try {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.bufferedWriter(Charsets.UTF_8).use { out ->
                    out.write(HEADER)
                    out.newLine()
                    entries.forEach { (path, e) ->
                        if (pruneUnseen && path !in seen) {
                            return@forEach
                        }

                        out.write(path)
                        out.write("\t${e.modified}\t${e.size}\t")
                        out.write(if (e.kinds.isEmpty()) "-" else e.kinds.joinToString(",") { it.name })
                        out.newLine()
                    }
                }

                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun load() {
            try {
                if (!file.exists()) {
                    return
                }

                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    val iterator = lines.iterator()
                    if (!iterator.hasNext() || iterator.next() != HEADER) {
                        return@useLines // iná verzia formátu — začíname odznova
                    }

                    while (iterator.hasNext()) {
                        val parts = iterator.next().split('\t')
                        if (parts.size != 4) {
                            continue
                        }

                        val modified = parts[1].toLongOrNull() ?: continue
                        val size = parts[2].toLongOrNull() ?: continue
                        val kinds: Set<Kind> = if (parts[3] == "-") {
                            emptySet()
                        } else {
                            parts[3].split(',').mapNotNull { name -> Kind.entries.firstOrNull { it.name == name } }.toSet()
                        }
                        entries[parts[0]] = Entry(modified, size, kinds)
                    }
                }
            } catch (ignored: Throwable) {
                entries.clear()
            }
        }

        companion object {
            // zmena rozpoznávania (nový druh, iný prah) = zvýšiť verziu, stará cache sa zahodí
            private const val HEADER = "#special-v1"

            fun open(context: Context): ScanCache = ScanCache(File(context.cacheDir, "special_scan_cache.tsv"))
        }
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
