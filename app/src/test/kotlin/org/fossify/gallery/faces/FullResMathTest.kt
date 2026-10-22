package org.fossify.gallery.faces

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullResMathTest {
    // dopredné mapovanie surový → upright (rovnaké ako UprightDecoder.mapBox bez zrkadlenia)
    private fun forward(b: IntArray, rawW: Int, rawH: Int, deg: Int): IntArray = when (deg) {
        90 -> intArrayOf(rawH - b[3], b[0], rawH - b[1], b[2])
        180 -> intArrayOf(rawW - b[2], rawH - b[3], rawW - b[0], rawH - b[1])
        270 -> intArrayOf(b[1], rawW - b[2], b[3], rawW - b[0])
        else -> b
    }

    @Test
    fun sampleMatchesUprightDecoder() {
        assertEquals(1, FullResMath.sampleFor(1000, 800, 1024))
        assertEquals(4, FullResMath.sampleFor(4000, 3000, 1024))
        assertEquals(8, FullResMath.sampleFor(8160, 6120, 1024))
        assertArrayEquals(intArrayOf(750, 1000), FullResMath.uprightDims(4000, 3000, 4, 90))
        assertArrayEquals(intArrayOf(1000, 750), FullResMath.uprightDims(4000, 3000, 4, 180))
    }

    @Test
    fun rawRectIsInverseOfUprightMapping() {
        val rawW = 4000
        val rawH = 3000
        val sample = 4
        val sw = rawW / sample
        val sh = rawH / sample
        for (deg in listOf(0, 90, 180, 270)) {
            val dims = FullResMath.uprightDims(rawW, rawH, sample, deg)
            val region = intArrayOf(dims[0] / 3, dims[1] / 4, dims[0] / 3 + 120, dims[1] / 4 + 90)
            val raw = FullResMath.rawRect(region, deg, sw, sh, sample, rawW, rawH)!!
            val scaled = IntArray(4) { raw[it] / sample }
            assertArrayEquals("deg=$deg", region, forward(scaled, sw, sh, deg))
        }
    }

    @Test
    fun rawRectClampsAndRejectsEmpty() {
        val r = FullResMath.rawRect(intArrayOf(0, 0, 2000, 2000), 0, 1000, 750, 4, 4000, 3000)!!
        assertArrayEquals(intArrayOf(0, 0, 4000, 3000), r)
        assertNull(FullResMath.rawRect(intArrayOf(5, 5, 5, 9), 0, 1000, 750, 4, 4000, 3000))
    }

    @Test
    fun marginRegionLikeCropRegion() {
        assertArrayEquals(intArrayOf(70, 70, 130, 130), FullResMath.regionWithMargin(80, 80, 120, 120, 0.25f, 500, 500))
        // orezanie na okraj fotky
        assertArrayEquals(intArrayOf(0, 0, 26, 26), FullResMath.regionWithMargin(0, 0, 20, 20, 0.3f, 500, 500))
        assertNull(FullResMath.regionWithMargin(10, 10, 10, 30, 0.3f, 500, 500))
    }

    @Test
    fun regionSampleKeepsFaceAtLeastTargetWide() {
        assertEquals(1, FullResMath.regionSample(100))
        assertEquals(1, FullResMath.regionSample(447))
        assertEquals(2, FullResMath.regionSample(448))
        assertEquals(4, FullResMath.regionSample(1000))
        for (w in listOf(230, 500, 900, 2000, 3999)) {
            val s = FullResMath.regionSample(w)
            assertTrue(w / s >= FullResMath.TARGET_FACE_PX)
            assertTrue(w / (s * 2) < FullResMath.TARGET_FACE_PX)
        }
    }
}
