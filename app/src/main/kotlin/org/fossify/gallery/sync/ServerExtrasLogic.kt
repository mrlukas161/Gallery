package org.fossify.gallery.sync

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Čistá logika endpointov /api/zip, /api/favorites, /api/favorite a /api/motion (bez Android tried,
// ServerExtrasLogicTest). Obsluha je v ServerExtras.kt.

object ZipRequest {
    const val MAX_IDS = 500

    sealed class Ids {
        class Ok(val ids: List<Long>) : Ids()

        object TooMany : Ids()

        object Bad : Ids()
    }

    // pole ids z formulára: "1,2,3" (aj viac polí ids naraz); duplicity preč, poradie zachované
    fun parseIds(values: List<String>?, max: Int = MAX_IDS): Ids {
        val parts = values.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Ids.Bad
        val ids = LinkedHashSet<Long>()
        for (p in parts) {
            val id = p.toLongOrNull()?.takeIf { it >= 0 } ?: return Ids.Bad
            ids.add(id)
        }
        return if (ids.size > max) Ids.TooMany else Ids.Ok(ids.toList())
    }

    private val NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    // „Galeria-20261102-2105.zip“ (miestny čas mobilu)
    fun fileName(t: LocalDateTime): String = "Galeria-${NAME_TIME.format(t)}.zip"

    const val MISSING_NAME = "chybajuce.txt"

    // obsah chybajuce.txt: id, ktoré server nepozná (iný beh, zamknutý/vylúčený priečinok, zmazané),
    // a súbory, ktoré sa nedali prečítať; null = nič nechýba
    fun missingText(unknownIds: List<Long>, unreadable: List<String>): String? {
        if (unknownIds.isEmpty() && unreadable.isEmpty()) return null
        val sb = StringBuilder("Galéria+ – súbory, ktoré v ZIP-e nie sú\n")
        if (unknownIds.isNotEmpty()) {
            sb.append("\nNeznáme alebo nedostupné id (zmazané, zamknutý či vylúčený priečinok):\n")
            unknownIds.forEach { sb.append(it).append('\n') }
        }
        if (unreadable.isNotEmpty()) {
            sb.append("\nNepodarilo sa prečítať:\n")
            unreadable.forEach { sb.append(it).append('\n') }
        }
        return sb.toString()
    }
}

// Jedinečné mená položiek v ZIP-e: „IMG_1.jpg“, „IMG_1 (2).jpg“, „IMG_1 (3).jpg“ (bez ohľadu na veľkosť
// písmen — Windows by ich inak pri rozbalení prepísal). Lomky a riadiace znaky v mene sa nahradia.
class ZipNames {
    private val used = HashSet<String>()

    fun unique(raw: String): String {
        val clean = raw.filter { it >= ' ' && it != '\u007f' }.replace('/', '_').replace('\\', '_').trim()
            .ifEmpty { "subor" }
        if (used.add(clean.lowercase())) return clean
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var i = 2
        while (true) {
            val cand = "$base ($i)$ext"
            if (used.add(cand.lowercase())) return cand
            i++
        }
    }
}

// Výrez videa z pohyblivej fotky [offset, offset+length) s Range voči dĺžke výrezu.
object MotionSlice {
    sealed class Result {
        // absStart = poloha v celom súbore, len = koľko bajtov poslať
        data class Full(val absStart: Long, val len: Long) : Result()

        data class Partial(val absStart: Long, val len: Long, val contentRange: String) : Result()

        data class Unsatisfiable(val contentRange: String) : Result()
    }

    fun of(rangeHeader: String?, offset: Long, length: Long, ifRange: String? = null, etag: String? = null): Result =
        when (val r = HttpRange.parse(rangeHeader, length, ifRange, etag)) {
            HttpRange.Result.Full -> Result.Full(offset, length)
            HttpRange.Result.Unsatisfiable -> Result.Unsatisfiable(HttpRange.unsatisfiedRange(length))
            is HttpRange.Result.Partial -> Result.Partial(offset + r.start, r.length, HttpRange.contentRange(r, length))
        }
}

object FavMatch {
    // obľúbené sa v DB porovnávajú bez ohľadu na veľkosť písmen (COLLATE NOCASE)
    fun norm(path: String): String = path.lowercase()

    fun normalize(paths: Collection<String>): Set<String> = paths.mapTo(HashSet()) { norm(it) }

    // cesty (normalizované), ktorým sa medzi dvoma čítaniami zmenil stav
    fun changed(old: Set<String>, new: Set<String>): Set<String> = (old - new) + (new - old)
}

// pohyblivá fotka z trvalej cache skenu „Špeciálnych fotiek“ — bez čítania súboru: záznam platí, keď
// sedí veľkosť a čas zmeny (cache má ms zo súboru, MediaStore sekundy)
object MotionCacheMatch {
    private const val MS = 1000L

    fun matches(entryModifiedMs: Long, entrySize: Long, itemMtimeS: Long, itemSize: Long): Boolean =
        entrySize == itemSize && itemMtimeS > 0 && entryModifiedMs / MS == itemMtimeS
}
