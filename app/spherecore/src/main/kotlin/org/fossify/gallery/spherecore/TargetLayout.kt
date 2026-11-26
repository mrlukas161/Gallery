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

    /** Najviac cieľov, ktoré generátor navrhne (okrem prípadu, keď inak zostanú diery). */
    const val MAX_TARGETS = 46

    private val cache = HashMap<String, List<Target>>()

    /**
     * Návrh cieľov: skúša sa niekoľko rozložení (prekryv v rade, počet radov, výška najvyššieho radu) a vyberie sa
     * to s najmenším počtom cieľov, ktoré bez dier pokryje guľu v strednom 92 % záberov aspoň dvojnásobne
     * na ≥ 95 % smerov (nutné pre spoľahlivé párovanie a prelínanie). Ak to do [MAX_TARGETS] nejde,
     * vyberie sa rozloženie s najväčším dvojitým pokrytím do tohto počtu.
     */
    fun generate(hFovDeg: Double, vFovDeg: Double): List<Target> {
        val h = hFovDeg.coerceIn(30.0, 130.0)
        val v = vFovDeg.coerceIn(30.0, 130.0)
        val key = "%.2f/%.2f".format(java.util.Locale.ROOT, h, v)
        synchronized(cache) { cache[key]?.let { return it } }
        val res = search(h, v)
        synchronized(cache) { cache[key] = res }
        return res
    }

    /** Rozloženie s pevnými parametrami: prekryv v rade, počet radov nad horizontom a výška najvyššieho radu. */
    fun build(h: Double, v: Double, overlapH: Double, rings: Int, topPitchDeg: Double): List<Target> {
        val out = ArrayList<Target>()
        fun addRing(pitch: Double, ring: Int, stagger: Boolean) {
            val n = ringCount(pitch, h, overlapH)
            val stepYaw = 360.0 / n
            val off = if (stagger) stepYaw / 2 else 0.0
            for (i in 0 until n) {
                var yaw = off + i * stepYaw
                if (yaw > 180.0) yaw -= 360.0
                out.add(Target(out.size, yaw, pitch, ring, false))
            }
        }
        addRing(0.0, 0, false)
        for (k in 1..rings) addRing(topPitchDeg * k / rings, k, k % 2 == 1)
        for (k in 1..rings) addRing(-topPitchDeg * k / rings, -k, k % 2 == 1)
        out.add(Target(out.size, 0.0, 90.0, POLE_RING, true))
        out.add(Target(out.size, 0.0, -90.0, -POLE_RING, true))
        return out
    }

    private val evalPano = PanoFrame(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))

    /** Skúšobné smery po celej guli (po 3°), v pevne zamiešanom poradí, aby sa diera našla čo najskôr. */
    private val evalDirs: DoubleArray by lazy {
        val d = ArrayList<DoubleArray>()
        var lat = -90.0
        while (lat <= 90.0 + 1e-9) {
            val n = if (kotlin.math.abs(lat) > 89.9) 1 else maxOf(1, Math.round(120.0 * cos(Math.toRadians(lat))).toInt())
            for (i in 0 until n) d.add(evalPano.dir(i * 360.0 / n + 1.3, lat))
            lat += 3.0
        }
        d.shuffle(java.util.Random(20261126L))
        val flat = DoubleArray(d.size * 3)
        for (i in d.indices) { flat[i * 3] = d[i][0]; flat[i * 3 + 1] = d[i][1]; flat[i * 3 + 2] = d[i][2] }
        flat
    }

    /**
     * Podiel smerov pokrytých aspoň dvakrát (centrálne 92 %) a počet nepokrytých smerov.
     * Vráti null, len čo je isté, že rozloženie nevyhovie: má dieru, alebo (ak je zadané `minTwo`)
     * dvojité pokrytie už nemôže dosiahnuť `minTwo`. Poradie smerov výsledok nemení.
     */
    private fun coverage(t: List<Target>, h: Double, v: Double, minTwo: Double = -1.0): Pair<Double, Int>? {
        val th = tan(Math.toRadians(h / 2)) * 0.92
        val tv = tan(Math.toRadians(v / 2)) * 0.92
        val nc = t.size
        val cams = DoubleArray(nc * 9)
        for (k in 0 until nc) {
            val m = PanoFrame.cameraLookingAt(evalPano, t[k].yawDeg, t[k].pitchDeg).m
            System.arraycopy(m, 0, cams, k * 9, 9)
        }
        val dirs = evalDirs
        val n = dirs.size / 3
        val maxNotTwo = if (minTwo < 0) n else n - ceil(minTwo * n - 1e-9).toInt()
        var two = 0
        var notTwo = 0
        var i = 0
        while (i < n) {
            val dx = dirs[i * 3]
            val dy = dirs[i * 3 + 1]
            val dz = dirs[i * 3 + 2]
            var c = 0
            var k = 0
            while (k < nc) {
                val o = k * 9
                val z = cams[o + 2] * dx + cams[o + 5] * dy + cams[o + 8] * dz
                if (z > 1e-6) {
                    val x = cams[o] * dx + cams[o + 3] * dy + cams[o + 6] * dz
                    if (kotlin.math.abs(x) <= th * z) {
                        val y = cams[o + 1] * dx + cams[o + 4] * dy + cams[o + 7] * dz
                        if (kotlin.math.abs(y) <= tv * z && ++c >= 2) break
                    }
                }
                k++
            }
            if (c == 0) return null
            if (c >= 2) two++ else if (++notTwo > maxNotTwo) return null
            i++
        }
        return two.toDouble() / n to 0
    }

    private fun search(h: Double, v: Double): List<Target> {
        // Kandidáti v pôvodnom poradí (prekryv, počet radov, výška). Rôzne prekryvy často dajú to isté
        // rozloženie (počet v rade je zaokrúhlený), preto sa každé rozloženie hodnotí len raz.
        val cands = ArrayList<List<Target>>()
        val seen = HashSet<String>()
        var oh = 0.30
        while (oh <= 0.601) {
            for (rings in 1..4) {
                var top = 30.0
                while (top <= 78.0 + 1e-9) {
                    val t = build(h, v, oh, rings, top)
                    if (t.size <= 80) {
                        val sig = StringBuilder().append(rings).append('/').append(Math.round(top * 10)).append('/').append(t.size)
                        for (k in 0..rings) sig.append(':').append(t.count { it.ring == k })
                        if (seen.add(sig.toString())) cands.add(t)
                    }
                    top += 3.0
                }
            }
            oh += 0.025
        }
        val full = HashMap<Int, Pair<Double, Int>?>()
        fun covFull(i: Int): Pair<Double, Int>? {
            if (!full.containsKey(i)) full[i] = coverage(cands[i], h, v)
            return full[i]
        }

        // 1. rýchla cesta: od najmenšieho počtu cieľov; prvý počet, pri ktorom niečo vyhovie, je výsledok
        //    (v rámci toho istého počtu vyhráva väčšie dvojité pokrytie, pri zhode skorší kandidát)
        val bySize = cands.indices.filter { cands[it].size <= MAX_TARGETS }.sortedBy { cands[it].size }
        var okIdx = -1
        var okTwo = 0.0
        var okSize = -1
        for (i in bySize) {
            val size = cands[i].size
            if (okIdx >= 0 && size > okSize) break
            val c = coverage(cands[i], h, v, 0.955) ?: continue
            full[i] = c
            val two = c.first
            if (two >= 0.955 && (okIdx < 0 || two > okTwo || (two == okTwo && i < okIdx))) {
                okIdx = i; okTwo = two; okSize = size
            }
        }
        if (okIdx >= 0) return cands[okIdx].mapIndexed { i, t -> t.copy(index = i) }

        // 2. nič nevyhovelo do MAX_TARGETS: najlepší kompromis (dvojité pokrytie mínus pokuta za počet nad limit)
        var best: List<Target>? = null
        var bestScore = -1e9
        var bestOk: List<Target>? = null
        var bestOkCount = Int.MAX_VALUE
        var bestOkTwo = 0.0
        for (i in cands.indices) {
            val t = cands[i]
            val two = (covFull(i) ?: continue).first
            if (two >= 0.955 && (t.size < bestOkCount || (t.size == bestOkCount && two > bestOkTwo))) {
                bestOk = t; bestOkCount = t.size; bestOkTwo = two
            }
            val score = (if (t.size <= MAX_TARGETS) 0.0 else -(t.size - MAX_TARGETS) * 0.05) + two
            if (score > bestScore) { bestScore = score; best = t }
        }
        val pick = best ?: bestOk ?: build(h, v, OVERLAP_H, 1, 50.0)
        return pick.mapIndexed { i, t -> t.copy(index = i) }
    }

    /** Počet záberov v rade s danou výškou: šírka záberu v azimute rastie s 1/cos(výška). */
    fun ringCount(pitchDeg: Double, hFovDeg: Double, overlapH: Double = OVERLAP_H): Int {
        val half = Math.toRadians(hFovDeg / 2)
        val c = cos(Math.toRadians(pitchDeg)).coerceAtLeast(0.05)
        val yawWidth = Math.toDegrees(2 * atan(tan(half) / c)).coerceAtMost(170.0)
        return ceil(360.0 / (yawWidth * (1 - overlapH))).toInt().coerceAtLeast(3)
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
