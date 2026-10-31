package org.fossify.gallery.faces

// Zoskupí fotky podľa perceptuálneho hashu (average hash 64 bitov z PhashIndexer) na „Presné kópie“
// a „Podobné“. Čistá logika bez Androidu (PhashGrouperTest).
//
// KANDIDÁTI — multi-index podľa princípu holubníka: 64 bitov sa rozdelí na T + 1 blokov (pri prahu
// T = 6 na 7 blokov po 9–10 bitoch). Keď sa dva hashe líšia najviac v T bitoch, tie rozdiely zasiahnu
// najviac T blokov, takže aspoň jeden blok majú CELÝ rovnaký. Stačí preto porovnať len fotky, ktoré sa
// zhodujú v niektorom celom bloku — nič pod prahom sa nevynechá. (Pôvodne boli 4 bloky po 16 bitoch:
// to zaručí zhodný blok len pri ≤ 3 rozdieloch, pri 4–6 sa vynechala desatina až dve pätiny párov.)
// Dvojica sa vyhodnotí len v PRVOM bloku, v ktorom sa zhoduje, takže netreba množinu videných dvojíc.
//
// SKUPINY — union-find s ÚPLNOU väzbou: hrany sa spracujú od najbližších (vzdialenosť, potom poradie
// fotiek) a dve skupiny sa spoja, len keď sa KAŽDÁ fotka jednej líši od KAŽDEJ fotky druhej najviac
// o prah. Reťaz A~B~C, v ktorej sú A a C ďaleko, tak A s C nespojí a skupina nevzniká „okolo prvej
// fotky“. Fotky sa najprv zoradia podľa cesty, takže výsledok nezávisí od poradia vstupu.
//
// TYPY — najprv „Presné kópie“ (≤ 2 bity a rovnaké rozlíšenie alebo pomer strán), zo zvyšku „Podobné“
// (≤ 6 bitov). Average hash zlepuje takmer jednofarebné obrázky (tmavé fotky, prázdne obrazovky — hash
// je takmer samé jednotky) a snímky obrazovky (rovnaké rozloženie aplikácie): tie sa berú len ako presné
// kópie a navyše musia mať rovnakú veľkosť súboru, nikdy nie ako „Podobné“.
object PhashGrouper {
    const val SIMILAR_THRESHOLD = 6
    const val EXACT_THRESHOLD = 2

    private const val HASH_BITS = 64

    // hash s nanajvýš toľkými jednotkami (alebo nulami) = obrázok bez štruktúry (takmer jedna farba)
    private const val LOW_INFO_BITS = 6

    // presná kópia: pomer strán po zmenšení sa môže posunúť zaokrúhlením
    private const val EXACT_ASPECT_TOLERANCE = 0.02

    // podobné fotky s výrazne iným pomerom strán (na výšku vs. panoráma) nie sú tá istá scéna
    private const val SIMILAR_ASPECT_TOLERANCE = 0.25

    // zbalená hrana: vzdialenosť (7 bitov) | i (28 bitov) | j (28 bitov) — triedenie Long = podľa (d, i, j)
    private const val INDEX_BITS = 28
    private const val INDEX_MASK = (1L shl INDEX_BITS) - 1
    private const val DIST_SHIFT = INDEX_BITS * 2

    // kľúč (hodnota bloku | index) a kľúč dvojice skupín: horných 32 bitov | dolných 32 bitov
    private const val KEY_SHIFT = 32
    private const val MIN_BLOCKS = 4
    private const val INITIAL_CAPACITY = 256

    // width/height = rozmery z MediaStore (0 = neznáme), sizeBytes = veľkosť súboru (0 = neznáma)
    data class Photo(
        val path: String,
        val hash: Long,
        val width: Int = 0,
        val height: Int = 0,
        val sizeBytes: Long = 0L,
    )

    enum class Kind { EXACT, SIMILAR }

    // paths zoradené podľa cesty
    class Group(val kind: Kind, val paths: List<String>)

    // Pôvodné rozhranie (web MediaServer): všetky skupiny bez rozmerov — teda len „Podobné“, najväčšie prvé.
    fun groupBySimilarity(hashes: List<PhashEntity>, threshold: Int = SIMILAR_THRESHOLD): List<List<String>> =
        group(hashes.map { Photo(it.path, it.hashValue) }, threshold)
            .map { it.paths }
            .sortedByDescending { it.size }

    fun group(photos: List<Photo>, similar: Int = SIMILAR_THRESHOLD, exact: Int = EXACT_THRESHOLD): List<Group> {
        // pevné poradie (podľa cesty) — pri rovnakých vzdialenostiach rozhoduje poradie hrán, nie vstupu
        val valid = photos.filter { it.hash != 0L }.distinctBy { it.path }.sortedBy { it.path }
        if (valid.size < 2) return emptyList()
        val hashes = LongArray(valid.size) { valid[it].hash }
        val strict = BooleanArray(valid.size) { isLowInfo(hashes[it]) || isScreenshot(valid[it].path) }
        val edges = candidateEdges(hashes, maxOf(similar, exact))
        val active = BooleanArray(valid.size) { true }

        val exactClusters = link(valid.size, edges, active) { a, b ->
            distance(hashes[a], hashes[b]) <= exact && sameImage(valid[a], valid[b], strict[a] || strict[b])
        }
        exactClusters.forEach { cluster -> cluster.forEach { active[it] = false } }

        val similarClusters = link(valid.size, edges, active) { a, b ->
            !strict[a] && !strict[b] && distance(hashes[a], hashes[b]) <= similar &&
                aspectClose(valid[a], valid[b], SIMILAR_ASPECT_TOLERANCE, unknownMatches = true)
        }
        return exactClusters.map { c -> Group(Kind.EXACT, c.map { valid[it].path }) } +
            similarClusters.map { c -> Group(Kind.SIMILAR, c.map { valid[it].path }) }
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    // takmer jednofarebný obrázok: pri rovnomernom jase je každý pixel ≥ priemer → samé jednotky
    fun isLowInfo(hash: Long): Boolean {
        val ones = java.lang.Long.bitCount(hash)
        return ones <= LOW_INFO_BITS || ones >= HASH_BITS - LOW_INFO_BITS
    }

    fun isScreenshot(path: String): Boolean {
        val lower = path.lowercase()
        return lower.substringAfterLast('/').startsWith("screenshot") || lower.contains("/screenshots/")
    }

    // Presná kópia: rovnaké rozmery alebo pomer strán (zmenšená kópia). „Prísna“ dvojica (obrázok bez
    // štruktúry alebo snímka obrazovky) musí mať navyše rovnaké rozmery aj veľkosť súboru.
    private fun sameImage(a: Photo, b: Photo, strict: Boolean): Boolean {
        if (strict) {
            return a.sizeBytes > 0 && a.sizeBytes == b.sizeBytes && a.width > 0 && a.width == b.width &&
                a.height == b.height
        }
        val sameDims = a.width > 0 && a.height > 0 && a.width == b.width && a.height == b.height
        return sameDims || aspectClose(a, b, EXACT_ASPECT_TOLERANCE, unknownMatches = false)
    }

    // pomer strán bez ohľadu na orientáciu (dlhšia / kratšia strana)
    private fun aspectClose(a: Photo, b: Photo, tolerance: Double, unknownMatches: Boolean): Boolean {
        val ra = aspect(a) ?: return unknownMatches
        val rb = aspect(b) ?: return unknownMatches
        return kotlin.math.abs(ra - rb) / minOf(ra, rb) <= tolerance
    }

    private fun aspect(p: Photo): Double? {
        if (p.width <= 0 || p.height <= 0) return null
        return maxOf(p.width, p.height).toDouble() / minOf(p.width, p.height)
    }

    // Všetky dvojice s Hammingovou vzdialenosťou ≤ threshold, zbalené a zoradené (d, i, j), i < j.
    internal fun candidateEdges(hashes: LongArray, threshold: Int): LongArray {
        require(hashes.size < INDEX_MASK) { "príliš veľa fotiek" }
        val out = LongBuffer()
        if (threshold >= HASH_BITS / 2) {
            // pri takom voľnom prahu sú bloky 1-bitové a nič neušetria — porovnať každý s každým
            for (i in hashes.indices) {
                for (j in i + 1 until hashes.size) out.addIfClose(hashes, i, j, threshold)
            }
        } else {
            MultiIndex(hashes, threshold, out).run()
        }
        return out.sorted()
    }

    private fun link(n: Int, edges: LongArray, active: BooleanArray, linked: (Int, Int) -> Boolean): List<List<Int>> {
        val linker = CompleteLinkage(n, linked)
        for (e in edges) {
            val i = ((e ushr INDEX_BITS) and INDEX_MASK).toInt()
            val j = (e and INDEX_MASK).toInt()
            if (active[i] && active[j] && linked(i, j)) linker.tryMerge(i, j)
        }
        return linker.clusters()
    }

    // Kandidáti z blokov (princíp holubníka, viď hlavička): pre každý blok sa fotky zoradia podľa jeho hodnoty
    // a porovnajú sa len fotky v rovnakom behu hodnôt.
    private class MultiIndex(private val hashes: LongArray, private val threshold: Int, private val out: LongBuffer) {
        // viac blokov než T + 1 na správnosti nič nemení; aspoň MIN_BLOCKS, aby sa hodnota bloku zmestila do kľúča
        private val blocks = maxOf(threshold + 1, MIN_BLOCKS)
        private val starts = IntArray(blocks + 1) { it * HASH_BITS / blocks }
        private val keyed = LongArray(hashes.size)

        fun run() {
            for (b in 0 until blocks) {
                // (hodnota bloku | index) zoradené → fotky so zhodným blokom idú za sebou
                for (i in hashes.indices) keyed[i] = (blockValue(hashes[i], b) shl KEY_SHIFT) or i.toLong()
                keyed.sort()
                var runStart = 0
                while (runStart < keyed.size) {
                    val value = keyed[runStart] ushr KEY_SHIFT
                    var runEnd = runStart + 1
                    while (runEnd < keyed.size && (keyed[runEnd] ushr KEY_SHIFT) == value) runEnd++
                    pairsInRun(runStart, runEnd, b)
                    runStart = runEnd
                }
            }
        }

        private fun pairsInRun(from: Int, to: Int, block: Int) {
            for (x in from until to) {
                for (y in x + 1 until to) {
                    val i = keyed[x].toInt()
                    val j = keyed[y].toInt()
                    // dvojica sa vyhodnotí len v prvom bloku, v ktorom sa zhoduje
                    if (firstSharedBlock(hashes[i] xor hashes[j]) == block) {
                        out.addIfClose(hashes, minOf(i, j), maxOf(i, j), threshold)
                    }
                }
            }
        }

        private fun blockValue(hash: Long, block: Int): Long {
            val mask = (1L shl (starts[block + 1] - starts[block])) - 1
            return (hash ushr starts[block]) and mask
        }

        // volá sa len pre dvojicu, ktorá niektorý blok zdieľa — cyklus teda skončí
        private fun firstSharedBlock(diff: Long): Int {
            var b = 0
            while (blockValue(diff, b) != 0L) b++
            return b
        }
    }

    // Union-find s úplnou väzbou (viď hlavička). Neúspešné spojenie dvojice skupín sa pamätá: skupiny len
    // rastú, takže väčšia skupina už podmienku splniť nemôže.
    private class CompleteLinkage(n: Int, private val linked: (Int, Int) -> Boolean) {
        private val parent = IntArray(n) { it }
        private val members = arrayOfNulls<MutableList<Int>>(n)
        private val failed = HashSet<Long>()

        fun tryMerge(i: Int, j: Int) {
            val ri = find(i)
            val rj = find(j)
            val pairKey = (minOf(ri, rj).toLong() shl KEY_SHIFT) or maxOf(ri, rj).toLong()
            if (ri == rj || pairKey in failed) return
            val a = members[ri] ?: mutableListOf(ri)
            val b = members[rj] ?: mutableListOf(rj)
            if (!a.all { x -> b.all { y -> linked(x, y) } }) {
                failed.add(pairKey)
                return
            }
            val keepA = a.size >= b.size
            val root = if (keepA) ri else rj
            val gone = if (keepA) rj else ri
            members[root] = if (keepA) a.apply { addAll(b) } else b.apply { addAll(a) }
            members[gone] = null
            parent[gone] = root
        }

        // skupiny s aspoň 2 fotkami, fotky aj skupiny v poradí indexov
        fun clusters(): List<List<Int>> =
            members.filterNotNull().filter { it.size >= 2 }.map { it.sorted() }.sortedBy { it.first() }

        private fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var c = x
            while (parent[c] != root) {
                val next = parent[c]
                parent[c] = root
                c = next
            }
            return root
        }
    }

    // rastúce pole Long bez boxovania (hrán býva rádovo tisíce, pri tmavých sériách aj státisíce)
    private class LongBuffer {
        private var data = LongArray(INITIAL_CAPACITY)
        private var size = 0

        fun addIfClose(hashes: LongArray, i: Int, j: Int, threshold: Int) {
            val d = distance(hashes[i], hashes[j])
            if (d > threshold) return
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = (d.toLong() shl DIST_SHIFT) or (i.toLong() shl INDEX_BITS) or j.toLong()
        }

        fun sorted(): LongArray = data.copyOf(size).also { it.sort() }
    }
}
