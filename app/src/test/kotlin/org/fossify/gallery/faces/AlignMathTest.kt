package org.fossify.gallery.faces

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AlignMathTest {
    private val t = AlignMath.ARCFACE_112

    private fun mapAll(tr: FloatArray, pts: FloatArray): FloatArray {
        val out = FloatArray(pts.size)
        for (i in 0 until pts.size / 2) {
            val p = AlignMath.apply(tr, pts[i * 2], pts[i * 2 + 1])
            out[i * 2] = p[0]
            out[i * 2 + 1] = p[1]
        }
        return out
    }

    // body tváre vo fotke = šablóna otočená o [deg], zväčšená [scale]-krát a posunutá
    private fun photoPoints(deg: Double, scale: Float, dx: Float, dy: Float): FloatArray {
        val c = cos(Math.toRadians(deg)).toFloat()
        val s = sin(Math.toRadians(deg)).toFloat()
        return FloatArray(t.size) { i ->
            val x = t[i - i % 2]
            val y = t[i - i % 2 + 1]
            if (i % 2 == 0) scale * (c * x - s * y) + dx else scale * (s * x + c * y) + dy
        }
    }

    @Test
    fun templateMapsOntoItself() {
        val tr = AlignMath.similarity(t, t)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), tr, 1e-3f)
    }

    @Test
    fun recoversRotationScaleAndShift() {
        for (deg in listOf(-35.0, -10.0, 0.0, 17.0, 90.0)) {
            val src = photoPoints(deg, 4.5f, 812f, 233f)
            val tr = AlignMath.similarity(src, t)
            assertArrayEquals(t, mapAll(tr, src), 1e-2f)
            assertEquals(1f / 4.5f, AlignMath.scaleOf(tr), 1e-4f)
        }
    }

    @Test
    fun twoEyePointsMapExactlyOntoTemplateEyes() {
        val eyes = floatArrayOf(400f, 300f, 520f, 310f)
        val dst = t.copyOfRange(0, 4)
        val tr = AlignMath.similarity(eyes, dst)
        assertArrayEquals(dst, mapAll(tr, eyes), 1e-3f)
    }

    @Test
    fun noisyPointsGiveLeastSquaresFit() {
        val src = photoPoints(5.0, 3f, 100f, 50f)
        src[6] += 6f // posunutý kútik úst
        val tr = AlignMath.similarity(src, t)
        val mapped = mapAll(tr, src)
        // oči ostanú blízko šablóny aj s jedným chybným bodom
        for (i in 0..3) assertTrue(abs(mapped[i] - t[i]) < 1.5f)
    }

    @Test
    fun halvingsKeepLastStepAtMostTwoToOne() {
        assertEquals(0, AlignMath.halvings(1f))
        assertEquals(0, AlignMath.halvings(0.5f))
        assertEquals(1, AlignMath.halvings(0.49f))
        assertEquals(3, AlignMath.halvings(0.1f))
        assertEquals(0, AlignMath.halvings(0f))
        assertEquals(0, AlignMath.halvings(2f))
        for (s in listOf(0.03f, 0.07f, 0.2f, 0.33f)) {
            val k = AlignMath.halvings(s)
            val last = s * (1 shl k)
            assertTrue(last >= AlignMath.MIN_WARP_SCALE && last < 2 * AlignMath.MIN_WARP_SCALE)
        }
    }

    @Test
    fun scaledSourceMatrixMatchesOriginalTransform() {
        val tr = AlignMath.similarity(photoPoints(12.0, 6f, 40f, 70f), t)
        val m = AlignMath.forScaledSource(tr, 4f, 3.9f)
        val xh = 31f
        val yh = 52f
        val expected = AlignMath.apply(tr, xh * 4f, yh * 3.9f)
        assertEquals(expected[0], m[0] * xh + m[1] * yh + m[2], 1e-3f)
        assertEquals(expected[1], m[3] * xh + m[4] * yh + m[5], 1e-3f)
    }

    // syntetická sieť: všetky body v strede, oči a ústa na zadaných miestach
    private fun mesh(size: Int, irisR: FloatArray? = null, irisL: FloatArray? = null): Pair<FloatArray, FloatArray> {
        val xs = FloatArray(size) { 200f }
        val ys = FloatArray(size) { 200f }
        fun set(i: Int, x: Float, y: Float) {
            if (i < size) {
                xs[i] = x
                ys[i] = y
            }
        }
        set(AlignMath.RIGHT_EYE_OUTER, 100f, 100f)
        set(AlignMath.RIGHT_EYE_INNER, 140f, 104f)
        set(AlignMath.LEFT_EYE_INNER, 180f, 104f)
        set(AlignMath.LEFT_EYE_OUTER, 220f, 100f)
        set(AlignMath.NOSE_TIP, 160f, 150f)
        set(AlignMath.MOUTH_RIGHT, 125f, 190f)
        set(AlignMath.MOUTH_LEFT, 195f, 190f)
        irisR?.let { set(AlignMath.RIGHT_IRIS, it[0], it[1]) }
        irisL?.let { set(AlignMath.LEFT_IRIS, it[0], it[1]) }
        return xs to ys
    }

    @Test
    fun eyesFromIrisCentersWhenAvailable() {
        val (xs, ys) = mesh(478, floatArrayOf(121f, 101f), floatArrayOf(199f, 103f))
        val p = AlignMath.fivePoints(xs, ys)!!
        assertArrayEquals(floatArrayOf(121f, 101f, 199f, 103f), p.copyOfRange(0, 4), 1e-4f)
        // nos a kútiky úst v poradí šablóny (vľavo na obrázku najprv)
        assertArrayEquals(floatArrayOf(160f, 150f, 125f, 190f, 195f, 190f), p.copyOfRange(4, 10), 1e-4f)
    }

    @Test
    fun eyesFromCornerMidpointsWithoutIris() {
        val (xs, ys) = mesh(468)
        val p = AlignMath.fivePoints(xs, ys)!!
        // (33+133)/2 a (263+362)/2 — nie vonkajšie kútiky 33/263 ako predtým
        assertArrayEquals(floatArrayOf(120f, 102f, 200f, 102f), p.copyOfRange(0, 4), 1e-4f)
    }

    @Test
    fun implausibleIrisFallsBackToCorners() {
        // dúhovka ďaleko od oka (chyba siete) → priemer kútikov
        val (xs, ys) = mesh(478, floatArrayOf(60f, 160f), floatArrayOf(201f, 104f))
        val p = AlignMath.fivePoints(xs, ys)!!
        assertArrayEquals(floatArrayOf(120f, 102f, 201f, 104f), p.copyOfRange(0, 4), 1e-4f)
    }

    @Test
    fun tooFewLandmarks() {
        val (xs, ys) = mesh(291)
        assertNull(AlignMath.fivePoints(xs, ys))
    }

    @Test
    fun eyeCentersLandCloserToTemplateEyesThanOuterCorners() {
        // tvár presne podľa šablóny: stredy očí = body šablóny, kútiky ± 10 px
        val xs = FloatArray(478) { 56f }
        val ys = FloatArray(478) { 70f }
        fun set(i: Int, x: Float, y: Float) {
            xs[i] = x
            ys[i] = y
        }
        set(AlignMath.RIGHT_EYE_OUTER, t[0] - 10f, t[1])
        set(AlignMath.RIGHT_EYE_INNER, t[0] + 10f, t[1])
        set(AlignMath.LEFT_EYE_INNER, t[2] - 10f, t[3])
        set(AlignMath.LEFT_EYE_OUTER, t[2] + 10f, t[3])
        set(AlignMath.RIGHT_IRIS, t[0], t[1])
        set(AlignMath.LEFT_IRIS, t[2], t[3])
        set(AlignMath.NOSE_TIP, t[4], t[5])
        set(AlignMath.MOUTH_RIGHT, t[6], t[7])
        set(AlignMath.MOUTH_LEFT, t[8], t[9])
        val p = AlignMath.fivePoints(xs, ys)!!
        assertEquals(1f, AlignMath.scaleOf(AlignMath.similarity(p, t)), 1e-4f)
        // so starými bodmi (vonkajšie kútiky 33/263) by sa tvár v šablóne zmenšila
        val old = p.copyOf()
        old[0] = xs[AlignMath.RIGHT_EYE_OUTER]
        old[2] = xs[AlignMath.LEFT_EYE_OUTER]
        assertTrue(AlignMath.scaleOf(AlignMath.similarity(old, t)) < 0.95f)
    }

    @Test
    fun templateIsMirrorSymmetricForTta() {
        // zrkadlenie 112 px obrazu: x → 112 − x; páry oko/oko a kútik/kútik sa vymenia
        assertEquals(112f, t[0] + t[2], 0.5f)
        assertEquals(112f, t[6] + t[8], 0.5f)
        assertEquals(56f, t[4], 0.5f)
    }

    @Test
    fun ttaCombinationIsUnitLengthAndEqualWeight() {
        val a = floatArrayOf(3f, 0f, 4f)
        val b = floatArrayOf(0f, 10f, 0f)
        val c = AlignMath.combineTta(a, b)
        val norm = sqrt(c.sumOf { (it * it).toDouble() }).toFloat()
        assertEquals(1f, norm, 1e-5f)
        // rovnaká váha bez ohľadu na dĺžku vstupov: (0.6, 0, 0.8) + (0, 1, 0)
        val expected = AlignMath.l2normalize(floatArrayOf(0.6f, 1f, 0.8f))
        assertArrayEquals(expected, c, 1e-5f)
        assertArrayEquals(c, AlignMath.combineTta(b, a), 1e-6f)
        assertArrayEquals(c, AlignMath.combineTta(floatArrayOf(30f, 0f, 40f), b), 1e-6f)
    }

    @Test
    fun ttaOfIdenticalVectorsIsTheNormalizedVector() {
        val a = floatArrayOf(1f, 2f, 2f)
        assertArrayEquals(floatArrayOf(1f / 3, 2f / 3, 2f / 3), AlignMath.combineTta(a, a), 1e-6f)
        // nezhodná dĺžka (chyba modelu) → len normalizovaný prvý
        assertArrayEquals(AlignMath.l2normalize(a), AlignMath.combineTta(a, floatArrayOf(1f)), 1e-6f)
        // nulový vektor nespôsobí NaN
        assertTrue(AlignMath.combineTta(FloatArray(3), FloatArray(3)).none { it.isNaN() })
    }
}
