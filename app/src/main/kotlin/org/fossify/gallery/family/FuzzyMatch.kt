package org.fossify.gallery.family

import kotlin.math.abs

// Záložná zhoda fotky, keď obsahový kľúč nesedí (napr. WhatsApp fotku prekomprimoval):
//  - s dátumom nasnímania na oboch stranách: pHash Hamming ≤ 6 (rovnaký prah ako PhashGrouper) A dátum ± 2 s;
//  - keď dátum (EXIF) chýba aspoň na jednej strane: len pHash ≤ 3.
// pHash 0 = neplatný (PhashGrouper ho tiež ignoruje), taká fotka sa nespáruje nikdy.
// Zhoda len cez zálohu prenesie tváre a priradenia, ale nie OCR (rozhoduje BundleReceiver).
// Čistá logika bez Androidu (testovaná v FuzzyMatchTest).
object FuzzyMatch {
    const val MAX_PHASH_DISTANCE = 6
    const val MAX_PHASH_DISTANCE_WITHOUT_DATE = 3
    const val MAX_DATE_DIFF_MS = 2_000L

    // takenAtMs = dátum nasnímania v ms; null alebo ≤ 0 = neznámy (chýba EXIF)
    data class Fingerprint(val phash: Long, val takenAtMs: Long?)

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun matches(a: Fingerprint, b: Fingerprint): Boolean = score(a, b) != null

    // Najlepší kandidát (najmenšia pHash vzdialenosť, potom najmenší rozdiel dátumu). Keď sú dvaja
    // kandidáti rovnako dobrí (napr. sériové snímky), vráti null: radšej nič, než výsledky na cudzej fotke.
    fun <K> findBest(target: Fingerprint, candidates: Map<K, Fingerprint>): K? {
        var bestKey: K? = null
        var bestScore: Score? = null
        var ambiguous = false
        for ((key, fp) in candidates) {
            val s = score(target, fp) ?: continue
            val cmp = bestScore?.let { s.compareTo(it) } ?: -1
            when {
                cmp < 0 -> {
                    bestKey = key
                    bestScore = s
                    ambiguous = false
                }
                cmp == 0 -> ambiguous = true
            }
        }
        return if (ambiguous) null else bestKey
    }

    private data class Score(val distance: Int, val dateDiffMs: Long) : Comparable<Score> {
        override fun compareTo(other: Score): Int =
            compareValuesBy(this, other, Score::distance, Score::dateDiffMs)
    }

    // null = nezhoda
    private fun score(a: Fingerprint, b: Fingerprint): Score? {
        if (a.phash == 0L || b.phash == 0L) return null
        val distance = hammingDistance(a.phash, b.phash)
        val ta = a.takenAtMs?.takeIf { it > 0L }
        val tb = b.takenAtMs?.takeIf { it > 0L }
        if (ta == null || tb == null) {
            return if (distance <= MAX_PHASH_DISTANCE_WITHOUT_DATE) Score(distance, Long.MAX_VALUE) else null
        }
        val diff = abs(ta - tb)
        return if (distance <= MAX_PHASH_DISTANCE && diff <= MAX_DATE_DIFF_MS) Score(distance, diff) else null
    }
}
