package org.fossify.gallery.family

import java.security.MessageDigest

// Výber do AnchorsSet.MAX_ANCHORS reprezentatívnych odtlačkov osoby na prenos (spec 2.2).
// Kandidáti sú ručne potvrdené tváre osoby a jej vlastné vzory (AnchorEmbeddingEntity, napr. Picasa).
// D7: najprv sa vyradia odľahlé vektory (chybne priradená tvár by sa inak šírila do rodiny): kosínus
// k medoidu (najtypickejšej tvári) pod OUTLIER_COSINE, najviac však štvrtina kandidátov (prah sa vtedy
// zníži na 25. percentil), aby rôznorodá osoba (deti, roky) neprišla o väčšinu tvárí.
// Potom výber „najvzdialenejšieho bodu“: začne tvárou najbližšou k ťažisku (typická tvár) a potom vždy
// pridá tú, ktorá je najďalej od už vybraných. Tak sa dostanú rôzne pózy, okuliare aj roky,
// nie 20 takmer rovnakých záberov. Deterministické (rovnaký vstup = rovnaký výber).
// Čistá logika bez Androidu (testovaná v AnchorPickerTest). Vektory sú L2-normalizované.
object AnchorPicker {
    const val OUTLIER_COSINE = 0.35f
    private const val MIN_FOR_OUTLIERS = 3
    private const val MAX_DROP_PERCENTILE = 0.25

    // medoid sa hľadá najviac z toľkých kandidátov (rovnomerne vybraných), aby výpočet ostal lacný
    private const val MEDOID_SAMPLE = 300

    fun pick(vectors: List<FloatArray>, max: Int = AnchorsSet.MAX_ANCHORS): List<FloatArray> {
        val dim = vectors.firstOrNull { it.isNotEmpty() }?.size ?: return emptyList()
        val pool = withoutOutliers(vectors.filter { it.size == dim }.distinctBy { it.toList() })
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

    // Kandidáti bez odľahlých vektorov (poradie zachované).
    fun withoutOutliers(pool: List<FloatArray>): List<FloatArray> {
        if (pool.size < MIN_FOR_OUTLIERS) return pool
        val medoid = pool[medoidIndex(pool)]
        val sims = pool.map { dot(it, medoid) }
        val sorted = sims.sorted()
        val percentile = sorted[(sorted.size * MAX_DROP_PERCENTILE).toInt().coerceAtMost(sorted.size - 1)]
        val threshold = minOf(OUTLIER_COSINE, percentile)
        return pool.filterIndexed { i, _ -> sims[i] >= threshold }
    }

    // Kandidát s najvyšším súčtom podobností k ostatným (na rovnomernej vzorke najviac MEDOID_SAMPLE).
    private fun medoidIndex(pool: List<FloatArray>): Int {
        val step = maxOf(1, (pool.size + MEDOID_SAMPLE - 1) / MEDOID_SAMPLE)
        val sample = pool.indices.filter { it % step == 0 }
        return sample.maxBy { i -> sample.sumOf { j -> dot(pool[i], pool[j]).toDouble() } }
    }

    // D7: odtlačok sady (poradie nehrá rolu), aby sa rovnaká sada neposlala dvakrát (AnchorOutbox)
    fun setHash(vectors: List<FloatArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        vectors.map { AnchorVectors.toBase64(it) }.sorted().forEach { digest.update(it.toByteArray(Charsets.US_ASCII)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
