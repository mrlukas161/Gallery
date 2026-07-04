package org.fossify.gallery.faces

// Zoskupí fotky podľa perceptuálnej podobnosti (Hammingova vzdialenosť 64-bit hashov <= prah).
object PhashGrouper {
    fun groupBySimilarity(hashes: List<PhashEntity>, threshold: Int = 6): List<List<String>> {
        val valid = hashes.filter { it.hashValue != 0L }
        if (valid.isEmpty()) return emptyList()
        val visited = HashSet<String>()
        val groups = ArrayList<List<String>>()
        for (i in valid.indices) {
            val h1 = valid[i]
            if (visited.contains(h1.path)) continue
            val group = ArrayList<String>()
            group.add(h1.path)
            visited.add(h1.path)
            for (j in valid.indices) {
                if (i == j) continue
                val h2 = valid[j]
                if (visited.contains(h2.path)) continue
                if (java.lang.Long.bitCount(h1.hashValue xor h2.hashValue) <= threshold) {
                    group.add(h2.path)
                    visited.add(h2.path)
                }
            }
            if (group.size >= 2) groups.add(group)
        }
        return groups.sortedByDescending { it.size }
    }
}
