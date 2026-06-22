package org.fossify.gallery.faces

import kotlin.math.sqrt

// Jednoduché greedy zoskupovanie L2-normalizovaných odtlačkov (cosine = dot product).
// Prah sa kalibruje — preto vie spočítať počet osôb pri viacerých prahoch naraz.
object FaceClusterer {
    const val THRESHOLD = 0.5f

    fun countPersons(embeddings: List<FloatArray>, threshold: Float = THRESHOLD): Int {
        if (embeddings.isEmpty()) return 0
        val centroids = ArrayList<FloatArray>()
        val counts = ArrayList<Int>()
        for (emb in embeddings) {
            if (emb.isEmpty()) continue
            var bestIdx = -1
            var bestSim = threshold
            for (i in centroids.indices) {
                val sim = dot(emb, centroids[i])
                if (sim > bestSim) {
                    bestSim = sim
                    bestIdx = i
                }
            }
            if (bestIdx >= 0) {
                val c = centroids[bestIdx]
                val n = counts[bestIdx]
                for (j in c.indices) {
                    c[j] = (c[j] * n + emb[j]) / (n + 1)
                }
                normalize(c)
                counts[bestIdx] = n + 1
            } else {
                centroids.add(emb.copyOf())
                counts.add(1)
            }
        }
        return centroids.size
    }

    fun countPersonsAtThresholds(embeddings: List<FloatArray>, thresholds: FloatArray): IntArray {
        return IntArray(thresholds.size) { countPersons(embeddings, thresholds[it]) }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun normalize(v: FloatArray) {
        var s = 0f
        for (x in v) s += x * x
        val norm = sqrt(s).coerceAtLeast(1e-9f)
        for (i in v.indices) v[i] = v[i] / norm
    }
}
