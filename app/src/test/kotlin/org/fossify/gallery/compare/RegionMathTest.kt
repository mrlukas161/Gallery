package org.fossify.gallery.compare

import org.fossify.gallery.family.RelBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionMathTest {
    private val none = RelBox.Transform(0, false)

    // panel 1000×800, bitmapa 2000×1500, originál 8000×6000 (48 Mpx)
    private fun planAt(relZoom: Float, uw: Int = 8000, uh: Int = 6000, t: RelBox.Transform = none): RegionPlan? {
        val abs = PaneMath.toAbsolute(1000f, 800f, 2000f, 1500f, RelativeView(0.5f, 0.5f, relZoom))
        val visible = PaneMath.visibleRect(1000f, 800f, 2000f, 1500f, abs)
        return RegionMath.plan(visible, 2000f, 1500f, uw, uh, abs.zoom, t)
    }

    @Test
    fun noRegionWhileBitmapIsSharpEnough() {
        assertNull(planAt(1f))
        assertNull(planAt(2f)) // zoom bitmapy = 1,0 → pixel na pixel
        assertNull(planAt(6f, uw = 2000, uh = 1500)) // originál nemá viac detailu
    }

    @Test
    fun zoomedInReadsVisiblePartWithMargin() {
        val plan = requireNotNull(planAt(8f))
        // viditeľných 250 px bitmapy = 1000 px originálu, s okrajom 25 % na každú stranu = 1500
        assertEquals(1500, plan.upright.width)
        assertEquals(4000, (plan.upright.left + plan.upright.right) / 2)
        assertEquals(1, plan.sample) // 1000 px panela na 1000 px originálu → plné rozlíšenie
        assertEquals(plan.upright, plan.raw)
    }

    @Test
    fun sampleSizeKeepsAtLeastScreenResolutionAndMemoryGuard() {
        val plan = requireNotNull(planAt(3f))
        // viditeľná tretina originálu (~2667 px) na 1000 px panela → stačí každý 2. pixel
        assertEquals(2, plan.sample)
        assertTrue(plan.upright.pixels / (plan.sample.toLong() * plan.sample) <= RegionMath.MAX_PIXELS)
    }

    @Test
    fun rotatedPhotoMapsBackToRawFile() {
        // fotka na výšku uložená naležato s EXIF otočením o 90°: vzpriamene 6000×8000, surovo 8000×6000
        val t = RelBox.exifTransform(RelBox.EXIF_ROTATE_90)
        val upright = PixelRect(0, 0, 3000, 2000) // ľavý horný roh vzpriamenej fotky
        val raw = RegionMath.toRaw(upright, 6000, 8000, t)
        // ľavý horný roh vzpriamene = ľavý dolný roh surového súboru
        assertEquals(PixelRect(0, 3000, 2000, 6000), raw)
        val back = RelBox.fromAbsolute(raw.left, raw.top, raw.right, raw.bottom, 8000, 6000)
            .transformed(t.rotationDegrees, t.mirrored).toAbsolute(6000, 8000)
        assertEquals(listOf(0, 0, 3000, 2000), back.toList())
    }

    @Test
    fun coversOnlyWhenRegionContainsViewAtSameQuality() {
        val plan = requireNotNull(planAt(8f))
        assertTrue(RegionMath.covers(plan, plan))
        assertFalse(RegionMath.covers(null, plan))
        val coarser = plan.copy(sample = plan.sample * 2)
        assertFalse(RegionMath.covers(coarser, plan))
        val elsewhere = plan.copy(upright = PixelRect(0, 0, 500, 500))
        assertFalse(RegionMath.covers(elsewhere, plan))
    }
}
