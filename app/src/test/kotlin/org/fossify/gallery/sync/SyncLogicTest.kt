package org.fossify.gallery.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogicTest {
    // ---------- /api/state?since ----------

    @Test
    fun zmenyOdGeneracie() {
        val log = StateLog()
        log.record(11, listOf("/a"))
        log.record(12, listOf("/b", "/c"))
        log.record(13, emptyList()) // stav skupín, bez ciest
        assertEquals(setOf("/a", "/b", "/c"), log.since(10, 13))
        assertEquals(setOf("/b", "/c"), log.since(11, 13))
        assertEquals(emptySet<String>(), log.since(12, 13))
        assertEquals(emptySet<String>(), log.since(13, 13))
    }

    @Test
    fun kedHistoriaNestaciJeCelyStav() {
        val log = StateLog(capacity = 2)
        log.record(1, listOf("/a"))
        log.record(2, listOf("/b"))
        log.record(3, listOf("/c"))
        // generácia 1 už vypadla
        assertNull(log.since(0, 3))
        // budúca generácia (iný beh servera) a záporná
        assertNull(log.since(5, 3))
        assertNull(log.since(-1, 3))
    }

    @Test
    fun zrusenieVsetkychZnaciekVyzadujeCelyStav() {
        val log = StateLog()
        log.record(1, listOf("/a"))
        log.record(2, null)
        log.record(3, listOf("/b"))
        assertNull(log.since(0, 3))
        assertEquals(setOf("/b"), log.since(2, 3))
    }

    @Test
    fun medzeraVGeneraciachPoNovomBehu() {
        val log = StateLog()
        log.record(5, listOf("/a"))
        // nový beh servera posunul stateGen o 100 bez záznamu
        log.record(106, listOf("/b"))
        assertNull(log.since(4, 106))
        assertNull(log.since(5, 106))
        assertEquals(setOf("/b"), log.since(105, 106))
        log.clear()
        assertNull(log.since(105, 106))
    }

    // ---------- dávka „✓ v PC“ ----------

    @Test
    fun davkaSpojiOpakovaneCestyANechaNajnovsiCas() {
        val b = SyncedBatch()
        b.add(listOf("/a", "/b"), 100)
        b.add(listOf("/a", ""), 200)
        assertEquals(2, b.size())
        val d = b.drain()
        assertEquals(mapOf("/a" to 200L, "/b" to 100L), d)
        assertEquals(0, b.size())
        assertTrue(b.drain().isEmpty())
    }

    @Test
    fun jsonTamASpat() {
        val m = mapOf("/sdcard/DCIM/Camera/IMG \"1\".jpg" to 1_730_000_000_000L, "/b" to 5L)
        assertEquals(m, SyncedJson.decode(SyncedJson.encode(m)))
        assertTrue(SyncedJson.decode(null).isEmpty())
        assertTrue(SyncedJson.decode("nie je json").isEmpty())
    }
}
