package org.fossify.gallery.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FaceQualityTest {
    @Test
    fun sharpFrontalLargeFacePasses() {
        val q = FaceQuality.score(200f, 150f, 0f, 0f, AlignMethod.M5)
        assertEquals(1f, q, 1e-4f)
        assertTrue(FaceQuality.passes(q))
    }

    @Test
    fun profileFails() {
        val q = FaceQuality.score(200f, 150f, 60f, 0f, AlignMethod.M5)
        assertFalse("yaw 60° → $q", FaceQuality.passes(q))
        // mierne natočenie (do YAW_OK) bez trestu
        assertEquals(1f, FaceQuality.score(200f, 150f, -20f, 10f, AlignMethod.M5), 1e-4f)
    }

    @Test
    fun smallBlurryFaceFails() {
        val q = FaceQuality.score(30f, 15f, 0f, 0f, AlignMethod.M5)
        assertFalse("malá a rozmazaná → $q", FaceQuality.passes(q))
        // rovnako malá, ale ostrá tvár prejde (veľkosť sama nerozhoduje)
        assertTrue(FaceQuality.passes(FaceQuality.score(60f, 120f, 0f, 0f, AlignMethod.M5)))
    }

    @Test
    fun alignMethodOrdersScore() {
        val m5 = FaceQuality.score(80f, 60f, null, null, AlignMethod.M5)
        val m2 = FaceQuality.score(80f, 60f, null, null, AlignMethod.M2)
        val mr = FaceQuality.score(80f, 60f, null, null, AlignMethod.MR)
        assertTrue(m5 > m2 && m2 > mr)
    }

    @Test
    fun unknownQualityPasses() {
        assertTrue(FaceQuality.passes(null as Float?))
        assertFalse(FaceQuality.passes(FaceQuality.QUALITY_GATE - 0.01f))
        assertTrue(FaceQuality.passes(FaceQuality.QUALITY_GATE))
    }

    @Test
    fun laplacianFlatIsZeroAndEdgesAreSharp() {
        val n = 16
        val flat = IntArray(n * n) { 0xFF808080.toInt() }
        assertEquals(0f, FaceQuality.laplacianVariance(flat, n, n), 1e-6f)
        // šachovnica = maximálne hrany
        val checker = IntArray(n * n) { i -> if ((i % n + i / n) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val sharp = FaceQuality.laplacianVariance(checker, n, n)
        // jemný prechod (gradient) = málo hrán
        val gradient = IntArray(n * n) { i -> gray((i % n) * 255 / (n - 1)) }
        val soft = FaceQuality.laplacianVariance(gradient, n, n)
        assertTrue("šachovnica $sharp > gradient $soft", sharp > soft * 100)
        assertEquals(0f, FaceQuality.laplacianVariance(IntArray(4), 2, 2), 0f)
    }

    @Test
    fun poseFromFrontalTemplateIsNearZero() {
        val pose = FaceQuality.pose(AlignMath.ARCFACE_112)
        assertNotNull(pose)
        assertTrue("yaw ${pose!![0]}", abs(pose[0]) < 3f)
        assertTrue("pitch ${pose[1]}", abs(pose[1]) < 3f)
    }

    @Test
    fun poseRecoversYawAndIgnoresRollAndScale() {
        for (yaw in floatArrayOf(-40f, -15f, 15f, 30f, 45f)) {
            val p = FaceQuality.pose(syntheticPoints(yaw, rollDeg = 20f, scale = 3f))!!
            assertEquals("yaw $yaw", yaw, p[0], 3f)
        }
        // nezmyselné body (oči v jednom bode)
        assertEquals(null, FaceQuality.pose(FloatArray(10)))
    }

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    // 5 bodov šablóny ArcFace natočených o yaw (stupne) podľa modelu vo FaceQuality.pose (nos pred rovinou
    // očí a úst o NOSE_DEPTH polovice vzdialenosti očí), potom otočených o roll a zväčšených
    private fun syntheticPoints(yawDeg: Float, rollDeg: Float = 0f, scale: Float = 1f): FloatArray {
        val t = AlignMath.ARCFACE_112
        val ex = (t[0] + t[2]) / 2f
        val ey = (t[1] + t[3]) / 2f
        val half = (t[2] - t[0]) / 2f
        val yaw = Math.toRadians(yawDeg.toDouble())
        val roll = Math.toRadians(rollDeg.toDouble())
        val out = FloatArray(10)
        for (i in 0 until 5) {
            val x0 = t[2 * i] - ex
            val y = t[2 * i + 1] - ey
            val z = if (i == 2) half * FaceQuality.NOSE_DEPTH else 0f
            val x = (x0 * cos(yaw) + z * sin(yaw)).toFloat()
            out[2 * i] = ex + scale * (x * cos(roll) - y * sin(roll)).toFloat()
            out[2 * i + 1] = ey + scale * (x * sin(roll) + y * cos(roll)).toFloat()
        }
        return out
    }
}
