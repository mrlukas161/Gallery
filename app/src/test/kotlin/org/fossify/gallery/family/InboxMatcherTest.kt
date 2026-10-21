package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// D5: séria 5 záberov bez EXIF. Susedné snímky sa v pHash líšia o 1 bit, bez dátumu stačí na zhodu ≤ 3 bity,
// takže balík jednej snímky „sedí“ aj na susedné. Mená sa zapíšu naplno len pri jednoznačnej zhode.
class InboxMatcherTest {
    private val base = 0x5A5A_F0F0_1234_5678L

    // snímka i: pHash sa od snímky j líši o |i − j| bitov
    private fun shot(i: Int, noise: Long = 0L) = FuzzyMatch.Fingerprint(base xor ((1L shl i) - 1) xor noise, null)

    private val now = 1_800_000_000_000L

    private fun waiting(id: String, fp: FuzzyMatch.Fingerprint?, key: String = "remote-$id") =
        InboxMatcher.Waiting(id, key, fp, now)

    private fun choose(
        local: FuzzyMatch.Fingerprint?,
        waiting: List<InboxMatcher.Waiting>,
        others: List<FuzzyMatch.Fingerprint> = emptyList(),
        key: String = "local",
    ) = InboxMatcher.choose(key, { local }, now, waiting) { others }

    @Test
    fun exactKeyWinsAlwaysWithFullNames() {
        val c = choose(shot(2), listOf(waiting("b2", shot(2), key = "same")), key = "same")
        assertEquals(InboxMatcher.Choice("b2", BundleMatch.EXACT, NamePolicy.FULL), c)
    }

    @Test
    fun rightShotOfSeriesGetsFullNames() {
        // balík snímky 2, lokálne prišla WhatsApp kópia snímky 2; ostatné snímky série sú tu tiež
        val others = listOf(shot(0), shot(1), shot(3), shot(4))
        val c = choose(shot(2), listOf(waiting("b2", shot(2))), others)
        assertEquals(NamePolicy.FULL, c?.names)
        assertEquals(BundleMatch.FUZZY, c?.match)
    }

    @Test
    fun neighbourShotGetsFacesWithoutNamesWhenBetterPhotoIsHere() {
        // kontroluje sa snímka 1, ale snímka 2 (presná zhoda s balíkom) je v knižnici tiež
        val others = listOf(shot(0), shot(2), shot(3), shot(4))
        val c = choose(shot(1), listOf(waiting("b2", shot(2))), others)
        assertEquals("b2", c?.id)
        assertEquals(NamePolicy.NONE, c?.names)
    }

    @Test
    fun equallyGoodPhotoMeansOnlyAutomaticNames() {
        // WhatsApp kópia snímky 2 s inými 2 bitmi šumu: rovnako ďaleko ako iná nedávna fotka
        val bundle = shot(2)
        val local = shot(2, noise = 1L shl 40 or (1L shl 41))
        val rival = shot(2, noise = 1L shl 50 or (1L shl 51))
        val c = choose(local, listOf(waiting("b2", bundle)), listOf(rival))
        assertEquals(NamePolicy.AUTOMATIC, c?.names)
    }

    @Test
    fun allFiveBundlesWaitingEachShotGetsItsOwn() {
        val bundles = (0 until 5).map { waiting("b$it", shot(it)) }
        for (i in 0 until 5) {
            val others = (0 until 5).filter { it != i }.map { shot(it) }
            val c = choose(shot(i), bundles, others)
            assertEquals("b$i", c?.id)
            assertEquals(NamePolicy.FULL, c?.names)
        }
    }

    @Test
    fun ambiguousBetweenTwoBundlesGivesNothing() {
        // lokálna kópia je presne medzi snímkami 1 a 2 (šum na bite, ktorým sa líšia)
        val local = shot(1, noise = 1L shl 60)
        val bundles = listOf(waiting("b1", shot(1, noise = 1L shl 61)), waiting("b2", shot(1, noise = 1L shl 62)))
        assertNull(choose(local, bundles))
    }

    @Test
    fun otherWaitingBundleMatchingBetterBlocksNames() {
        // LocalPhotoFinder: balík b1 hľadá fotku, najlepšia lokálna je snímka 2, ale čaká aj balík snímky 2
        val policy = SeriesGuard.policy(shot(1), shot(2), otherLocals = emptyList(), otherBundles = listOf(shot(2)))
        assertEquals(NamePolicy.NONE, policy)
    }

    @Test
    fun withoutBundleInWindowNothingIsComputed() {
        var computed = false
        val far = InboxMatcher.Waiting("b", "k", shot(1), now - BundleRules.FUZZY_WINDOW_MS - 1)
        val c = InboxMatcher.choose("x", { computed = true; shot(1) }, now, listOf(far)) { emptyList() }
        assertNull(c)
        assertEquals(false, computed)
    }
}
