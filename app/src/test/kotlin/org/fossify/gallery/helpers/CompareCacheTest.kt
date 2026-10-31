package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompareCacheTest {
    private val m = PhotoMeasure("/DCIM/Camera/a.jpg", 123.5, 0.75, 3, 1, 2, true, 4000, 3000, 1_700_000_000_123L)

    @Test
    fun hitOnlyWhenTimeAndSizeMatch() {
        val c = CompareCache()
        c.put(m, 2_500_000L)
        assertEquals(m, c.get(m.path, m.modified, 2_500_000L))
        assertNull(c.get(m.path, m.modified + 1, 2_500_000L))
        assertNull(c.get(m.path, m.modified, 2_500_001L))
        assertNull(c.get("/inde.jpg", m.modified, 2_500_000L))
    }

    @Test
    fun evictsLeastRecentlyUsed() {
        val c = CompareCache(maxEntries = 2)
        c.put(m.copy(path = "/a.jpg"), 1)
        c.put(m.copy(path = "/b.jpg"), 1)
        c.get("/a.jpg", m.modified, 1) // a je teraz čerstvejšia ako b
        c.put(m.copy(path = "/c.jpg"), 1)
        assertEquals(2, c.size)
        assertNull(c.get("/b.jpg", m.modified, 1))
        assertEquals("/a.jpg", c.get("/a.jpg", m.modified, 1)?.path)
    }

    @Test
    fun dumpAndLoadRoundTrip() {
        val c = CompareCache()
        c.put(m, 2_500_000L)
        c.put(m.copy(path = "/x\ty.jpg"), 1) // tabulátor v ceste sa neuloží
        val lines = c.dump()
        assertEquals(CompareCache.HEADER, lines.first())
        assertEquals(2, lines.size)
        val back = CompareCache()
        back.load(lines)
        assertEquals(m, back.get(m.path, m.modified, 2_500_000L))
    }

    @Test
    fun ignoresOtherVersionsAndBrokenLines() {
        val c = CompareCache()
        c.load(listOf("compare-cache-v0", "/a.jpg\t1\t1\t1\t1\t1\t1\t1\t1\t1\t1"))
        assertEquals(0, c.size)
        c.load(listOf(CompareCache.HEADER, "/a.jpg\tx\t1\t1\t1\t1\t1\t1\t1\t1\t1", "kratky\triadok", ""))
        assertEquals(0, c.size)
    }
}
