package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGuardTest {
    @Test
    fun onlyIncreasingSeqIsAccepted() {
        val guard = ReplayGuard()
        assertFalse(guard.accept(0))
        assertTrue(guard.accept(1))
        assertFalse(guard.accept(1))
        assertTrue(guard.accept(5))
        assertFalse(guard.accept(3))
        assertEquals(5, guard.last())
    }

    @Test
    fun nonceCacheRejectsRepeatAndForgetsOldest() {
        val cache = NonceCache(capacity = 2)
        val a = byteArrayOf(1)
        val b = byteArrayOf(2)
        val c = byteArrayOf(3)
        assertTrue(cache.firstSeen(a))
        assertFalse(cache.firstSeen(byteArrayOf(1)))
        assertTrue(cache.firstSeen(b))
        assertTrue(cache.firstSeen(c))
        // `a` vypadol (kapacita 2), `b` a `c` sú stále zapamätané
        assertFalse(cache.firstSeen(b))
        assertFalse(cache.firstSeen(c))
        assertTrue(cache.firstSeen(a))
    }
}
