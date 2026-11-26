package org.fossify.gallery.sphere

import org.fossify.gallery.spherecore.Mat3
import org.fossify.gallery.spherecore.PanoFrame
import org.fossify.gallery.spherecore.SphereGeo
import org.fossify.gallery.spherecore.Target
import org.fossify.gallery.spherecore.TargetLayout
import org.fossify.gallery.spherecore.Vec
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min

/** Rada pod krúžkom. */
enum class GuideHint { NONE, SLOWER, LEVEL, HOLD, MOVE_TO_TARGET, AIM_HORIZON }

/** Výsledok jedného kroku navádzania. `nearest` = index najbližšieho nenasnímaného cieľa, −1 = žiadny. */
data class GuideState(
    val hasFirstFrame: Boolean,
    val nearest: Int,
    val distanceDeg: Double,
    val aligned: Boolean,
    val holdProgress: Float,
    val shouldCapture: Boolean,
    val hint: GuideHint,
    /** Pred prvým záberom: mobil mieri približne na horizont a stojí. */
    val firstFrameReady: Boolean,
    val pitchDeg: Double,
)

/**
 * Stavový automat navádzania (čistý Kotlin, bez Androidu). Pred prvým záberom nie sú ciele;
 * po ňom sa z prvého záberu určí rám panorámy a ciele sa dajú nasnímať v ľubovoľnom poradí.
 */
class CaptureGuide(
    hFovDeg: Double,
    vFovDeg: Double,
    initialTargets: List<Target> = TargetCache.get(hFovDeg, vFovDeg),
) {
    var hFov = hFovDeg
        private set
    var vFov = vFovDeg
        private set
    var targets: List<Target> = initialTargets
        private set
    var panoFrame: PanoFrame? = null
        private set

    private var captured = BooleanArray(targets.size)
    private var dirs: List<DoubleArray> = emptyList()
    private val stack = ArrayList<Int>() // poradie záberov: index cieľa alebo −1 (ručný záber)
    private var holdStartMs = -1L
    private var capturing = false
    private var startR: Mat3? = null

    val hasFirstFrame: Boolean get() = panoFrame != null
    val capturedCount: Int get() = stack.size
    val total: Int get() = targets.size
    val remaining: List<Int> get() = targets.indices.filter { !captured[it] }
    val isComplete: Boolean get() = panoFrame != null && captured.all { it }
    val isCapturing: Boolean get() = capturing

    /** Vodorovný rad (horizont) je celý nasnímaný. */
    val horizonComplete: Boolean
        get() = panoFrame != null && targets.indices.all { targets[it].ring != 0 || captured[it] }

    fun isCaptured(i: Int) = captured[i]

    /** Svetový smer cieľa (až po prvom zábere). */
    fun targetDir(i: Int): DoubleArray = dirs[i]

    /** Zmena zorného poľa (objektív) je možná len pred prvým záberom. */
    fun setFov(hFovDeg: Double, vFovDeg: Double, newTargets: List<Target> = TargetCache.get(hFovDeg, vFovDeg)) {
        if (panoFrame != null) return
        hFov = hFovDeg
        vFov = vFovDeg
        targets = newTargets
        captured = BooleanArray(targets.size)
    }

    /** Prvý záber: určí stred panorámy a označí cieľ 0. */
    fun setFirstFrame(rCw: Mat3) {
        val pf = PanoFrame.fromFirstFrame(rCw)
        panoFrame = pf
        dirs = targets.map { pf.dir(it.yawDeg, it.pitchDeg) }
        captured = BooleanArray(targets.size)
        captured[0] = true
        stack.clear()
        stack.add(0)
        holdStartMs = -1L
        capturing = false
    }

    fun update(rCw: Mat3, angularSpeedRadS: Double, nowMs: Long): GuideState {
        val axis = SphereGeo.opticalAxis(rCw)
        val pitch = Math.toDegrees(asin(axis[2].coerceIn(-1.0, 1.0)))
        val still = angularSpeedRadS < STILL_RAD_S
        if (panoFrame == null) {
            val level = abs(pitch) < MAX_FIRST_PITCH_DEG
            val hint = when {
                !level -> GuideHint.AIM_HORIZON
                !still -> GuideHint.SLOWER
                else -> GuideHint.NONE
            }
            return GuideState(false, -1, 0.0, false, 0f, false, hint, level && still, pitch)
        }
        var nearest = -1
        var bestDot = -2.0
        for (i in targets.indices) {
            if (captured[i]) continue
            val d = Vec.dot(axis, dirs[i])
            if (d > bestDot) {
                bestDot = d
                nearest = i
            }
        }
        if (nearest < 0) return GuideState(true, -1, 0.0, false, 0f, false, GuideHint.NONE, false, pitch)

        val dist = Vec.angleDeg(axis, dirs[nearest])
        val posOk = dist <= ALIGN_DEG
        val rollOk = targets[nearest].isPole || abs(SphereGeo.rollDeg(rCw)) <= MAX_ROLL_DEG
        val aligned = posOk && rollOk
        var progress = 0f
        var trigger = false
        if (capturing) {
            holdStartMs = -1L
        } else if (aligned && still) {
            if (holdStartMs < 0) holdStartMs = nowMs
            progress = min(1f, (nowMs - holdStartMs).toFloat() / HOLD_MS)
            if (progress >= 1f) {
                trigger = true
                capturing = true
                holdStartMs = -1L
            }
        } else {
            holdStartMs = -1L
        }
        val hint = when {
            capturing -> GuideHint.HOLD
            posOk && !rollOk -> GuideHint.LEVEL
            posOk && !still -> GuideHint.SLOWER
            aligned && still -> GuideHint.HOLD
            dist <= NEAR_SLOWER_DEG && !still -> GuideHint.SLOWER
            else -> GuideHint.MOVE_TO_TARGET
        }
        return GuideState(true, nearest, dist, aligned, progress, trigger, hint, false, pitch)
    }

    /** Začiatok expozície (aj pri ručnom zábere): zapamätá natočenie a blokuje ďalšiu automatickú spúšť. */
    fun onCaptureStarted(rCw: Mat3) {
        startR = rCw
        capturing = true
    }

    /** Koniec expozície: vráti, o koľko stupňov sa mobil pohol; nad [MAX_MOVE_DEG] treba záber zahodiť. */
    fun onCaptureFinished(rCw: Mat3): Double {
        val s = startR
        capturing = false
        startR = null
        return if (s == null) 0.0 else (rCw * s.transpose()).angleDeg()
    }

    /** Záber sa nekonal (chyba, zahodený). */
    fun abortCapture() {
        capturing = false
        startR = null
        holdStartMs = -1L
    }

    /** Označí cieľ ako nasnímaný; −1 = ručný záber mimo cieľov. */
    fun markCaptured(index: Int) {
        if (index >= 0) captured[index] = true
        stack.add(index)
        capturing = false
        holdStartMs = -1L
    }

    /** Vráti späť posledný záber a vráti jeho cieľ (−1 = ručný), null ak nie je čo. Po prvom zábere zruší aj rám. */
    fun undoLast(): Int? {
        if (stack.isEmpty()) return null
        val e = stack.removeAt(stack.size - 1)
        if (e >= 0) captured[e] = false
        if (stack.isEmpty()) {
            panoFrame = null
            dirs = emptyList()
            captured = BooleanArray(targets.size)
        }
        abortCapture()
        return e
    }

    companion object {
        const val ALIGN_DEG = 3.0
        const val MAX_ROLL_DEG = 10.0
        const val STILL_RAD_S = 0.15
        const val HOLD_MS = 400f
        const val MAX_MOVE_DEG = 2.0
        const val MAX_FIRST_PITCH_DEG = 25.0
        const val NEAR_SLOWER_DEG = 10.0
    }
}

/**
 * Plynulé presúvanie natočenia k cieľu (rýchlosť ~45 °/s so spomalením pred cieľom).
 * Používa ho ukážkový režim [DemoOrientationSource] aj testy.
 */
class DemoSlewer(start: Mat3, private val rateDegPerS: Double = 45.0) {
    var r: Mat3 = start
        private set

    /** Posun o `dtS` sekúnd k cieľu (null = stáť); vráti uhlovú rýchlosť v rad/s. */
    fun step(target: Mat3?, dtS: Double): Double {
        if (target == null || dtS <= 0) return 0.0
        val delta = target * r.transpose()
        val w = delta.log()
        val angle = Vec.norm(w)
        if (angle < 1e-6) {
            r = target
            return 0.0
        }
        val rate = Math.toRadians(rateDegPerS)
        val maxStep = min(rate, max(angle * 4.0, 0.2)) * dtS
        if (angle <= maxStep) {
            r = target
            return angle / dtS
        }
        r = Mat3.exp(Vec.scale(w, maxStep / angle)) * r
        return maxStep / dtS
    }
}

/**
 * Rozloženie cieľov sa hľadá podľa pokrytia, čo na pomalšom zariadení trvá aj sekundy — výsledok sa
 * pamätá a aktivita ho počíta mimo hlavného vlákna.
 */
object TargetCache {
    private val map = java.util.concurrent.ConcurrentHashMap<Long, List<Target>>()

    private fun key(h: Double, v: Double) = Math.round(h * 100) * 1_000_000L + Math.round(v * 100)

    fun cached(h: Double, v: Double): List<Target>? = map[key(h, v)]

    fun get(h: Double, v: Double): List<Target> = map.getOrPut(key(h, v)) { TargetLayout.generate(h, v) }
}
