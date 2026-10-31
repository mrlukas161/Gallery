package org.fossify.gallery.compare

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneMathTest {
    private val eps = 1e-3f

    @Test
    fun fitIsCenteredAtZoomOne() {
        val abs = PaneMath.toAbsolute(1000f, 800f, 2000f, 1500f, RelativeView.FIT)
        assertEquals(0.5f, abs.zoom, eps)
        assertEquals(0f, abs.x, eps)
        assertEquals(25f, abs.y, eps) // 1500 × 0,5 = 750 → zvyšok 50 px na dva okraje
        val rel = PaneMath.toRelative(1000f, 800f, 2000f, 1500f, abs)
        assertEquals(RelativeView.FIT.centerX, rel.centerX, eps)
        assertEquals(1f, rel.zoom, eps)
        assertFalse(rel.isZoomed)
    }

    @Test
    fun sameSceneSpotAcrossDifferentResolutions() {
        // panel A: 12 Mpx bitmapa 2000×1500, priblížené 4× na bod (0,3 ; 0,7)
        val rel = RelativeView(0.3f, 0.7f, 4f)
        val a = PaneMath.toAbsolute(1000f, 800f, 2000f, 1500f, rel)
        val back = PaneMath.toRelative(1000f, 800f, 2000f, 1500f, a)
        assertEquals(0.3f, back.centerX, eps)
        assertEquals(0.7f, back.centerY, eps)
        assertEquals(4f, back.zoom, eps)
        // panel B: menšia kópia 800×600 v inak veľkom paneli → ten istý bod scény v strede
        val b = PaneMath.toAbsolute(500f, 900f, 800f, 600f, back)
        val centerImgX = (250f - b.x) / b.zoom / 800f
        val centerImgY = (450f - b.y) / b.zoom / 600f
        assertEquals(0.3f, centerImgX, eps)
        assertEquals(0.7f, centerImgY, eps)
        assertTrue(PaneMath.toRelative(500f, 900f, 800f, 600f, b).isZoomed)
    }

    @Test
    fun visibleRectAtFitAndZoomed() {
        val fit = PaneMath.toAbsolute(1000f, 800f, 2000f, 1500f, RelativeView.FIT)
        val all = PaneMath.visibleRect(1000f, 800f, 2000f, 1500f, fit)
        assertArrayEquals(floatArrayOf(0f, 0f, 2000f, 1500f), all, 0.5f)
        val zoomed = PaneMath.toAbsolute(1000f, 800f, 2000f, 1500f, RelativeView(0.5f, 0.5f, 4f))
        val r = PaneMath.visibleRect(1000f, 800f, 2000f, 1500f, zoomed)
        assertEquals(500f, r[2] - r[0], 0.5f) // 1000 px panela / zoom 2
        assertEquals(1000f, (r[0] + r[2]) / 2f, 0.5f)
    }

    @Test
    fun stackedInPortraitForLandscapePhotos() {
        assertTrue(PaneMath.stacked(1080f, 1500f, 4f / 3f))
        assertFalse(PaneMath.stacked(2200f, 900f, 4f / 3f))
        // úzke fotky na výšku v širokom priestore → vedľa seba
        assertFalse(PaneMath.stacked(1080f, 1500f, 9f / 20f))
    }

    @Test
    fun medianAspectIgnoresOutliers() {
        assertEquals(4f / 3f, PaneMath.medianAspect(listOf(4f / 3f, 4f / 3f, 6f)), eps)
        assertEquals(4f / 3f, PaneMath.medianAspect(emptyList()), eps)
    }
}
