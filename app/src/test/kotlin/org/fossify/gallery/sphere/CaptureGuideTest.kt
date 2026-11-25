package org.fossify.gallery.sphere

import org.fossify.gallery.spherecore.Mat3
import org.fossify.gallery.spherecore.PanoFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

class CaptureGuideTest {
    private val world = PanoFrame(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
    private val fovH = 58.9
    private val fovV = 73.9

    private fun horizonShot() = PanoFrame.cameraLookingAt(world, 0.0, 0.0)

    private fun guideWithFirst(h: Double = fovH, v: Double = fovV): CaptureGuide {
        val g = CaptureGuide(h, v)
        g.setFirstFrame(horizonShot())
        return g
    }

    private fun poseOf(g: CaptureGuide, i: Int): Mat3 {
        val t = g.targets[i]
        return PanoFrame.cameraLookingAt(g.panoFrame!!, t.yawDeg, t.pitchDeg)
    }

    /** Podrží pózu `ms` milisekúnd v krokoch po 50 ms, vráti počet spustení. */
    private fun hold(g: CaptureGuide, r: Mat3, speed: Double, ms: Int, from: Long = 0L): Int {
        var triggers = 0
        var t = 0
        while (t <= ms) {
            if (g.update(r, speed, from + t).shouldCapture) triggers++
            t += 50
        }
        return triggers
    }

    @Test
    fun firstFrameNeedsHorizonAndStillness() {
        val g = CaptureGuide(fovH, fovV)
        val ok = g.update(horizonShot(), 0.0, 0)
        assertFalse(ok.hasFirstFrame)
        assertTrue(ok.firstFrameReady)
        assertEquals(GuideHint.NONE, ok.hint)

        val up = PanoFrame.cameraLookingAt(world, 0.0, 40.0)
        val aim = g.update(up, 0.0, 10)
        assertFalse(aim.firstFrameReady)
        assertEquals(GuideHint.AIM_HORIZON, aim.hint)

        val moving = g.update(horizonShot(), 0.5, 20)
        assertFalse(moving.firstFrameReady)
        assertEquals(GuideHint.SLOWER, moving.hint)
        assertFalse(ok.shouldCapture)
    }

    @Test
    fun firstFrameMarksTargetZeroAndBuildsTargets() {
        val g = guideWithFirst()
        assertTrue(g.hasFirstFrame)
        assertTrue(g.isCaptured(0))
        assertEquals(1, g.capturedCount)
        assertEquals(g.total - 1, g.remaining.size)
        assertFalse(g.isComplete)
    }

    @Test
    fun alignedAndHeldTriggersExactlyOnce() {
        val g = guideWithFirst()
        val i = g.remaining.first()
        val r = poseOf(g, i)
        // kým sa drží menej než 400 ms, nespustí
        assertEquals(0, hold(g, r, 0.0, 350))
        val g2 = guideWithFirst()
        val s = g2.update(poseOf(g2, i), 0.0, 0)
        assertTrue(s.aligned)
        assertEquals(i, s.nearest)
        assertEquals(1, hold(g2, poseOf(g2, i), 0.0, 2000, from = 0L))
    }

    @Test
    fun holdProgressGrowsToOne() {
        val g = guideWithFirst()
        val r = poseOf(g, g.remaining.first())
        g.update(r, 0.0, 0)
        val mid = g.update(r, 0.0, 200)
        assertEquals(0.5f, mid.holdProgress, 0.01f)
        assertEquals(GuideHint.HOLD, mid.hint)
    }

    @Test
    fun movingFastGivesSlowerAndNoTrigger() {
        val g = guideWithFirst()
        val r = poseOf(g, g.remaining.first())
        assertEquals(0, hold(g, r, 0.4, 2000))
        assertEquals(GuideHint.SLOWER, g.update(r, 0.4, 3000).hint)
    }

    @Test
    fun movingOutOfStillnessResetsHold() {
        val g = guideWithFirst()
        val r = poseOf(g, g.remaining.first())
        g.update(r, 0.0, 0)
        g.update(r, 0.0, 300)
        g.update(r, 0.5, 350) // rýchly pohyb pretrhne držanie
        assertFalse(g.update(r, 0.0, 450).shouldCapture)
        assertFalse(g.update(r, 0.0, 750).shouldCapture)
        assertTrue(g.update(r, 0.0, 860).shouldCapture)
    }

    @Test
    fun rollGivesLevelAndNoTrigger() {
        val g = guideWithFirst()
        val r = poseOf(g, g.remaining.first()) * Mat3.axisAngleDeg(doubleArrayOf(0.0, 0.0, 1.0), 20.0)
        assertEquals(0, hold(g, r, 0.0, 2000))
        assertEquals(GuideHint.LEVEL, g.update(r, 0.0, 3000).hint)
    }

    @Test
    fun poleTargetIgnoresRoll() {
        val g = guideWithFirst()
        val poleIdx = g.targets.indexOfFirst { it.isPole && it.pitchDeg > 0 }
        for (i in g.remaining) if (i != poleIdx) g.markCaptured(i)
        assertEquals(listOf(poleIdx), g.remaining)
        val r = poseOf(g, poleIdx) * Mat3.axisAngleDeg(doubleArrayOf(0.0, 0.0, 1.0), 40.0)
        assertEquals(1, hold(g, r, 0.0, 2000))
    }

    @Test
    fun captureBlocksFurtherTriggersUntilFinished() {
        val g = guideWithFirst()
        val i = g.remaining.first()
        val r = poseOf(g, i)
        assertEquals(1, hold(g, r, 0.0, 600))
        g.onCaptureStarted(r)
        assertEquals(0, hold(g, r, 0.0, 2000, from = 1000L))
        val moved = g.onCaptureFinished(r)
        assertEquals(0.0, moved, 1e-6)
        g.abortCapture()
        assertEquals(1, hold(g, r, 0.0, 600, from = 5000L)) // po ukončení znova (cieľ nebol označený)
    }

    @Test
    fun movedDuringCaptureIsReported() {
        val g = guideWithFirst()
        val r = poseOf(g, g.remaining.first())
        g.onCaptureStarted(r)
        val r2 = r * Mat3.axisAngleDeg(doubleArrayOf(0.0, 1.0, 0.0), 3.0)
        val moved = g.onCaptureFinished(r2)
        assertEquals(3.0, moved, 0.01)
        assertTrue(moved > CaptureGuide.MAX_MOVE_DEG)
    }

    @Test
    fun undoLastRestoresTarget() {
        val g = guideWithFirst()
        val i = g.remaining.first()
        g.markCaptured(i)
        assertTrue(g.isCaptured(i))
        assertEquals(2, g.capturedCount)
        assertEquals(i, g.undoLast())
        assertFalse(g.isCaptured(i))
        assertEquals(i, g.remaining.first())
        // ručný záber mimo cieľov
        g.markCaptured(-1)
        assertEquals(-1, g.undoLast())
        // zrušenie prvého záberu zruší rám
        assertEquals(0, g.undoLast())
        assertFalse(g.hasFirstFrame)
        assertNull(g.undoLast())
    }

    @Test
    fun capturingAllTargetsCompletes() {
        val g = guideWithFirst()
        assertFalse(g.horizonComplete)
        for (i in g.remaining) g.markCaptured(i)
        assertTrue(g.isComplete)
        assertTrue(g.horizonComplete)
        assertTrue(g.remaining.isEmpty())
        assertEquals(-1, g.update(poseOf(g, 3), 0.0, 0).nearest)
    }

    @Test
    fun fullSweepCapturesEverythingFor29And20Targets() {
        for ((h, v) in listOf(58.9 to 73.9, 85.6 to 102.0)) {
            val total = sweep(h, v)
            assertTrue("počet cieľov $total", total in 12..40)
        }
    }

    /** To isté, čo robí DemoOrientationSource: pomalý presun k prvému nenasnímanému cieľu, stojí, kým sa záber dokončí. */
    private fun sweep(h: Double, v: Double): Int {
        val g = CaptureGuide(h, v)
        val first = horizonShot()
        g.setFirstFrame(first)
        val slewer = DemoSlewer(first)
        var now = 0L
        val dt = 20L
        var busyUntil = -1L
        var pendingTarget = -1
        var captures = 1
        while (!g.isComplete && now < 900_000L) {
            val busy = now < busyUntil
            if (!busy && pendingTarget >= 0) {
                val moved = g.onCaptureFinished(slewer.r)
                assertTrue(moved <= CaptureGuide.MAX_MOVE_DEG)
                g.markCaptured(pendingTarget)
                captures++
                pendingTarget = -1
                continue
            }
            val target = if (busy) null else g.remaining.firstOrNull()?.let { poseOf(g, it) }
            val speed = slewer.step(target, dt / 1000.0)
            val s = g.update(slewer.r, speed, now)
            if (s.shouldCapture && !busy) {
                g.onCaptureStarted(slewer.r)
                busyUntil = now + 300
                pendingTarget = s.nearest
            }
            now += dt
        }
        assertTrue("sweep nedobehol (h=$h, v=$v, ostáva ${g.remaining})", g.isComplete)
        assertEquals(g.total, g.capturedCount)
        assertEquals(g.total, captures)
        return g.total
    }

    // ---- projekcia ----

    @Test
    fun projectionCentreRightEdgeAndUp() {
        val g = guideWithFirst()
        val pf = g.panoFrame!!
        val r = PanoFrame.cameraLookingAt(pf, 0.0, 0.0)
        val out = DoubleArray(3)
        val left = 0.0
        val top = 200.0
        val w = 1080.0
        val hgt = 1440.0

        assertTrue(SphereProjection.project(r, pf.dir(0.0, 0.0), fovH, fovV, left, top, w, hgt, out))
        assertEquals(540.0, out[0], 0.01)
        assertEquals(920.0, out[1], 0.01)

        assertTrue(SphereProjection.project(r, pf.dir(fovH / 2, 0.0), fovH, fovV, left, top, w, hgt, out))
        assertEquals(left + w, out[0], 0.5)
        assertEquals(920.0, out[1], 0.5)

        assertTrue(SphereProjection.project(r, pf.dir(0.0, 10.0), fovH, fovV, left, top, w, hgt, out))
        assertTrue("nad stredom má byť menšie y", out[1] < 920.0)
        val fy = (hgt / 2) / tan(Math.toRadians(fovV) / 2)
        assertEquals(920.0 - fy * tan(Math.toRadians(10.0)), out[1], 0.5)
        assertEquals(540.0, out[0], 0.5)

        // za kamerou sa neprojektuje
        assertFalse(SphereProjection.project(r, pf.dir(180.0, 0.0), fovH, fovV, left, top, w, hgt, out))
    }

    @Test
    fun projectionFollowsCameraTurningRight() {
        val g = guideWithFirst()
        val pf = g.panoFrame!!
        val out = DoubleArray(3)
        // kamera otočená doprava o 20° → bod v smere 0° je vľavo od stredu
        val r = PanoFrame.cameraLookingAt(pf, 20.0, 0.0)
        assertTrue(SphereProjection.project(r, pf.dir(0.0, 0.0), fovH, fovV, 0.0, 0.0, 1080.0, 1440.0, out))
        assertTrue(out[0] < 540.0)
    }

    @Test
    fun lensFovFromSensor() {
        // 1/1.3" snímač ~ 9.8 x 7.4 mm, ohnisko 6.9 mm (hlavný 23 mm ekv.)
        val f = LensFov.fromSensor(6.9, 9.8, 7.4)
        assertNotNull(f)
        assertTrue(abs(f!!.hDeg - 56.0) < 6.0)
        assertTrue(f.vDeg > f.hDeg)
        // snímač 16:9 sa oreže na 4:3
        val wide = LensFov.fromSensor(6.9, 9.8, 5.5)
        assertNotNull(wide)
        assertEquals(wide!!.vDeg, Math.toDegrees(2 * Math.atan(5.5 * 4.0 / 3.0 / 2 / 6.9)), 0.01)
        assertNull(LensFov.fromSensor(0.0, 9.8, 7.4))
        // priblíženie 0,5 zdvojnásobí tangens polovice uhla
        val z = LensFov.withZoom(f, 0.5)
        assertEquals(2 * tan(Math.toRadians(f.hDeg / 2)), tan(Math.toRadians(z.hDeg / 2)), 1e-9)
    }
}
