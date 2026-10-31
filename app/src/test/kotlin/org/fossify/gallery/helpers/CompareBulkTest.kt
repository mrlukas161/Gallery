package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// Hromadné „Ponechať najlepšiu“: neisté skupiny, prah, presné kópie a invariant „žiadna skupina nepríde
// o všetky fotky, ★ vždy ostane“.
class CompareBulkTest {
    private fun s(score: Double, rank: Int, faces: Boolean = true) =
        PhotoScore(1.0, 1.0, 0, 0, 0, score, emptyList(), rank, faces)

    private fun m(path: String, w: Int = 4000, h: Int = 3000, modified: Long = 1_000L) =
        PhotoMeasure(path, 0.0, 0.0, width = w, height = h, modified = modified)

    // --- verdikt a neisté skupiny ---

    @Test
    fun clearWinnerIsCertain() {
        val v = CompareBulk.verdict(mapOf("/a" to s(0.9, 0), "/b" to s(0.7, 1), "/c" to s(0.5, 2)))
        assertEquals("/a", v.best)
        assertEquals("/b", v.second)
        assertEquals(0.2, v.confidence, 1e-9)
        assertFalse(v.uncertain)
        assertTrue(v.facesChecked)
    }

    @Test
    fun smallMarginIsUncertain() {
        val v = CompareBulk.verdict(mapOf("/a" to s(0.80, 0), "/b" to s(0.77, 1)))
        assertTrue(v.uncertain)
        // presne na prahu ešte istá
        assertFalse(CompareBulk.verdict(mapOf("/a" to s(0.80, 0), "/b" to s(0.75, 1))).uncertain)
    }

    @Test
    fun tieBrokenByResolutionCountsAsUncertain() {
        // ★ vyhrala len rozhodnutím pri zhode (vyššie rozlíšenie) a má nižšie skóre než druhá
        val v = CompareBulk.verdict(mapOf("/a" to s(0.78, 0), "/b" to s(0.80, 1)))
        assertEquals("/a", v.best)
        assertEquals(-0.02, v.confidence, 1e-9)
        assertTrue(v.uncertain)
    }

    @Test
    fun secondIsHighestScoreAmongOthers() {
        // poradie 1 a 2 sa môže pri zhode prehodiť — náskok sa ráta voči najvyššiemu skóre ostatných
        val v = CompareBulk.verdict(mapOf("/a" to s(0.9, 0), "/b" to s(0.6, 1), "/c" to s(0.63, 2)))
        assertEquals("/c", v.second)
        assertEquals(0.27, v.confidence, 1e-9)
    }

    @Test
    fun withoutFacesNeedsBiggerMargin() {
        val scores = mapOf("/a" to s(0.9, 0, faces = false), "/b" to s(0.8, 1, faces = false))
        val v = CompareBulk.verdict(scores)
        assertFalse(v.facesChecked)
        assertTrue(v.uncertain)
        val clear = mapOf("/a" to s(0.95, 0, faces = false), "/b" to s(0.7, 1, faces = false))
        assertFalse(CompareBulk.verdict(clear).uncertain)
    }

    @Test
    fun singleScoredPhotoIsUncertain() {
        val v = CompareBulk.verdict(mapOf("/a" to s(0.9, 0)))
        assertEquals("/a", v.best)
        assertNull(v.second)
        assertTrue(v.uncertain)
        assertTrue(CompareBulk.verdict(emptyMap()).uncertain)
    }

    @Test
    fun uncertainGroupIsSkipped() {
        val d = CompareBulk.qualityDecision(listOf("/a", "/b"), mapOf("/a" to s(0.8, 0), "/b" to s(0.79, 1)))
        assertNull(d.keep)
        assertTrue(d.removable.isEmpty())
        assertEquals(CompareBulk.Skip.UNCERTAIN, d.skip)
        val plan = CompareBulk.plan(listOf(d))
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.uncertain)
        assertEquals(0, plan.affected)
    }

    @Test
    fun unscoredPhotoIsNeverDeleted() {
        // /c sa nedala prečítať (nemá skóre) — nesmie ísť do Koša, aj keď ★ je jasná
        val d = CompareBulk.qualityDecision(
            listOf("/a", "/b", "/c"),
            mapOf("/a" to s(0.9, 0), "/b" to s(0.5, 1)),
        )
        assertEquals("/a", d.keep)
        assertEquals(listOf("/b"), d.removable)
    }

    // --- presné kópie ---

    @Test
    fun exactCopiesKeepHigherResolution() {
        val keep = CompareBulk.exactKeeper(
            listOf(m("/DCIM/Camera/a.jpg", 1600, 1200), m("/Pictures/WhatsApp/a.jpg", 4000, 3000)),
        )
        assertEquals("/Pictures/WhatsApp/a.jpg", keep)
    }

    @Test
    fun exactCopiesSameResolutionPreferCameraFolder() {
        val keep = CompareBulk.exactKeeper(
            listOf(
                m("/Download/a.jpg", modified = 10L),
                m("/Pictures/Other/a.jpg", modified = 20L),
                m("/DCIM/Camera/a.jpg", modified = 30L),
            ),
        )
        assertEquals("/DCIM/Camera/a.jpg", keep)
    }

    @Test
    fun exactCopiesSameFolderKindKeepOlder() {
        val keep = CompareBulk.exactKeeper(
            listOf(m("/Pictures/B/a.jpg", modified = 2_000L), m("/Pictures/A/a.jpg", modified = 1_000L)),
        )
        assertEquals("/Pictures/A/a.jpg", keep)
        // neznámy čas (0) nie je „starší“
        val unknown = CompareBulk.exactKeeper(
            listOf(m("/Pictures/B/a.jpg", modified = 0L), m("/Pictures/A/a.jpg", modified = 5_000L)),
        )
        assertEquals("/Pictures/A/a.jpg", unknown)
    }

    @Test
    fun exactDecisionNeedsTwoKnownPhotos() {
        val d = CompareBulk.exactDecision(listOf("/a", "/b"), listOf(m("/a")))
        assertNull(d.keep)
        assertEquals(CompareBulk.Skip.NOT_MEASURED, d.skip)
        val ok = CompareBulk.exactDecision(
            listOf("/a", "/b", "/c"),
            listOf(m("/a", modified = 1L), m("/b", modified = 2L)),
        )
        assertEquals("/a", ok.keep)
        // /c nepoznáme — nemaže sa
        assertEquals(listOf("/b"), ok.removable)
    }

    // --- plán a invariant ---

    @Test
    fun planDeletesAllButStarInEachGroup() {
        val d1 = CompareBulk.Decision(listOf("/a", "/b", "/c"), "/b", listOf("/a", "/c"))
        val d2 = CompareBulk.Decision(listOf("/x", "/y"), "/x", listOf("/y"))
        val plan = CompareBulk.plan(listOf(d1, d2))
        assertEquals(listOf("/a", "/c", "/y"), plan.delete)
        assertEquals(2, plan.affected)
        assertEquals(setOf("/b", "/x"), plan.keeps)
    }

    @Test
    fun keepIsNeverRemovableEvenIfListed() {
        val d = CompareBulk.Decision(listOf("/a", "/b"), "/a", listOf("/a", "/b", "/zzz"))
        assertEquals(listOf("/b"), d.removable)
        // keep mimo skupiny = skupina sa preskočí
        val bad = CompareBulk.Decision(listOf("/a", "/b"), "/q", listOf("/a", "/b"))
        assertNull(bad.keep)
        assertTrue(bad.removable.isEmpty())
    }

    @Test
    fun starOfOneGroupSurvivesWhenAnotherGroupWantsItGone() {
        // séria {a, b} má ★ b; presné kópie {b, c} majú ★ c a chceli by zmazať b
        val burst = CompareBulk.Decision(listOf("/a", "/b"), "/b", listOf("/a"))
        val exact = CompareBulk.Decision(listOf("/b", "/c"), "/c", listOf("/b"))
        val plan = CompareBulk.plan(listOf(burst, exact))
        assertEquals(listOf("/a"), plan.delete)
        assertFalse("/b" in plan.delete)
    }

    @Test
    fun otherGroupNeverLosesAllPhotos() {
        // nevybraná skupina {a, c}: a ide preč zo skupiny 1, c zo skupiny 2 -> jedna z nich musí ostať
        val g1 = CompareBulk.Decision(listOf("/a", "/b"), "/b", listOf("/a"))
        val g2 = CompareBulk.Decision(listOf("/c", "/d"), "/d", listOf("/c"))
        val plan = CompareBulk.plan(listOf(g1, g2), otherGroups = listOf(listOf("/c", "/a")))
        assertEquals(listOf("/a"), plan.delete)
        assertEquals(setOf("/c"), plan.spared)
        assertEquals(1, plan.affected)
    }

    @Test
    fun keeperFirstPutsTileFirst() {
        assertEquals(listOf("/b", "/a", "/c"), CompareBulk.keeperFirst(listOf("/a", "/b", "/c"), "/b"))
        assertEquals(listOf("/a", "/c"), CompareBulk.keeperFirst(listOf("/a", "/c"), "/x"))
    }

    @Test
    fun fitRunCountsSharedPhotosOnce() {
        val groups = listOf(listOf("/a", "/b"), listOf("/b", "/c"), listOf("/d", "/e"))
        assertEquals(2, CompareBulk.fitRun(groups, maxPhotos = 3))
        assertEquals(3, CompareBulk.fitRun(groups, maxPhotos = 5))
        assertEquals(0, CompareBulk.fitRun(listOf(listOf("/a", "/b", "/c")), maxPhotos = 2))
    }

    // Náhodné prekrývajúce sa skupiny a rozhodnutia: žiadna skupina nesmie prísť o všetky fotky a ★ každej
    // rozhodnutej skupiny ostane.
    @Test
    fun invariantNoGroupLosesAllPhotos() {
        val rnd = Random(20261031)
        repeat(RANDOM_ROUNDS) {
            val photos = (0 until rnd.nextInt(2, 30)).map { "/p$it" }
            val groups = (0 until rnd.nextInt(1, 12)).map {
                photos.shuffled(rnd).take(rnd.nextInt(2, minOf(6, photos.size) + 1))
            }
            val decisions = groups.map { g ->
                when (rnd.nextInt(4)) {
                    0 -> CompareBulk.Decision(g, null, emptyList(), CompareBulk.Skip.UNCERTAIN)
                    1 -> CompareBulk.Decision(g, g.random(rnd), g.shuffled(rnd).take(rnd.nextInt(g.size + 1)))
                    else -> CompareBulk.Decision(g, g.random(rnd), g)
                }
            }
            val others = (0 until rnd.nextInt(0, 6)).map {
                photos.shuffled(rnd).take(rnd.nextInt(2, minOf(5, photos.size) + 1))
            }
            val plan = CompareBulk.plan(decisions, others)
            val deleted = plan.delete.toSet()
            assertEquals("bez duplicít", plan.delete.size, deleted.size)
            (groups + others).forEach { g -> assertTrue("skupina $g prišla o všetko", g.any { it !in deleted }) }
            decisions.forEach { d -> d.keep?.let { assertFalse("★ $it zmazaná", it in deleted) } }
            // zmazať sa smie len to, čo niektorá rozhodnutá skupina navrhla
            val proposed = decisions.flatMap { it.removable }.toSet()
            assertTrue(deleted.all { it in proposed })
        }
    }

    private companion object {
        const val RANDOM_ROUNDS = 2000
    }
}
