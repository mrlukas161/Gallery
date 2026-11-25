package org.fossify.gallery.spherecore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class TargetLayoutTest {
    private val fovs = listOf(
        58.9 to 73.9, // hlavný objektív
        85.6 to 102.0, // širokouhlý
        45.0 to 60.0, 66.0 to 82.0, 50.0 to 65.0,
    )

    private val pano = PanoFrame(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))

    /** Je smer v centrálnych `frac` (po osiach, v normovaných súradniciach obrazu) záberu s rotáciou r? */
    private fun inside(r: Mat3, d: DoubleArray, h: Double, v: Double, frac: Double): Boolean {
        val c = r.mulT(d)
        if (c[2] <= 1e-6) return false
        val xn = c[0] / c[2] / tan(Math.toRadians(h / 2))
        val yn = c[1] / c[2] / tan(Math.toRadians(v / 2))
        return Math.abs(xn) <= frac && Math.abs(yn) <= frac
    }

    private fun dirs(stepDeg: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var lat = -90.0
        while (lat <= 90.0 + 1e-9) {
            val n = if (Math.abs(lat) > 89.9) 1 else maxOf(1, Math.round(360.0 / stepDeg * Math.cos(Math.toRadians(lat))).toInt()) // rovnomerne po ploche
            for (i in 0 until n) out.add(pano.dir(i * 360.0 / n, lat))
            lat += stepDeg
        }
        return out
    }

    @Test fun coverageForPortraitFovs() {
        val ds = dirs(2.5)
        for ((h, v) in fovs) {
            val targets = TargetLayout.generate(h, v)
            val cams = targets.map { PanoFrame.cameraLookingAt(pano, it.yawDeg, it.pitchDeg) }
            // pokrytie
            var uncovered = 0
            var twice = 0
            for (d in ds) {
                var cnt = 0
                for (r in cams) if (inside(r, d, h, v, 0.92)) cnt++
                if (cnt == 0) uncovered++
                if (cnt >= 2) twice++
            }
            val twiceFrac = twice.toDouble() / ds.size
            // prekryv každého záberu s ostatnými
            var minOverlap = 1.0
            var minIdx = -1
            for ((i, r) in cams.withIndex()) {
                var tot = 0
                var shared = 0
                for (d in ds) {
                    if (!inside(r, d, h, v, 1.0)) continue
                    tot++
                    for ((j, r2) in cams.withIndex()) if (j != i && inside(r2, d, h, v, 1.0)) { shared++; break }
                }
                val f = if (tot == 0) 0.0 else shared.toDouble() / tot
                if (f < minOverlap) { minOverlap = f; minIdx = i }
            }
            println("TargetLayout h=$h v=$v: n=${targets.size} uncovered=$uncovered dvojitéPokrytie=${"%.3f".format(twiceFrac)} minPrekryv=${"%.2f".format(minOverlap)} (cieľ $minIdx)")
            assertEquals("nepokryté smery pre $h×$v", 0, uncovered)
            assertTrue("dvojité pokrytie $twiceFrac pre $h×$v", twiceFrac >= 0.93)
            assertTrue("prekryv $minOverlap pre $h×$v", minOverlap >= 0.20)
            assertTrue("počet ${targets.size} pre $h×$v", targets.size <= 46)
            assertEquals(0.0, targets[0].yawDeg, 0.0)
            assertEquals(0.0, targets[0].pitchDeg, 0.0)
            for ((i, t) in targets.withIndex()) assertEquals(i, t.index)
        }
    }
}
