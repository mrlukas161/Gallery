package org.fossify.gallery.faces

// Série záberov pre „Podobné a duplikáty“ → Série. Čistá logika bez Androidu (BurstGrouperTest).
//
//  - Séria podľa názvu súboru (…_BURST001, 00001IMG_00001_BURST20240101120000123.jpg …) v tom istom
//    priečinku, bez ohľadu na časové medzery. Kľúčom je časová pečiatka za BURST (Pixel), inak spoločná
//    predpona pred BURST (číslo za BURST je poradie záberu).
//  - Inak zábery z toho istého priečinka s dátumom nasnímania (EXIF), ktorých susedné snímky delí
//    najviac GAP_MS. Fotka BEZ dátumu nasnímania do časovej série nepatrí: čas zmeny súboru (mtime)
//    pri obnove zálohy, sťahovaní či WhatsAppe nič nehovorí o snímaní a robil z nich obrie „série“.
//  - Časová reťaz sa preruší tam, kde sa susedné zábery zjavne líšia (zmena scény, pHash > SCENE_BREAK_BITS).
//  - Žiadna séria nemá viac ako MAX_SHOTS záberov ani rozpätie nad MAX_SPAN_MS (intervalové snímky,
//    časozber): dlhá reťaz sa delí v najväčšej časovej medzere (pri zhode bližšie k stredu), kým nevyhovie.
object BurstGrouper {
    const val GAP_MS = 3_000L
    const val MAX_SPAN_MS = 60_000L
    const val MAX_SHOTS = 50
    const val SCENE_BREAK_BITS = 20

    // číslo za BURST s toľkými číslicami je časová pečiatka série, nie poradie záberu
    private const val TIMESTAMP_DIGITS = 10

    // pri delení dlhej reťaze nech ostanú na oboch stranách aspoň 2 zábery (jeden nie je séria)
    private const val MIN_PIECE = 2

    private val BURST_NAME = Regex("""^(.*?)[._-]?BURST(\d+)""", RegexOption.IGNORE_CASE)

    // takenMs = dátum nasnímania v ms (0 = neznámy), hash = average hash (0 = neznámy)
    data class Shot(val path: String, val takenMs: Long, val hash: Long = 0L) {
        val name: String get() = path.substringAfterLast('/')
        val folder: String get() = path.substringBeforeLast('/', "")
    }

    // spoločný kľúč série z názvu súboru, null = nie je to záber zo série
    fun burstKey(name: String): String? {
        val match = BURST_NAME.find(name) ?: return null
        val digits = match.groupValues[2]
        return if (digits.length >= TIMESTAMP_DIGITS) "t:$digits" else "p:" + match.groupValues[1].lowercase()
    }

    // Série (≥ 2 zábery), každá zoradená podľa času (séria podľa názvu podľa názvu súboru).
    fun group(shots: List<Shot>): List<List<Shot>> {
        val byName = LinkedHashMap<String, MutableList<Shot>>()
        val byFolder = LinkedHashMap<String, MutableList<Shot>>()
        for (shot in shots) {
            val key = burstKey(shot.name)
            if (key != null) byName.getOrPut(shot.folder + "/" + key) { ArrayList() }.add(shot)
        }
        val result = ArrayList<List<Shot>>()
        for (list in byName.values) {
            if (list.size >= 2) {
                result.addAll(splitLong(list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })))
            }
        }
        val inNameBurst = byName.values.filter { it.size >= 2 }.flatten().mapTo(HashSet()) { it.path }
        for (shot in shots) {
            if (shot.takenMs > 0 && shot.path !in inNameBurst) {
                byFolder.getOrPut(shot.folder) { ArrayList() }.add(shot)
            }
        }
        for (list in byFolder.values) {
            val sorted = list.sortedWith(compareBy({ it.takenMs }, { it.path }))
            for (chain in chains(sorted)) {
                for (scene in splitScenes(chain)) result.addAll(splitLong(scene))
            }
        }
        return result.filter { it.size >= 2 }
    }

    // susedné zábery (zoradené podľa času) s medzerou ≤ GAP_MS
    private fun chains(sorted: List<Shot>): List<List<Shot>> {
        val out = ArrayList<List<Shot>>()
        var current = ArrayList<Shot>()
        for (shot in sorted) {
            val prev = current.lastOrNull()
            if (prev != null && shot.takenMs - prev.takenMs > GAP_MS) {
                if (current.size >= 2) out.add(current)
                current = ArrayList()
            }
            current.add(shot)
        }
        if (current.size >= 2) out.add(current)
        return out
    }

    private fun splitScenes(chain: List<Shot>): List<List<Shot>> {
        val out = ArrayList<List<Shot>>()
        var start = 0
        for (k in 1 until chain.size) {
            val a = chain[k - 1].hash
            val b = chain[k].hash
            if (a != 0L && b != 0L && java.lang.Long.bitCount(a xor b) > SCENE_BREAK_BITS) {
                out.add(chain.subList(start, k))
                start = k
            }
        }
        out.add(chain.subList(start, chain.size))
        return out.filter { it.size >= 2 }
    }

    // Rozdelí reťaz, kým každý kus nemá ≤ MAX_SHOTS záberov a rozpätie ≤ MAX_SPAN_MS (bez rekurzie —
    // pri tisícoch intervalových snímok by bola hlboká).
    internal fun splitLong(chain: List<Shot>): List<List<Shot>> {
        val out = ArrayList<List<Shot>>()
        val todo = ArrayDeque<List<Shot>>()
        todo.addLast(chain)
        while (todo.isNotEmpty()) {
            val piece = todo.removeFirst()
            if (fits(piece)) {
                out.add(piece)
                continue
            }
            val cut = bestCut(piece)
            todo.addFirst(piece.subList(cut, piece.size))
            todo.addFirst(piece.subList(0, cut))
        }
        return out.filter { it.size >= 2 }
    }

    private fun fits(piece: List<Shot>): Boolean {
        if (piece.size > MAX_SHOTS) return false
        val times = piece.map { it.takenMs }.filter { it > 0 }
        return times.isEmpty() || times.max() - times.min() <= MAX_SPAN_MS
    }

    // index prvého záberu druhej časti: najväčšia časová medzera, pri zhode najbližšie k stredu
    private fun bestCut(piece: List<Shot>): Int {
        val size = piece.size
        val range = if (size >= 2 * MIN_PIECE) MIN_PIECE..size - MIN_PIECE else 1 until size
        val middle = size / 2
        var best = range.first
        var bestGap = Long.MIN_VALUE
        for (k in range) {
            val a = piece[k - 1].takenMs
            val b = piece[k].takenMs
            val gap = if (a > 0 && b > 0) b - a else 0L
            val closer = kotlin.math.abs(k - middle) < kotlin.math.abs(best - middle)
            if (gap > bestGap || gap == bestGap && closer) {
                best = k
                bestGap = gap
            }
        }
        return best
    }
}
