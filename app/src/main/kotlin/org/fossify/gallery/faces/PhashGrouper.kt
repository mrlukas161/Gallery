package org.fossify.gallery.faces

// Zoskupí fotky podľa perceptuálnej podobnosti (Hammingova vzdialenosť 64-bit hashov <= prah).
//
// VÝKON: pôvodne sa porovnával každý s každým — pri 12 000 fotkách ~72 miliónov porovnaní, čo v
// appke znamenalo dlhé čakanie. Teraz sa hashe najprv rozhádžu do „vedierok" po 16 bitov: ak sa
// dva hashe líšia najviac o `threshold` bitov (6), potom sa pri 4 blokoch musia aspoň v jednom
// bloku zhodovať úplne, alebo majú v ňom veľmi málo chýb — kandidátov teda hľadáme len v tých
// istých vedierkach (pigeonhole princíp). Výsledok je prakticky rovnaký, rýchlosť rádovo lepšia.
// Pri prahu > 8 (veľmi voľné hľadanie) sa pre istotu použije pôvodné úplné porovnanie.
object PhashGrouper {

    private const val BLOCKS = 4
    private const val BITS_PER_BLOCK = 16

    fun groupBySimilarity(hashes: List<PhashEntity>, threshold: Int = 6): List<List<String>> {
        val valid = hashes.filter { it.hashValue != 0L }
        if (valid.size < 2) return emptyList()
        if (threshold > 8 || valid.size < 400) return bruteForce(valid, threshold)

        val buckets = Array(BLOCKS) { HashMap<Int, MutableList<Int>>() }
        for (i in valid.indices) {
            val h = valid[i].hashValue
            for (b in 0 until BLOCKS) {
                val key = ((h ushr (b * BITS_PER_BLOCK)) and 0xFFFFL).toInt()
                buckets[b].getOrPut(key) { ArrayList() }.add(i)
            }
        }

        val visited = BooleanArray(valid.size)
        val groups = ArrayList<List<String>>()
        val candidates = HashSet<Int>()

        for (i in valid.indices) {
            if (visited[i]) continue
            visited[i] = true
            val h1 = valid[i].hashValue
            val group = ArrayList<String>()
            group.add(valid[i].path)

            candidates.clear()
            for (b in 0 until BLOCKS) {
                val key = ((h1 ushr (b * BITS_PER_BLOCK)) and 0xFFFFL).toInt()
                buckets[b][key]?.let { candidates.addAll(it) }
            }
            for (j in candidates) {
                if (j == i || visited[j]) continue
                if (java.lang.Long.bitCount(h1 xor valid[j].hashValue) <= threshold) {
                    group.add(valid[j].path)
                    visited[j] = true
                }
            }
            if (group.size >= 2) groups.add(group)
        }
        return groups.sortedByDescending { it.size }
    }

    private fun bruteForce(valid: List<PhashEntity>, threshold: Int): List<List<String>> {
        val visited = BooleanArray(valid.size)
        val groups = ArrayList<List<String>>()
        for (i in valid.indices) {
            if (visited[i]) continue
            visited[i] = true
            val group = ArrayList<String>()
            group.add(valid[i].path)
            for (j in valid.indices) {
                if (i == j || visited[j]) continue
                if (java.lang.Long.bitCount(valid[i].hashValue xor valid[j].hashValue) <= threshold) {
                    group.add(valid[j].path)
                    visited[j] = true
                }
            }
            if (group.size >= 2) groups.add(group)
        }
        return groups.sortedByDescending { it.size }
    }
}
