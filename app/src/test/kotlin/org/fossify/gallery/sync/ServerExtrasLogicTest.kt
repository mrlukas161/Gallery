package org.fossify.gallery.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ServerExtrasLogicTest {
    // ---------- /api/zip ----------

    @Test
    fun idsZFormulara() {
        val ok = ZipRequest.parseIds(listOf("1, 2,3", "3,4")) as ZipRequest.Ids.Ok
        assertEquals(listOf(1L, 2L, 3L, 4L), ok.ids)
        assertTrue(ZipRequest.parseIds(null) is ZipRequest.Ids.Bad)
        assertTrue(ZipRequest.parseIds(listOf("")) is ZipRequest.Ids.Bad)
        assertTrue(ZipRequest.parseIds(listOf("1,x")) is ZipRequest.Ids.Bad)
        assertTrue(ZipRequest.parseIds(listOf("-5")) is ZipRequest.Ids.Bad)
    }

    @Test
    fun najviac500Id() {
        val five = (1..500).joinToString(",")
        assertTrue(ZipRequest.parseIds(listOf(five)) is ZipRequest.Ids.Ok)
        assertTrue(ZipRequest.parseIds(listOf("$five,501")) is ZipRequest.Ids.TooMany)
        // duplicity sa nerátajú
        assertTrue(ZipRequest.parseIds(listOf("$five,1,2")) is ZipRequest.Ids.Ok)
    }

    @Test
    fun menoZipu() {
        assertEquals("Galeria-20261102-2105.zip", ZipRequest.fileName(LocalDateTime.of(2026, 11, 2, 21, 5, 59)))
    }

    @Test
    fun menaVZipeSuJedinecne() {
        val n = ZipNames()
        assertEquals("IMG_1.jpg", n.unique("IMG_1.jpg"))
        assertEquals("IMG_1 (2).jpg", n.unique("IMG_1.jpg"))
        assertEquals("img_1 (3).JPG", n.unique("img_1.JPG")) // Windows nerozlišuje veľkosť písmen
        assertEquals("IMG_1 (2) (2).jpg", n.unique("IMG_1 (2).jpg"))
        assertEquals("a_b.jpg", n.unique("a/b.jpg"))
        assertEquals("subor", n.unique("\u0001"))
        assertEquals("subor (2)", n.unique(""))
        assertEquals("README", n.unique("README"))
        assertEquals("readme (2)", n.unique("readme"))
    }

    @Test
    fun chybajuceSubory() {
        assertNull(ZipRequest.missingText(emptyList(), emptyList()))
        val t = ZipRequest.missingText(listOf(12L, 13L), listOf("IMG_9.jpg"))!!
        assertTrue(t.contains("12\n13\n"))
        assertTrue(t.contains("IMG_9.jpg"))
    }

    // ---------- /api/motion: Range voči výrezu ----------

    @Test
    fun celeVideoVyrezu() {
        assertEquals(MotionSlice.Result.Full(3_000_000, 1_000_000), MotionSlice.of(null, 3_000_000, 1_000_000))
    }

    @Test
    fun rozsahOdDo() {
        val r = MotionSlice.of("bytes=100-199", 3_000_000, 1_000_000) as MotionSlice.Result.Partial
        assertEquals(3_000_100L, r.absStart)
        assertEquals(100L, r.len)
        assertEquals("bytes 100-199/1000000", r.contentRange)
        // otvorený koniec a koniec za výrezom sa oreže na dĺžku výrezu
        val open = MotionSlice.of("bytes=999900-", 3_000_000, 1_000_000) as MotionSlice.Result.Partial
        assertEquals(3_999_900L, open.absStart)
        assertEquals(100L, open.len)
        val far = MotionSlice.of("bytes=0-5000000", 10, 100) as MotionSlice.Result.Partial
        assertEquals(100L, far.len)
        assertEquals("bytes 0-99/100", far.contentRange)
    }

    @Test
    fun poslednychNBajtov() {
        val r = MotionSlice.of("bytes=-50", 1000, 400) as MotionSlice.Result.Partial
        assertEquals(1350L, r.absStart)
        assertEquals(50L, r.len)
        assertEquals("bytes 350-399/400", r.contentRange)
    }

    @Test
    fun mimoVyrezuJe416() {
        val r = MotionSlice.of("bytes=400-", 1000, 400) as MotionSlice.Result.Unsatisfiable
        assertEquals("bytes */400", r.contentRange)
        assertTrue(MotionSlice.of("bytes=-0", 1000, 400) is MotionSlice.Result.Unsatisfiable)
    }

    @Test
    fun zmenenySuborCezIfRangeJeCelyVyrez() {
        val r = MotionSlice.of("bytes=0-9", 1000, 400, ifRange = "\"stary\"", etag = "\"novy\"")
        assertEquals(MotionSlice.Result.Full(1000, 400), r)
    }

    // ---------- obľúbené a pohyblivé ----------

    @Test
    fun oblubeneBezOhladuNaVelkostPismen() {
        val old = FavMatch.normalize(listOf("/sdcard/DCIM/A.jpg", "/sdcard/DCIM/B.jpg"))
        val new = FavMatch.normalize(listOf("/sdcard/dcim/a.JPG", "/sdcard/DCIM/C.jpg"))
        assertTrue(FavMatch.norm("/SDCARD/dcim/A.jpg") in old)
        assertEquals(setOf("/sdcard/dcim/b.jpg", "/sdcard/dcim/c.jpg"), FavMatch.changed(old, new))
    }

    @Test
    fun pohyblivaPodlaCacheSkenu() {
        // cache má čas súboru v ms, MediaStore v sekundách
        assertTrue(MotionCacheMatch.matches(1_730_000_000_456L, 3_000_000, 1_730_000_000L, 3_000_000))
        assertFalse(MotionCacheMatch.matches(1_730_000_000_456L, 3_000_000, 1_730_000_001L, 3_000_000))
        assertFalse(MotionCacheMatch.matches(1_730_000_000_456L, 3_000_001, 1_730_000_000L, 3_000_000))
        assertFalse(MotionCacheMatch.matches(0L, 3_000_000, 0L, 3_000_000))
    }
}
