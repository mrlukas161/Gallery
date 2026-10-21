package org.fossify.gallery.faces

import org.fossify.gallery.family.RelBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelinkPlannerTest {

    private fun missing(path: String, key: String, size: Long = 1000L) = RelinkPlanner.Missing(path, size, key)
    private fun cand(path: String, key: String, size: Long = 1000L, id: Long = 7L) =
        RelinkPlanner.Candidate(path, size, key, id)
    private fun slot(id: Long, index: Int) = RelinkPlanner.FaceSlot(id, index)

    @Test
    fun uniqueMatchIsPaired() {
        val m = missing("/DCIM/a.jpg", "k1")
        val c = cand("/Pictures/a.jpg", "k1")
        val plan = RelinkPlanner.plan(listOf(m, missing("/DCIM/b.jpg", "k2")), listOf(c, cand("/x/other.jpg", "k3")))
        assertEquals(listOf(RelinkPlanner.Match(m, c)), plan)
    }

    @Test
    fun twoCandidatesAreAmbiguous() {
        // kópia v dvoch priečinkoch — nevieme, ktorá je „tá" fotka
        val plan = RelinkPlanner.plan(
            listOf(missing("/DCIM/a.jpg", "k1")),
            listOf(cand("/Pictures/a.jpg", "k1"), cand("/Download/a (1).jpg", "k1")),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun twoMissingWithSameContentAreAmbiguous() {
        val plan = RelinkPlanner.plan(
            listOf(missing("/DCIM/a.jpg", "k1"), missing("/DCIM/copy.jpg", "k1")),
            listOf(cand("/Pictures/a.jpg", "k1")),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun sizeMustMatchToo() {
        val plan = RelinkPlanner.plan(
            listOf(missing("/a.jpg", "k1", size = 1000L)),
            listOf(cand("/b.jpg", "k1", size = 999L)),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun noKeyMatchMeansNothing() {
        assertTrue(RelinkPlanner.plan(listOf(missing("/a.jpg", "k1")), listOf(cand("/b.jpg", "k2"))).isEmpty())
        assertTrue(RelinkPlanner.plan(emptyList(), listOf(cand("/b.jpg", "k2"))).isEmpty())
    }

    @Test
    fun duplicateCandidateRowsCountOnce() {
        // tá istá cesta z MediaStore aj ako skrytá dvojička nesmie spraviť zhodu nejednoznačnou
        val m = missing("/a.jpg", "k1")
        val plan = RelinkPlanner.plan(listOf(m), listOf(cand("/b.jpg", "k1"), cand("/b.jpg", "k1", id = -1L)))
        assertEquals(1, plan.size)
        assertEquals("/b.jpg", plan.first().candidate.path)
    }

    @Test
    fun missingPathIsNeverItsOwnCandidate() {
        assertTrue(RelinkPlanner.plan(listOf(missing("/a.jpg", "k1")), listOf(cand("/a.jpg", "k1"))).isEmpty())
    }

    @Test
    fun emptyNewPathMoves() {
        val action = RelinkPlanner.decide(listOf(slot(1, 0), slot(2, 1)), emptyList(), emptySet())
        assertEquals(RelinkPlanner.Action.Move(emptyList()), action)
    }

    @Test
    fun unreferencedDuplicatesAreDeletedThenMoved() {
        val action = RelinkPlanner.decide(listOf(slot(1, 0)), listOf(slot(10, 0), slot(11, 1)), setOf(1L, 99L))
        assertEquals(RelinkPlanner.Action.Move(listOf(10L, 11L)), action)
    }

    @Test
    fun referencedDuplicateBlocksUpdate() {
        // tvár 11 na novej ceste je už pomenovaná — UPDATE by narazil na (media_full_path, face_index)
        val action = RelinkPlanner.decide(listOf(slot(1, 0), slot(2, 1)), listOf(slot(10, 0), slot(11, 1)), setOf(11L))
        assertEquals(RelinkPlanner.Action.Transfer, action)
    }

    @Test
    fun unreadableReferencesNeverDelete() {
        assertEquals(RelinkPlanner.Action.Transfer, RelinkPlanner.decide(listOf(slot(1, 0)), listOf(slot(10, 0)), null))
        // bez tvárí na novej ceste sa nič nemaže, takže sa smie preklopiť aj bez odkazov
        val action = RelinkPlanner.decide(listOf(slot(1, 0)), emptyList(), null)
        assertEquals(RelinkPlanner.Action.Move(emptyList()), action)
    }

    @Test
    fun uniqueIndexCollision() {
        assertTrue(RelinkPlanner.collides(listOf(slot(1, 0), slot(2, 1)), listOf(slot(10, 1))))
        assertFalse(RelinkPlanner.collides(listOf(slot(1, 0), slot(2, 1)), listOf(slot(10, 2))))
        assertFalse(RelinkPlanner.collides(listOf(slot(1, 0)), emptyList()))
    }

    @Test
    fun transferPairsByOverlap() {
        val left = RelBox(0.1f, 0.1f, 0.3f, 0.4f)
        val right = RelBox(0.6f, 0.1f, 0.8f, 0.4f)
        val old = listOf(1L to listOf(left), 2L to listOf(right))
        val new = listOf(
            20L to listOf(RelBox(0.61f, 0.11f, 0.8f, 0.4f)),
            10L to listOf(RelBox(0.1f, 0.12f, 0.3f, 0.41f)),
        )
        assertEquals(mapOf(1L to 10L, 2L to 20L), RelinkPlanner.transferPairs(old, new))
    }

    @Test
    fun transferUsesSecondHypothesis() {
        // stará tvár s dvoma hypotézami (upright / surový priestor) — zhoda s ktoroukoľvek stačí
        val old = listOf(1L to listOf(RelBox(0.0f, 0.0f, 0.1f, 0.1f), RelBox(0.5f, 0.5f, 0.7f, 0.7f)))
        val new = listOf(10L to listOf(RelBox(0.5f, 0.5f, 0.7f, 0.7f)))
        assertEquals(mapOf(1L to 10L), RelinkPlanner.transferPairs(old, new))
    }

    @Test
    fun transferSkipsAmbiguousAndShared() {
        val box = RelBox(0.1f, 0.1f, 0.3f, 0.4f)
        // dve staré tváre na tom istom mieste → nová tvár by dostala dve menovky → nič
        val shared = RelinkPlanner.transferPairs(
            listOf(1L to listOf(box), 2L to listOf(box)),
            listOf(10L to listOf(box)),
        )
        assertTrue(shared.isEmpty())
        // bez prekryvu nič
        val far = RelBox(0.7f, 0.7f, 0.9f, 0.9f)
        assertTrue(RelinkPlanner.transferPairs(listOf(1L to listOf(box)), listOf(10L to listOf(far))).isEmpty())
    }
}
