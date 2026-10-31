package org.fossify.gallery.faces

import org.fossify.gallery.faces.BurstGrouper.Shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstGrouperTest {
    private val t0 = 1_700_000_000_000L

    private fun names(groups: List<List<Shot>>) = groups.map { g -> g.map { it.name } }

    @Test
    fun shotsWithinGapInSameFolderFormSeries() {
        val shots = listOf(
            Shot("/DCIM/Camera/a.jpg", t0),
            Shot("/DCIM/Camera/b.jpg", t0 + 1_000),
            Shot("/DCIM/Camera/c.jpg", t0 + 4_000), // medzera presne 3 s
            Shot("/DCIM/Camera/d.jpg", t0 + 7_001), // medzera 3,001 s → nová séria (sama, teda nič)
            Shot("/DCIM/Other/e.jpg", t0 + 500), // iný priečinok
        )
        assertEquals(listOf(listOf("a.jpg", "b.jpg", "c.jpg")), names(BurstGrouper.group(shots)))
    }

    @Test
    fun foldersInterleavedInTimeDoNotBreakSeries() {
        val shots = (0 until 6).map { i -> Shot(if (i % 2 == 0) "/A/a$i.jpg" else "/B/b$i.jpg", t0 + i * 500L) }
        val expected = setOf(listOf("a0.jpg", "a2.jpg", "a4.jpg"), listOf("b1.jpg", "b3.jpg", "b5.jpg"))
        assertEquals(expected, names(BurstGrouper.group(shots)).toSet())
    }

    @Test
    fun photosWithoutDateTakenAreNotChained() {
        // obnovená záloha / WhatsApp: bez EXIF dátumu — čas súboru sa nepoužije, nič nevznikne
        val shots = (0 until 30).map { Shot("/WhatsApp/Images/IMG-$it.jpg", 0L) }
        assertTrue(BurstGrouper.group(shots).isEmpty())
    }

    @Test
    fun giantChainIsSplitIntoBoundedSeries() {
        // intervalové snímky každú sekundu počas ~17 minút
        val shots = (0 until 1000).map { Shot("/DCIM/Camera/i%04d.jpg".format(it), t0 + it * 1_000L + (it % 7) * 13L) }
        val groups = BurstGrouper.group(shots)
        assertTrue(groups.size >= 1000 / BurstGrouper.MAX_SHOTS)
        for (g in groups) {
            assertTrue(g.size in 2..BurstGrouper.MAX_SHOTS)
            assertTrue(g.last().takenMs - g.first().takenMs <= BurstGrouper.MAX_SPAN_MS)
        }
        // nič sa nestratí okrem prípadných osamotených záberov
        assertTrue(groups.sumOf { it.size } >= 990)
        // kusy na seba nadväzujú (žiadna fotka v dvoch sériách)
        assertEquals(groups.sumOf { it.size }, groups.flatten().map { it.path }.toSet().size)
    }

    @Test
    fun longChainIsCutAtTheBiggestGap() {
        // 40 záberov s krokom 2 s, ale medzi 20. a 21. sú 3 s → rozdelí sa práve tam (rozpätie > 60 s)
        val shots = (0 until 40).map { i ->
            Shot("/C/s%02d.jpg".format(i), t0 + i * 2_000L + if (i >= 20) 1_000L else 0L)
        }
        val groups = BurstGrouper.group(shots)
        assertEquals(listOf(20, 20), groups.map { it.size })
        assertEquals("s20.jpg", groups[1].first().name)
    }

    @Test
    fun sceneChangeSplitsChain() {
        val a = 0x0F0F_3C3C_5A5A_A5A5L
        val b = a.inv() // úplne iná scéna
        val shots = listOf(
            Shot("/C/1.jpg", t0, a),
            Shot("/C/2.jpg", t0 + 1_000, a xor 3L),
            Shot("/C/3.jpg", t0 + 2_000, b),
            Shot("/C/4.jpg", t0 + 3_000, b xor 1L),
        )
        assertEquals(listOf(listOf("1.jpg", "2.jpg"), listOf("3.jpg", "4.jpg")), names(BurstGrouper.group(shots)))
    }

    @Test
    fun burstNamesGroupRegardlessOfTime() {
        val shots = listOf(
            Shot("/DCIM/Camera/IMG_20240101_120000_BURST001.jpg", t0),
            Shot("/DCIM/Camera/IMG_20240101_120000_BURST002_COVER.jpg", t0 + 20_000),
            Shot("/DCIM/Camera/00000IMG_00000_BURST20240101120000123_COVER.jpg", 0L),
            Shot("/DCIM/Camera/00001IMG_00001_BURST20240101120000123.jpg", 0L),
            Shot("/DCIM/Camera/IMG_20240101_130000_BURST001.jpg", t0 + 60_000), // iná séria, sama
            Shot("/DCIM/Other/IMG_20240101_120000_BURST003.jpg", t0), // iný priečinok
        )
        assertEquals(
            setOf(
                listOf("IMG_20240101_120000_BURST001.jpg", "IMG_20240101_120000_BURST002_COVER.jpg"),
                listOf("00000IMG_00000_BURST20240101120000123_COVER.jpg", "00001IMG_00001_BURST20240101120000123.jpg"),
            ),
            names(BurstGrouper.group(shots)).toSet(),
        )
    }

    @Test
    fun burstKeys() {
        assertEquals("p:img_20240101_120000", BurstGrouper.burstKey("IMG_20240101_120000_BURST001.jpg"))
        assertEquals("t:20240101120000123", BurstGrouper.burstKey("00001IMG_00001_BURST20240101120000123.jpg"))
        assertNull(BurstGrouper.burstKey("IMG_20240101_120000.jpg"))
    }

    @Test
    fun hugeNamedBurstIsCapped() {
        val shots = (0 until 120).map { Shot("/C/IMG_X_BURST%03d.jpg".format(it), 0L) }
        val groups = BurstGrouper.group(shots)
        assertTrue(groups.all { it.size <= BurstGrouper.MAX_SHOTS })
        assertEquals(120, groups.sumOf { it.size })
    }

    @Test
    fun loneBurstNameFallsBackToTimeChain() {
        val shots = listOf(Shot("/C/IMG_1_BURST001.jpg", t0), Shot("/C/IMG_2.jpg", t0 + 1_000))
        assertEquals(listOf(listOf("IMG_1_BURST001.jpg", "IMG_2.jpg")), names(BurstGrouper.group(shots)))
    }
}
