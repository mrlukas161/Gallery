package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexProgressTest {
    private var now = 0L
    private fun progress() = IndexProgress("Tváre") { now }

    @Test
    fun percentAndNoEtaAtStart() {
        val p = progress()
        p.update(0, 200)
        assertEquals(0, p.percent)
        assertNull(p.remainingMs())
    }

    @Test
    fun etaFromSpeed() {
        val p = progress()
        p.update(0, 200)
        now = 60_000
        p.update(50, 200)
        assertEquals(25, p.percent)
        // 50 fotiek za minútu, zostáva 150 → 3 minúty
        assertEquals(180_000L, p.remainingMs())
    }

    @Test
    fun noEtaWithTooLittleData() {
        val p = progress()
        p.update(0, 200)
        now = 5_000
        p.update(3, 200)
        assertNull(p.remainingMs())
    }

    @Test
    fun restartResetsSpeed() {
        val p = progress()
        p.update(100, 200)
        now = 60_000
        p.update(10, 50)
        now = 80_000
        p.update(20, 50)
        // 10 fotiek za 20 s, zostáva 30 → 60 s
        assertEquals(60_000L, p.remainingMs())
    }

    @Test
    fun formatEta() {
        val f = { ms: Long -> IndexProgress.formatEta(ms, "<1 min", { "~$it min" }, { h, m -> "~$h h $m min" }) }
        assertEquals("<1 min", f(30_000))
        assertEquals("~3 min", f(150_000))
        assertEquals("~2 h 10 min", f(130 * 60_000L))
    }
}
