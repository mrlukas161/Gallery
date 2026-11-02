package org.fossify.gallery.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ServerCachesTest {
    // ---------- fronta dekódovaní ----------

    private fun thread(block: () -> Unit) = Thread(block).apply {
        isDaemon = true
        start()
    }

    @Test
    fun volnaFrontaDekodujeHned() {
        val q = DecodeQueue(parallel = 2, maxWaiting = 6)
        val out = q.run({ false }) { 7 }
        assertEquals(7, (out as DecodeQueue.Outcome.Done).value)
        assertEquals(1L, q.completed.get())
        assertEquals(0, q.running)
    }

    @Test
    fun plnaFrontaOdmietne() {
        val q = DecodeQueue(parallel = 1, maxWaiting = 1, pollMs = 10)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val busy = thread { q.run({ false }) { started.countDown(); release.await() } }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val waiterIn = CountDownLatch(1)
        val waiter = thread {
            waiterIn.countDown()
            q.run({ false }) { 1 }
        }
        assertTrue(waiterIn.await(1, TimeUnit.SECONDS))
        // počkaj, kým druhý naozaj čaká vo fronte
        val deadline = System.currentTimeMillis() + 2000
        while (q.waiting < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(1, q.waiting)
        // tretí sa už nezmestí -> 503
        assertTrue(q.run({ false }) { 2 } is DecodeQueue.Outcome.Rejected)
        assertEquals(1L, q.rejected.get())
        release.countDown()
        busy.join(2000)
        waiter.join(2000)
        assertEquals(2L, q.completed.get())
    }

    @Test
    fun zrusenaPoziadavkaSaNedekoduje() {
        val q = DecodeQueue(parallel = 1, maxWaiting = 4, pollMs = 10)
        val decoded = AtomicBoolean(false)
        // klient zavrel spojenie ešte pred štartom
        val out = q.run({ true }) { decoded.set(true) }
        assertTrue(out is DecodeQueue.Outcome.Cancelled)
        assertFalse(decoded.get())
        assertEquals(0, q.running)
        // a počas čakania vo fronte
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val busy = thread { q.run({ false }) { started.countDown(); release.await() } }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val gone = AtomicBoolean(false)
        var result: DecodeQueue.Outcome<Unit>? = null
        val w = thread { result = q.run({ gone.get() }) { decoded.set(true) } }
        Thread.sleep(50)
        gone.set(true)
        w.join(2000)
        assertTrue(result is DecodeQueue.Outcome.Cancelled)
        assertFalse(decoded.get())
        assertEquals(2L, q.cancelled.get())
        release.countDown()
        busy.join(2000)
    }

    // ---------- plán dekódovania ----------

    @Test
    fun cielPresneNaMaxBezZvacsenia() {
        assertEquals(DecodePlan(2, 1024, 768), DecodePlan.of(4000, 3000, 1024))
        assertEquals(DecodePlan(1, 2560, 1920), DecodePlan.of(4000, 3000, 2560))
        // menší obrázok sa nezväčšuje
        assertEquals(DecodePlan(1, 800, 600), DecodePlan.of(800, 600, 2048))
        // na výšku
        assertEquals(DecodePlan(2, 768, 1024), DecodePlan.of(3000, 4000, 1024))
        // vzorkovanie nikdy pod cieľ: 4096 z 8192 -> 2, 1000 z 8192 -> 8 (1024 ≥ 1000)
        assertEquals(2, DecodePlan.of(8192, 6144, 4096).sample)
        assertEquals(8, DecodePlan.of(8192, 6144, 1000).sample)
    }

    @Test
    fun ochranaPamatePreHeic50Mpx() {
        // 8160×6120 (50 Mpx) na 4096: celé dekódovanie by malo ~200 MB -> vzorkovanie 2, výsledok 4080 px
        val p = DecodePlan.of(8160, 6120, 4096)
        assertEquals(2, p.sample)
        assertEquals(4080, p.width)
        assertEquals(3060, p.height)
        assertTrue(p.width <= 4096)
        // 12,5 Mpx sa dekóduje celé
        assertEquals(DecodePlan(1, 4096, 3072), DecodePlan.of(4096, 3072, 4096))
    }

    // web-0.67.2 „Plná kvalita“: max až 8192, plné rozlíšenie (dekóduje sa samo) má strop 8192² px
    @Test
    fun plneRozlisenieBezZbytocnehoZmensenia() {
        assertEquals(DecodePlan(1, 4032, 3024), DecodePlan.of(4032, 3024, 4032))
        assertEquals(DecodePlan(1, 4032, 3024), DecodePlan.of(4032, 3024, 8192))
        // 50 Mpx v plnom rozlíšení sa dekóduje celé
        assertEquals(DecodePlan(1, 8160, 6120), DecodePlan.of(8160, 6120, 8160))
        // náhľad prehliadača 2560 z 12 Mpx
        assertEquals(DecodePlan(1, 2560, 1920), DecodePlan.of(4032, 3024, 2560))
        // malé náhľady
        assertEquals(8, DecodePlan.of(4032, 3024, 300).sample)
        assertEquals(8, DecodePlan.of(8160, 6120, 512).sample)
    }

    @Test
    fun nikdyObrovskaBitmapa() {
        // 200 Mpx (16320 × 12240): pri max 8192 vzorkovanie 2 -> 8160 px (50 Mpx)
        val full = DecodePlan.of(16320, 12240, 8192)
        assertEquals(2, full.sample)
        assertEquals(8160, full.width)
        // pri 4096 (dve dekódovania naraz) prísnejší strop 25 Mpx -> vzorkovanie 4, výsledok 4080 px
        val big = DecodePlan.of(16320, 12240, 4096)
        assertEquals(4, big.sample)
        assertEquals(4080, big.width)
        assertTrue((16320L / big.sample) * (12240L / big.sample) <= DecodePlan.GUARD_PX)
    }

    @Test
    fun vyhradneDekodovanieBeziSamo() {
        val q = DecodeQueue(parallel = 2, maxWaiting = 6, pollMs = 10)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val one = thread { q.run({ false }) { started.countDown(); release.await() } }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val exclusiveRan = AtomicBoolean(false)
        val ex = thread { q.run({ false }, exclusive = true) { exclusiveRan.set(true) } }
        Thread.sleep(100)
        // druhé miesto je voľné, ale výhradné dekódovanie čaká, kým nedobehne prvé
        assertFalse(exclusiveRan.get())
        assertEquals(1, q.waiting)
        release.countDown()
        one.join(2000)
        ex.join(2000)
        assertTrue(exclusiveRan.get())
        assertEquals(0, q.running)
    }

    @Test
    fun neplatneRozmery() {
        assertEquals(DecodePlan(1, 0, 0), DecodePlan.of(0, 0, 1024))
        // extrémny pomer strán: aspoň 1 px
        assertEquals(100 to 1, DecodePlan.fit(10_000, 1, 100))
    }

    // ---------- LRU diskovej cache ----------

    @Test
    fun lruMazeNajstarsiePouzite() {
        val idx = DiskLruIndex(maxBytes = 100)
        assertTrue(idx.load(listOf("a" to 40L, "b" to 40L)).isEmpty())
        assertTrue(idx.touch("a")) // a je teraz najnovšie
        assertEquals(listOf("b"), idx.put("c", 40))
        assertEquals(80L, idx.totalBytes())
        assertFalse(idx.touch("b"))
        // príliš veľký súbor sa nezapíše vôbec
        assertEquals(listOf("huge"), idx.put("huge", 200))
        assertEquals(2, idx.count())
    }

    @Test
    fun nacitanieOrezeNaLimit() {
        val idx = DiskLruIndex(maxBytes = 100)
        val gone = idx.load(listOf("old" to 60L, "mid" to 30L, "new" to 30L))
        assertEquals(listOf("old"), gone)
        assertEquals(60L, idx.totalBytes())
    }

    @Test
    fun prepisRovnakehoKluca() {
        val idx = DiskLruIndex(maxBytes = 100)
        idx.put("a", 50)
        idx.put("a", 70)
        assertEquals(70L, idx.totalBytes())
        idx.remove("a")
        assertEquals(0L, idx.totalBytes())
    }

    @Test
    fun diskovaCacheNaDisku() {
        val dir = Files.createTempDirectory("pc_thumbs").toFile()
        try {
            File(dir, "x.jpg.1.tmp").writeText("nedopísané")
            var now = 1_000L
            val c = ThumbDiskCache(dir, 250) { now }
            assertNull(c.get("t:1")) // pred init nič
            c.init()
            assertFalse(File(dir, "x.jpg.1.tmp").exists())
            c.put("t:1", ByteArray(100) { 1 })
            c.put("t:2", ByteArray(100) { 2 })
            assertArrayEquals(ByteArray(100) { 1 }, c.get("t:1"))
            now += 1
            c.put("t:3", ByteArray(100) { 3 }) // vytlačí t:2 (najstaršie použité)
            assertNull(c.get("t:2"))
            assertEquals(2, c.files())
            assertEquals(2L, c.misses.get())
            assertEquals(1L, c.hits.get())
            // po reštarte servera sa cache načíta z disku
            val again = ThumbDiskCache(dir, 250) { now }
            again.init()
            assertArrayEquals(ByteArray(100) { 3 }, again.get("t:3"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun menoSuboruZKluca() {
        val n = ThumbDiskCache.nameOf("t:1:2:3:2560")
        assertTrue(n.endsWith(".jpg"))
        assertEquals(36, n.length)
        assertNotEquals(n, ThumbDiskCache.nameOf("t:1:2:3:1024"))
    }

    // ---------- kľúč a cache /api/groups ----------

    @Test
    fun etagSkupinPodlaKluca() {
        val k = GroupsKey("ep", 7, 1, 1, 3, true)
        assertEquals(k.etag(), k.copy().etag())
        assertNotEquals(k.etag(), k.copy(gen = 8).etag())
        assertNotEquals(k.etag(), k.copy(repo = 2).etag())
        assertNotEquals(k.etag(), k.copy(hidden = 2).etag())
        assertNotEquals(k.etag(), k.copy(scores = 4).etag())
        assertNotEquals(k.etag(), k.copy(v2 = false).etag())
        assertNotEquals(k.etag(), k.copy(epoch = "x").etag())
        assertTrue(ETags.matches(k.etag(), k.etag()))
    }

    @Test
    fun cacheSkupinVratiTeloLenPreRovnakyKluc() {
        val c = GroupsCache()
        val k = GroupsKey("ep", 7, 1, 1, 3, true)
        assertNull(c.get(k))
        c.put(k, "{v2}")
        c.put(k.copy(v2 = false), "{v1}")
        assertEquals("{v2}", c.get(k))
        assertEquals("{v1}", c.get(k.copy(v2 = false)))
        assertNull(c.get(k.copy(scores = 4)))
        assertEquals(2L, c.hits.get())
        assertEquals(2L, c.misses.get())
    }

    @Test
    fun verziaPodlaIdentity() {
        val v = IdentityVersion()
        val a = Any()
        val first = v.of(a)
        assertEquals(first, v.of(a))
        assertEquals(first + 1, v.of(Any()))
        assertEquals(first + 2, v.of(null))
    }
}
