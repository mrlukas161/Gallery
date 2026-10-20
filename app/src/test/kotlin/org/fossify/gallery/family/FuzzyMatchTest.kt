package org.fossify.gallery.family

import org.fossify.gallery.family.FuzzyMatch.Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatchTest {
    private val base = 0x0123_4567_89AB_CDEFL
    private val taken = 1_700_000_000_000L

    // hash, ktorý sa od `base` líši presne v `bits` najnižších bitoch
    private fun flip(bits: Int): Long = base xor ((1L shl bits) - 1)

    @Test
    fun hamming() {
        assertEquals(0, FuzzyMatch.hammingDistance(base, base))
        assertEquals(6, FuzzyMatch.hammingDistance(base, flip(6)))
        assertEquals(64, FuzzyMatch.hammingDistance(0L, -1L))
    }

    @Test
    fun withDatePhashBoundary() {
        val a = Fingerprint(base, taken)
        assertTrue(FuzzyMatch.matches(a, Fingerprint(flip(6), taken)))
        assertFalse(FuzzyMatch.matches(a, Fingerprint(flip(7), taken)))
    }

    @Test
    fun withDateTimeBoundary() {
        val a = Fingerprint(base, taken)
        assertTrue(FuzzyMatch.matches(a, Fingerprint(base, taken + 2_000L)))
        assertTrue(FuzzyMatch.matches(a, Fingerprint(base, taken - 2_000L)))
        assertFalse(FuzzyMatch.matches(a, Fingerprint(base, taken + 2_001L)))
        assertFalse(FuzzyMatch.matches(a, Fingerprint(base, taken - 2_001L)))
    }

    @Test
    fun withoutDateStricterPhash() {
        val noDate = Fingerprint(base, null)
        assertTrue(FuzzyMatch.matches(noDate, Fingerprint(flip(3), null)))
        assertFalse(FuzzyMatch.matches(noDate, Fingerprint(flip(4), null)))
        // dátum chýba len na jednej strane → tiež prísnejší prah
        assertTrue(FuzzyMatch.matches(noDate, Fingerprint(flip(3), taken)))
        assertFalse(FuzzyMatch.matches(Fingerprint(base, taken), Fingerprint(flip(4), 0L)))
        assertFalse(FuzzyMatch.matches(Fingerprint(base, -5L), Fingerprint(flip(5), taken)))
    }

    @Test
    fun zeroHashNeverMatches() {
        assertFalse(FuzzyMatch.matches(Fingerprint(0L, taken), Fingerprint(0L, taken)))
        assertFalse(FuzzyMatch.matches(Fingerprint(0L, null), Fingerprint(0L, null)))
    }

    @Test
    fun findBestPicksClosest() {
        val target = Fingerprint(base, taken)
        val candidates = mapOf(
            "far" to Fingerprint(flip(5), taken),
            "near" to Fingerprint(flip(1), taken + 1_500L),
            "nearer-time" to Fingerprint(flip(1), taken + 100L),
            "too-late" to Fingerprint(base, taken + 5_000L),
        )
        assertEquals("nearer-time", FuzzyMatch.findBest(target, candidates))
    }

    @Test
    fun findBestAmbiguousReturnsNull() {
        val target = Fingerprint(base, taken)
        val candidates = mapOf(
            "a" to Fingerprint(flip(2), taken + 500L),
            "b" to Fingerprint(flip(2), taken - 500L),
        )
        assertNull(FuzzyMatch.findBest(target, candidates))
    }

    @Test
    fun findBestNoneMatches() {
        val target = Fingerprint(base, taken)
        assertNull(FuzzyMatch.findBest(target, mapOf(1 to Fingerprint(flip(10), taken))))
        assertNull(FuzzyMatch.findBest(target, emptyMap<String, Fingerprint>()))
    }
}
