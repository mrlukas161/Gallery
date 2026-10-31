package org.fossify.gallery.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TournamentTest {
    private fun setup(n: Int = 4): Pair<CompareSession, Tournament> {
        val s = CompareSession((1..n).map { "/p$it.jpg" })
        return s to Tournament(s)
    }

    @Test
    fun winnerStaysAndLosersAreMarked() {
        val (s, t) = setup()
        assertTrue(t.start())
        assertEquals(3, t.total)
        assertTrue(t.pick(Pane.A)) // p1 > p2
        assertEquals("/p1.jpg", s.a)
        assertEquals("/p3.jpg", s.b)
        assertTrue(t.pick(Pane.B)) // p3 > p1
        assertEquals("/p3.jpg", s.a)
        assertEquals("/p4.jpg", s.b)
        assertFalse(t.pick(Pane.A)) // posledný zápas
        assertFalse(t.active)
        assertEquals(setOf("/p1.jpg", "/p2.jpg", "/p4.jpg"), s.marked)
        assertEquals(3, t.played)
    }

    @Test
    fun startSkipsAlreadyMarkedPhotos() {
        val (s, t) = setup(5)
        s.toggleMark("/p1.jpg")
        s.toggleMark("/p3.jpg")
        assertTrue(t.start())
        assertEquals("/p2.jpg", s.a)
        assertEquals("/p4.jpg", s.b)
        assertEquals(listOf("/p5.jpg"), t.waiting)
        assertEquals(2, t.total)
    }

    @Test
    fun needsTwoUnmarkedPhotos() {
        val (s, t) = setup(3)
        s.keepOnly("/p2.jpg")
        assertFalse(t.start())
        assertFalse(t.active)
    }

    @Test
    fun startsWithBestAsChampion() {
        val (s, t) = setup()
        s.best = "/p3.jpg"
        assertTrue(t.start())
        assertEquals("/p3.jpg", s.a)
        assertEquals("/p1.jpg", s.b)
    }

    @Test
    fun markedWinnerIsUnmarked() {
        val (s, t) = setup()
        t.start()
        s.toggleMark("/p2.jpg") // používateľ si medzitým označil vyzývateľa
        t.pick(Pane.B)
        assertFalse(s.marked.contains("/p2.jpg"))
        assertTrue(s.marked.contains("/p1.jpg"))
    }

    @Test
    fun photosMarkedDuringTournamentAreSkipped() {
        val (s, t) = setup(5)
        t.start()
        s.toggleMark("/p3.jpg")
        t.pick(Pane.A)
        assertEquals("/p4.jpg", s.b)
    }

    @Test
    fun deletingDuringTournamentKeepsOrder() {
        val (s, t) = setup(6)
        t.start()
        t.pick(Pane.A) // p2 označená, A = p1, B = p3
        assertTrue(t.remove(listOf("/p2.jpg", "/p4.jpg")))
        assertTrue(t.active)
        assertEquals("/p1.jpg", s.a)
        assertEquals("/p3.jpg", s.b)
        t.pick(Pane.A)
        assertEquals("/p5.jpg", s.b) // p4 je preč, poradie sa neposunulo o jednu
    }

    @Test
    fun deletingChampionPromotesChallenger() {
        val (s, t) = setup(4)
        t.start()
        assertTrue(t.remove(listOf("/p1.jpg")))
        assertEquals("/p2.jpg", s.a)
        assertEquals("/p3.jpg", s.b)
        assertTrue(t.active)
    }

    @Test
    fun deletingTooManyStopsTournament() {
        val (_, t) = setup(3)
        t.start()
        assertFalse(t.remove(listOf("/p1.jpg", "/p2.jpg")))
        assertFalse(t.active)
    }

    @Test
    fun restoreAfterProcessDeath() {
        val (s, t) = setup(5)
        t.start()
        t.pick(Pane.A)
        val waiting = t.waiting
        val played = t.played
        val s2 = CompareSession(s.paths).apply {
            a = s.a
            b = s.b
            marked.addAll(s.marked)
        }
        val t2 = Tournament(s2)
        t2.restore(waiting, played)
        assertTrue(t2.active)
        assertEquals(t.total, t2.total)
        t2.pick(Pane.A)
        assertEquals("/p4.jpg", s2.b)
    }
}
