package org.fossify.gallery.family

// Výber do AnchorsSet.MAX_ANCHORS reprezentatívnych odtlačkov osoby na prenos (spec 2.2).
// Kandidáti sú ručne potvrdené tváre osoby a jej vlastné vzory (AnchorEmbeddingEntity, napr. Picasa).
// Výber „najvzdialenejšieho bodu“: začne tvárou najbližšou k ťažisku (typická tvár) a potom vždy
// pridá tú, ktorá je najďalej od už vybraných. Tak sa dostanú rôzne pózy, okuliare aj roky,
// nie 20 takmer rovnakých záberov. Deterministické (rovnaký vstup = rovnaký výber).
// Čistá logika bez Androidu (testovaná v AnchorPickerTest). Vektory sú L2-normalizované.
object AnchorPicker {

    fun pick(vectors: List<FloatArray>, max: Int = AnchorsSet.MAX_ANCHORS): List<FloatArray> {
        val dim = vectors.firstOrNull { it.isNotEmpty() }?.size ?: return emptyList()
        val pool = vectors.filter { it.size == dim }.distinctBy { it.toList() }
        if (pool.size <= max) return pool
        val centroid = FloatArray(dim)
        pool.forEach { v -> for (i in 0 until dim) centroid[i] += v[i] }

        val chosen = ArrayList<Int>(max)
        chosen.add(pool.indices.maxBy { dot(pool[it], centroid) })
        // najvyššia podobnosť každého kandidáta k už vybraným
        val nearest = FloatArray(pool.size) { dot(pool[it], pool[chosen[0]]) }
        while (chosen.size < max) {
            var next = -1
            for (i in pool.indices) {
                if (i in chosen) continue
                if (next < 0 || nearest[i] < nearest[next]) next = i
            }
            chosen.add(next)
            for (i in pool.indices) nearest[i] = maxOf(nearest[i], dot(pool[i], pool[next]))
        }
        return chosen.map { pool[it] }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
