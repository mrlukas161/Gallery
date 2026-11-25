package org.fossify.gallery.spherecore

import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.tan

/** Jeden cieľový bod navádzania. `yawDeg` je voči prvému záberu (doprava kladný), `pitchDeg` hore kladný. */
data class Target(
    val index: Int,
    val yawDeg: Double,
    val pitchDeg: Double,
    /** 0 = horizont, kladné = vyššie rady, záporné = nižšie; póly majú ±99. */
    val ring: Int,
    val isPole: Boolean,
)

/**
 * Rozloženie cieľových bodov na guli pre dané zorné pole záberu na výšku.
 *
 * Pravidlá: susedné zábery v rade sa prekrývajú aspoň o [OVERLAP_H] šírky, rady nad sebou o [OVERLAP_V] výšky,
 * zenit a nadir majú vždy vlastný záber. Poradie: horizont (prvý cieľ = smer prvého záberu, ďalej doprava),
 * potom rady nad horizontom, pod horizontom, zenit, nadir. Susedné rady sú posunuté o pol kroku.
 */
object TargetLayout {
    const val OVERLAP_H = 0.40
    const val OVERLAP_V = 0.38
    /** O koľko stupňov musí najvyšší rad presahovať do záberu pólu, aby sa dali spojiť. */
    const val POLE_OVERLAP_DEG = 14.0
    const val MAX_RING_PITCH = 72.0
    const val POLE_RING = 99

    fun generate(hFovDeg: Double, vFovDeg: Double): List<Target> {
        val h = hFovDeg.coerceIn(30.0, 130.0)
        val v = vFovDeg.coerceIn(30.0, 130.0)
        val out = ArrayList<Target>()

        // rady nad horizontom (zrkadlovo pod ním)
        val ringPitches = ArrayList<Double>()
        ringPitches.add(0.0)
        val step = v * (1 - OVERLAP_V)
        val poleReach = 90.0 - minOf(h, v) / 2 + POLE_OVERLAP_DEG // po túto výšku musí siahať posledný rad
        var guard = 0
        while (worstTopDeg(ringPitches.last(), h, v) < poleReach && guard++ < 6) {
            val next = (ringPitches.last() + step).coerceAtMost(MAX_RING_PITCH)
            if (next - ringPitches.last() < 5.0) break
            ringPitches.add(next)
        }

        fun addRing(pitch: Double, ring: Int, stagger: Boolean) {
            val n = ringCount(pitch, h)
            val stepYaw = 360.0 / n
            val off = if (stagger) stepYaw / 2 else 0.0
            for (i in 0 until n) {
                var yaw = off + i * stepYaw
                if (yaw > 180.0) yaw -= 360.0
                out.add(Target(out.size, yaw, pitch, ring, false))
            }
        }

        addRing(0.0, 0, false)
        for (k in 1 until ringPitches.size) addRing(ringPitches[k], k, k % 2 == 1)
        for (k in 1 until ringPitches.size) addRing(-ringPitches[k], -k, k % 2 == 1)
        out.add(Target(out.size, 0.0, 90.0, POLE_RING, true))
        out.add(Target(out.size, 0.0, -90.0, -POLE_RING, true))
        return out
    }

    /** Počet záberov v rade s danou výškou: šírka záberu v azimute rastie s 1/cos(výška). */
    fun ringCount(pitchDeg: Double, hFovDeg: Double): Int {
        val half = Math.toRadians(hFovDeg / 2)
        val c = cos(Math.toRadians(pitchDeg)).coerceAtLeast(0.05)
        val yawWidth = Math.toDegrees(2 * atan(tan(half) / c)).coerceAtMost(170.0)
        return ceil(360.0 / (yawWidth * (1 - OVERLAP_H))).toInt().coerceAtLeast(3)
    }

    /**
     * Najnižšia hodnota, po ktorú rad s výškou `pitchDeg` ISTO siaha hore (v medzere medzi dvoma zábermi
     * je horný okraj nižšie než v strede záberu). Konzervatívny odhad.
     */
    fun worstTopDeg(pitchDeg: Double, hFovDeg: Double, vFovDeg: Double): Double {
        val n = ringCount(pitchDeg, hFovDeg)
        val halfGap = Math.toRadians(360.0 / n / 2)
        val halfV = Math.toRadians(vFovDeg / 2)
        // horný okraj záberu v strede: pitch + halfV; bokom od stredu klesá približne s cos(odchýlka)
        val topCenter = pitchDeg + vFovDeg / 2
        val drop = Math.toDegrees(halfV - atan(tan(halfV) * cos(halfGap)))
        return topCenter - drop * (1 + pitchDeg / 45.0)
    }
}
