package org.fossify.gallery.helpers

// Merania porovnávača (PhotoMeasure) podľa cesty, času zmeny a veľkosti súboru: LRU v pamäti procesu
// a riadky pre malý súbor v cacheDir (CompareAnalysis), aby sa skupina po návrate nemusela počítať znova.
// Samostatné úložisko — NIE people/faces/extras.db. Čistá logika bez Androidu (CompareCacheTest).
class CompareCache(private val maxEntries: Int = DEFAULT_MAX) {
    private val map = object : LinkedHashMap<String, Entry>(INITIAL, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    private class Entry(val measure: PhotoMeasure, val size: Long)

    val size: Int
        @Synchronized get() = map.size

    @Synchronized
    fun get(path: String, modified: Long, size: Long): PhotoMeasure? {
        val e = map[path] ?: return null
        return if (e.measure.modified == modified && e.size == size) e.measure else null
    }

    @Synchronized
    fun put(measure: PhotoMeasure, size: Long) {
        map[measure.path] = Entry(measure, size)
    }

    // riadky zo súboru (prvý = hlavička verzie; iná verzia = celý súbor sa ignoruje)
    @Synchronized
    fun load(lines: List<String>) {
        if (lines.firstOrNull() != HEADER) return
        for (line in lines.drop(1)) {
            val parts = line.split(SEP)
            val m = if (parts.size == FIELDS) decode(parts) else null
            if (m != null) map[m.path] = Entry(m, parts[I_SIZE].toDouble().toLong())
        }
    }

    // riadky pre súbor (od najstaršieho použitia), cesty s tabulátorom alebo novým riadkom sa neukladajú
    @Synchronized
    fun dump(): List<String> {
        val out = ArrayList<String>(map.size + 1)
        out.add(HEADER)
        for (e in map.values) {
            val m = e.measure
            if (m.path.contains(SEP) || m.path.contains('\n')) continue
            out.add(
                listOf(
                    m.path, m.modified, e.size, m.sharpness, m.exposure, m.faces, m.closedEyes, m.smiles,
                    if (m.facesChecked) 1 else 0, m.width, m.height,
                ).joinToString(SEP.toString()),
            )
        }
        return out
    }

    private fun decode(p: List<String>): PhotoMeasure? {
        if (p[I_PATH].isEmpty() || NUMERIC.any { p[it].toDoubleOrNull() == null }) return null
        fun num(i: Int) = p[i].toDouble()
        return PhotoMeasure(
            path = p[I_PATH],
            modified = p[I_MODIFIED].toLongOrNull() ?: num(I_MODIFIED).toLong(),
            sharpness = num(I_SHARP),
            exposure = num(I_EXPOSURE),
            faces = num(I_FACES).toInt(),
            closedEyes = num(I_CLOSED).toInt(),
            smiles = num(I_SMILES).toInt(),
            facesChecked = p[I_CHECKED] == "1",
            width = num(I_WIDTH).toInt(),
            height = num(I_HEIGHT).toInt(),
        )
    }

    companion object {
        const val HEADER = "compare-cache-v1"
        const val DEFAULT_MAX = 3000
        private const val SEP = '\t'
        private const val INITIAL = 64
        private const val LOAD_FACTOR = 0.75f
        private const val FIELDS = 11
        private const val I_PATH = 0
        private const val I_MODIFIED = 1
        private const val I_SIZE = 2
        private const val I_SHARP = 3
        private const val I_EXPOSURE = 4
        private const val I_FACES = 5
        private const val I_CLOSED = 6
        private const val I_SMILES = 7
        private const val I_CHECKED = 8
        private const val I_WIDTH = 9
        private const val I_HEIGHT = 10
        private val NUMERIC = listOf(
            I_MODIFIED, I_SIZE, I_SHARP, I_EXPOSURE, I_FACES, I_CLOSED, I_SMILES, I_WIDTH, I_HEIGHT,
        )
    }
}
