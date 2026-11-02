package org.fossify.gallery.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ServerPerfTest {
    // ---------- percentily ----------

    @Test
    fun percentilNearestRank() {
        val v = longArrayOf(50, 10, 40, 20, 30)
        assertEquals(30L, LatencyStats.percentile(v, 50))
        assertEquals(50L, LatencyStats.percentile(v, 95))
        assertEquals(10L, LatencyStats.percentile(longArrayOf(10), 95))
        assertEquals(0L, LatencyStats.percentile(LongArray(0), 50))
        val hundred = LongArray(100) { (it + 1).toLong() }
        assertEquals(50L, LatencyStats.percentile(hundred, 50))
        assertEquals(95L, LatencyStats.percentile(hundred, 95))
    }

    @Test
    fun kruhovyBufferDrziLenPosledne() {
        val s = LatencyStats(capacity = 4)
        // staré pomalé požiadavky vypadnú z okna, počet a maximum ostávajú
        repeat(4) { s.record("/api/thumb", 1000, 1000) }
        repeat(4) { s.record("/api/thumb", 10, 20) }
        val t = s.snapshot().getValue("/api/thumb")
        assertEquals(8L, t.count)
        assertEquals(10L, t.p95)
        assertEquals(20L, t.p95Total)
        assertEquals(1000L, t.max)
    }

    @Test
    fun neznameCestyNenafuknuMapu() {
        val s = LatencyStats(capacity = 4, maxKeys = 2)
        s.record("/api/ping", 1, 1)
        s.record("/api/list", 1, 1)
        s.record("/x1", 1, 1)
        s.record("/x2", 1, 1)
        val keys = s.snapshot().keys
        assertEquals(setOf("/api/ping", "/api/list", LatencyStats.OTHER), keys)
        assertEquals(2L, s.snapshot().getValue(LatencyStats.OTHER).count)
    }

    // ---------- pomalé požiadavky ----------

    private fun entry(ms: Long, total: Long, bytes: Long) =
        SlowLog.Entry(0L, "/api/thumb?id=1", 200, ms, total, bytes, ClientKind.LAN)

    @Test
    fun pomalaObsluhaAleboPomalyPrenosMalejOdpovede() {
        val log = SlowLog(capacity = 20, thresholdMs = 500)
        assertTrue(log.offer(entry(600, 650, 10_000)))
        // malá odpoveď, ktorej odoslanie trvalo dlho -> sieť
        assertTrue(log.offer(entry(5, 900, 2_000)))
        // veľký originál sa pre dlhý prenos neráta
        assertFalse(log.offer(entry(5, 9_000, 50_000_000)))
        assertFalse(log.offer(entry(100, 120, 10)))
        assertEquals(2, log.list().size)
    }

    @Test
    fun drziPoslednych20NajnovsiePrve() {
        val log = SlowLog()
        for (i in 1..25) log.offer(SlowLog.Entry(i.toLong(), "/p", 200, 1000, 1000, 1, ClientKind.USB))
        val l = log.list()
        assertEquals(20, l.size)
        assertEquals(25L, l.first().at)
        assertEquals(6L, l.last().at)
    }

    // ---------- Server-Timing ----------

    @Test
    fun serverTimingHlavicka() {
        val t = RequestTiming(0L)
        t.add("q", 5.0)
        t.add("dec", 80.25)
        t.add("q", 1.0)
        t.cache("miss")
        assertEquals("app;dur=12.5, q;dur=6.0, dec;dur=80.3, cache;desc=miss", t.header(12_500_000L))
        assertEquals("app;dur=0.0", RequestTiming(0L).header(0L))
    }

    @Test
    fun meranieMimoPoziadavkyLenVykona() {
        RequestTiming.end()
        assertEquals(42, RequestTiming.measure("dec") { 42 })
        val t = RequestTiming.begin(System.nanoTime())
        RequestTiming.measure("dec") { Thread.sleep(2) }
        assertTrue(t.header(System.nanoTime()).contains("dec;dur="))
        RequestTiming.end()
    }

    // ---------- koniec požiadavky ----------

    @Test
    fun koniecPoziadavkyRazSPoctomBajtov() {
        var calls = 0
        var sent = -1L
        val s = FinishStream(ByteArrayInputStream(ByteArray(100))) {
            calls++
            sent = it
        }
        val buf = ByteArray(64)
        while (s.read(buf, 0, buf.size) > 0) Unit
        s.close()
        s.close() // NanoHTTPD zatvára telo dvakrát
        assertEquals(1, calls)
        assertEquals(100L, sent)
    }

    // ---------- debounce obnovy indexu ----------

    @Test
    fun debounceTichoAleNajneskorPoMaxime() {
        val d = Debounce(quietMs = 2500, maxMs = 10_000)
        assertEquals(2500L, d.onEvent(0))
        assertEquals(2500L, d.onEvent(1000))
        // séria zmien stále pokračuje: najneskôr 10 s po prvej
        assertEquals(1000L, d.onEvent(9000))
        assertEquals(0L, d.onEvent(12_000))
        d.onRun()
        assertEquals(2500L, d.onEvent(20_000))
    }

    // ---------- WakeLock / WifiLock ----------

    @Test
    fun zamkyLenPocasPoziadaviekA90sPotom() {
        val h = LockHold(holdMs = 90_000, rearmMs = 20_000)
        assertEquals(LockHold.Action.ACQUIRE, h.onRequest(0))
        assertEquals(LockHold.Action.NONE, h.onRequest(5_000))
        assertEquals(LockHold.Action.REARM, h.onRequest(25_000))
        assertEquals(LockHold.Action.NONE, h.onCheck(100_000, inFlight = 0))
        assertEquals(LockHold.Action.RELEASE, h.onCheck(115_000, inFlight = 0))
        assertFalse(h.held)
        assertEquals(LockHold.Action.NONE, h.onCheck(200_000, inFlight = 0))
        assertEquals(LockHold.Action.ACQUIRE, h.onRequest(300_000))
    }

    @Test
    fun dlhyPrenosZamkyPredlzuje() {
        val h = LockHold(holdMs = 90_000, rearmMs = 20_000)
        h.onRequest(0)
        // 4 GB upload: jedna požiadavka beží minúty
        assertEquals(LockHold.Action.REARM, h.onCheck(95_000, inFlight = 1))
        assertEquals(LockHold.Action.NONE, h.onCheck(100_000, inFlight = 1))
        assertEquals(LockHold.Action.NONE, h.onCheck(150_000, inFlight = 0))
        assertEquals(LockHold.Action.RELEASE, h.onCheck(190_000, inFlight = 0))
    }

    @Test
    fun dalsiaKontrolaVHraniciach() {
        val h = LockHold(holdMs = 90_000, rearmMs = 20_000)
        h.onRequest(0)
        assertEquals(90_000L, h.nextCheckIn(0, 5_000))
        assertEquals(5_000L, h.nextCheckIn(89_000, 5_000))
    }

    // ---------- USB / LAN ----------

    @Test
    fun usbAleboLan() {
        assertEquals(ClientKind.USB, ClientKind.of("127.0.0.1"))
        assertEquals(ClientKind.USB, ClientKind.of("/127.0.0.1"))
        assertEquals(ClientKind.USB, ClientKind.of("::1"))
        assertEquals(ClientKind.USB, ClientKind.of("0:0:0:0:0:0:0:1"))
        assertEquals(ClientKind.LAN, ClientKind.of("192.168.1.20"))
        assertEquals(ClientKind.LAN, ClientKind.of(null))
    }

    @Test
    fun statistikyPoctuPoziadaviek() {
        val s = ServerStats()
        s.begin(ClientKind.USB)
        s.begin(ClientKind.LAN)
        assertEquals(2, s.inFlight.get())
        s.end("/api/list", SlowLog.Entry(0, "/api/list", 200, 5, 6, 10, ClientKind.USB))
        s.abort()
        s.abort()
        assertEquals(0, s.inFlight.get())
        assertEquals(1L, s.usb.get())
        assertEquals(1L, s.lan.get())
        s.rebuilt(100, 1)
        s.rebuilt(300, 2)
        assertEquals(200L, s.rebuildAvg)
        assertEquals(300L, s.rebuildMax)
        assertEquals(2L, s.rebuildCount)
    }
}
