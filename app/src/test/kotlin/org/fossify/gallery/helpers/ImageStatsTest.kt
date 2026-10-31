package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageStatsTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // šachovnica s políčkami cell × cell (ostré hrany)
    private fun checker(w: Int, h: Int, cell: Int) =
        GrayImage(IntArray(w * h) { i -> if ((i % w / cell + i / w / cell) % 2 == 0) 30 else 220 }, w, h)

    // jednoduché rozmazanie 3×3 (opakovane)
    private fun blur(img: GrayImage, times: Int): GrayImage {
        var px = img.pixels
        repeat(times) {
            val src = px
            px = IntArray(src.size) { i ->
                val x = i % img.width
                val y = i / img.width
                var sum = 0
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until img.width && yy in 0 until img.height) {
                        sum += src[yy * img.width + xx]
                        n++
                    }
                }
                sum / n
            }
        }
        return GrayImage(px, img.width, img.height)
    }

    @Test
    fun lumaWeights() {
        assertEquals(255, ImageStats.luma(argb(255, 255, 255)))
        assertEquals(0, ImageStats.luma(argb(0, 0, 0)))
        assertEquals(76, ImageStats.luma(argb(255, 0, 0)))
        assertEquals(149, ImageStats.luma(argb(0, 255, 0)))
    }

    @Test
    fun exposurePenalizesClippedPixels() {
        assertEquals(1.0, ImageStats.exposure(IntArray(1000) { 128 }), 1e-9)
        // štvrtina prepálená → 1 − 2 · 0,25
        assertEquals(0.5, ImageStats.exposure(IntArray(1000) { if (it % 4 == 0) 255 else 128 }), 1e-9)
        assertEquals(0.0, ImageStats.exposure(IntArray(1000) { 0 }), 1e-9)
        assertEquals(0.5, ImageStats.exposure(IntArray(0)), 1e-9)
    }

    @Test
    fun downscaleKeepsSmallImagesAndAveragesLargeOnes() {
        val small = GrayImage(IntArray(100) { 7 }, 10, 10)
        assertSame(small, ImageStats.downscale(small, 400))
        val img = GrayImage(IntArray(800 * 400) { if (it % 2 == 0) 0 else 200 }, 800, 400)
        val out = ImageStats.downscale(img, 400)
        assertEquals(400, out.width)
        assertEquals(200, out.height)
        assertTrue(out.pixels.all { it == 100 })
    }

    @Test
    fun sharperImageScoresHigher() {
        val sharp = checker(200, 160, 8)
        val soft = blur(sharp, 3)
        assertTrue(ImageStats.sharpness(sharp) > ImageStats.sharpness(soft) * 2)
        assertEquals(0.0, ImageStats.sharpness(GrayImage(IntArray(200 * 160) { 90 }, 200, 160)), 1e-9)
        assertEquals(0.0, ImageStats.sharpness(GrayImage(IntArray(4), 2, 2)), 1e-9)
    }

    @Test
    fun sharpnessFollowsSharpestPartNotAverage() {
        // ostrá len horná tretina (tvár), zvyšok rozmazaný (bokeh) — skóre ako pri ostrej fotke
        val sharp = checker(240, 240, 8)
        val soft = blur(sharp, 4)
        val px = IntArray(240 * 240) { i -> if (i / 240 < 90) sharp.pixels[i] else soft.pixels[i] }
        val mixed = GrayImage(px, 240, 240)
        assertTrue(ImageStats.sharpness(mixed) > ImageStats.sharpness(soft) * 2)
    }
}
