package org.fossify.gallery.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerGuardTest {
    private var now = 1_000_000L

    @Test
    fun localAddressesAllowed() {
        listOf(
            "127.0.0.1", "/127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "192.168.1.20", "10.0.0.5", "172.16.3.4",
            "172.31.255.1", "192.168.42.129", "169.254.10.1", "fe80::1%wlan0", "fd12:3456::1", "::ffff:192.168.0.7",
        ).forEach { assertTrue(it, ClientNet.isLocal(it)) }
    }

    @Test
    fun publicAddressesRejected() {
        listOf(
            null, "", "8.8.8.8", "100.64.1.1", "172.32.0.1", "2a00:1450:4001::1", "localhost", "evil.example",
            "11.0.0.1", "192.169.0.1",
        ).forEach { assertFalse(it.toString(), ClientNet.isLocal(it)) }
    }

    private fun guard() = PinGuard { now }

    @Test
    fun correctPinOk() {
        val g = guard()
        assertEquals(PinGuard.Verdict.OK, g.attempt("1.1.1.1", "123456", true).verdict)
    }

    @Test
    fun sameBadTokenCountsOnce() {
        val g = guard()
        repeat(50) { assertEquals(PinGuard.Verdict.BAD, g.attempt("a", "000000", false).verdict) }
        assertEquals(PinGuard.Verdict.OK, g.attempt("a", "123456", true).verdict)
    }

    @Test
    fun ipPausedAfterFiveDistinct() {
        val g = guard()
        repeat(PinGuard.PER_IP) { assertEquals(PinGuard.Verdict.BAD, g.attempt("a", "x$it", false).verdict) }
        val r = g.attempt("a", "123456", true)
        assertEquals(PinGuard.Verdict.PAUSED, r.verdict)
        assertEquals(PinGuard.IP_PAUSE_MS, r.retryMs)
        // iná adresa nie je zasiahnutá
        assertEquals(PinGuard.Verdict.OK, g.attempt("b", "123456", true).verdict)
        now += PinGuard.IP_PAUSE_MS
        assertEquals(PinGuard.Verdict.OK, g.attempt("a", "123456", true).verdict)
    }

    @Test
    fun ipPauseDoubles() {
        val g = guard()
        repeat(PinGuard.PER_IP) { g.attempt("a", "x$it", false) }
        now += PinGuard.IP_PAUSE_MS
        repeat(PinGuard.PER_IP) { g.attempt("a", "y$it", false) }
        assertEquals(2 * PinGuard.IP_PAUSE_MS, g.attempt("a", "z", false).retryMs)
    }

    @Test
    fun globalPauseSparesTrustedAddress() {
        val g = guard()
        assertEquals(PinGuard.Verdict.OK, g.attempt("pc", "123456", true).verdict)
        var event: PinGuard.Event? = null
        // útočník strieda adresy (každá 4 pokusy, pod limitom adresy)
        for (i in 0 until PinGuard.GLOBAL) {
            val r = g.attempt("ip${i / 4}", "g$i", false)
            if (r.event != null) event = r.event
        }
        assertEquals(PinGuard.Event.GLOBAL_PAUSE, event)
        assertEquals(PinGuard.Verdict.PAUSED, g.attempt("new", "123456", true).verdict)
        assertEquals(PinGuard.Verdict.OK, g.attempt("pc", "123456", true).verdict)
        assertFalse(g.hardLocked)
        now += PinGuard.GLOBAL_PAUSE_MS
        assertEquals(PinGuard.Verdict.OK, g.attempt("new", "123456", true).verdict)
    }

    @Test
    fun hardLockAfterLimit() {
        val g = guard()
        var last: PinGuard.Result? = null
        for (i in 0 until PinGuard.HARD_LIMIT) {
            now += PinGuard.IP_PAUSE_MAX_MS + PinGuard.GLOBAL_PAUSE_MS
            last = g.attempt("ip$i", "h$i", false)
        }
        assertEquals(PinGuard.Verdict.LOCKED, last!!.verdict)
        assertEquals(PinGuard.Event.HARD_LOCK, last.event)
        assertTrue(g.hardLocked)
        val after = g.attempt("x", "123456", true)
        assertEquals(PinGuard.Verdict.LOCKED, after.verdict)
        assertNull(after.event)
    }

    @Test
    fun idleClock() {
        val c = IdleClock { now }
        assertEquals(0L, c.idleMs())
        now += 1000
        assertEquals(1000L, c.idleMs())
        c.begin()
        now += IdleClock.IDLE_STOP_MS * 2
        assertEquals(0L, c.idleMs()) // dlhý ZIP beží
        c.end()
        assertFalse(c.shouldStop())
        now += IdleClock.IDLE_STOP_MS
        assertTrue(c.shouldStop())
        c.touch()
        assertFalse(c.shouldStop())
    }

    @Test
    fun idleClockStuckRequest() {
        val c = IdleClock { now }
        c.begin()
        now += IdleClock.STUCK_MS + 1
        assertTrue(c.idleMs() > 0)
    }
}
