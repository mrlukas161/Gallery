package org.fossify.gallery.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareSessionTest {
    private fun session(n: Int = 5) = CompareSession((1..n).map { "/p$it.jpg" })

    @Test
    fun startsWithFirstTwoAndDropsDuplicates() {
        val s = CompareSession(listOf("/a.jpg", "/a.jpg", "/b.jpg"))
        assertEquals(listOf("/a.jpg", "/b.jpg"), s.paths)
        assertEquals("/a.jpg", s.a)
        assertEquals("/b.jpg", s.b)
    }

    @Test
    fun tapMovesPreviousAToB() {
        val s = session()
        s.tap("/p4.jpg")
        assertEquals("/p4.jpg", s.a)
        assertEquals("/p1.jpg", s.b)
        assertTrue(s.userMoved)
        s.tap("/p1.jpg") // ťuk na B ich vymení
        assertEquals("/p1.jpg", s.a)
        assertEquals("/p4.jpg", s.b)
    }

    @Test
    fun stepSkipsPhotoShownInOtherPane() {
        val s = session()
        assertTrue(s.step(Pane.A, 1)) // p1 → p3 (p2 je v B)
        assertEquals("/p3.jpg", s.a)
        assertTrue(s.step(Pane.B, -1)) // p2 → p1 (p1 už nie je v A)
        assertEquals("/p1.jpg", s.b)
        assertTrue(s.step(Pane.B, 1)) // p1 → p2
        assertTrue(s.step(Pane.B, 1)) // p2 → p4 (p3 je v A)
        assertEquals("/p4.jpg", s.b)
    }

    @Test
    fun stepStopsAtEnds() {
        val s = session(2)
        assertFalse(s.step(Pane.A, -1))
        assertFalse(s.step(Pane.A, 1)) // jediná ďalšia je v B
        assertEquals("/p1.jpg", s.a)
    }

    @Test
    fun keepOnlyMarksAllOthersAndUnmarksKept() {
        val s = session(4)
        s.toggleMark("/p2.jpg")
        s.keepOnly("/p2.jpg")
        assertEquals(setOf("/p1.jpg", "/p3.jpg", "/p4.jpg"), s.marked)
    }

    @Test
    fun placeBestOnlyBeforeUserMoved() {
        val s = session()
        assertTrue(s.placeBest("/p4.jpg", "/p2.jpg"))
        assertEquals("/p4.jpg", s.a)
        assertEquals("/p2.jpg", s.b)
        s.tap("/p5.jpg")
        assertFalse(s.placeBest("/p3.jpg", "/p1.jpg"))
        assertEquals("/p5.jpg", s.a)
        assertEquals("/p3.jpg", s.best)
    }

    @Test
    fun removeKeepsMarksOnSamePhotosAndRefillsPanes() {
        val s = session()
        s.toggleMark("/p5.jpg")
        s.tap("/p3.jpg") // A = p3, B = p1
        assertTrue(s.remove(listOf("/p3.jpg", "/p1.jpg")))
        assertEquals(listOf("/p2.jpg", "/p4.jpg", "/p5.jpg"), s.paths)
        assertEquals(setOf("/p5.jpg"), s.marked)
        assertTrue(s.a in s.paths && s.b in s.paths && s.a != s.b)
    }

    @Test
    fun removeBelowTwoEndsComparison() {
        val s = session(3)
        assertFalse(s.remove(listOf("/p1.jpg", "/p2.jpg")))
    }

    @Test
    fun restorePutsPhotosBackToOriginalPlaces() {
        val s = session()
        s.remove(listOf("/p2.jpg", "/p4.jpg"))
        s.restore(listOf("/p4.jpg" to 3, "/p2.jpg" to 1))
        assertEquals((1..5).map { "/p$it.jpg" }, s.paths)
        assertFalse(s.marked.contains("/p2.jpg"))
    }
}
