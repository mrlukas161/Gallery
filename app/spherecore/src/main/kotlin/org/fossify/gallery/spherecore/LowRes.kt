package org.fossify.gallery.spherecore

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import kotlin.math.abs
import kotlin.math.roundToInt

/** Záber vykreslený na nízkorozlíšené plátno (celá šírka, riadky y0 until y0+rows): obraz 8UC3 a váha 8U. */
internal class LowFrame(val idx: Int, val y0: Int, val rows: Int, val img: Mat, val wgt: Mat) {
    fun release() { img.release(); wgt.release() }
}

/** Zisk jasu na záber (Brown–Lowe: najmenšie štvorce z priemerných jasov v prekryvoch). */
internal object GainCompensator {
    const val SIGMA_N = 10.0
    const val SIGMA_G = 0.1
    const val W_THR = 13.0

    fun solve(frames: List<LowFrame>, lw: Int, cancel: () -> Unit): DoubleArray {
        val n = frames.size
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        val sn2 = SIGMA_N * SIGMA_N
        val sg2 = SIGMA_G * SIGMA_G
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val fi = frames[i]
                val fj = frames[j]
                val ya = maxOf(fi.y0, fj.y0)
                val yb = minOf(fi.y0 + fi.rows, fj.y0 + fj.rows)
                if (yb - ya < 2) continue
                val ri = Rect(0, ya - fi.y0, lw, yb - ya)
                val rj = Rect(0, ya - fj.y0, lw, yb - ya)
                val m1 = Mat()
                val m2 = Mat()
                val wi = fi.wgt.submat(ri)
                val wj = fj.wgt.submat(rj)
                Core.compare(wi, Scalar(W_THR), m1, Core.CMP_GT)
                Core.compare(wj, Scalar(W_THR), m2, Core.CMP_GT)
                Core.bitwise_and(m1, m2, m1)
                val cnt = Core.countNonZero(m1)
                if (cnt >= 150) {
                    val mi = Core.mean(fi.img.submat(ri), m1)
                    val mj = Core.mean(fj.img.submat(rj), m1)
                    val ii = (mi.`val`[0] + mi.`val`[1] + mi.`val`[2]) / 3.0
                    val ij = (mj.`val`[0] + mj.`val`[1] + mj.`val`[2]) / 3.0
                    val nn = cnt.toDouble()
                    a[i][i] += nn * (ii * ii / sn2 + 1.0 / sg2)
                    a[j][j] += nn * (ij * ij / sn2 + 1.0 / sg2)
                    a[i][j] -= nn * ii * ij / sn2
                    a[j][i] -= nn * ii * ij / sn2
                    b[i] += nn / sg2
                    b[j] += nn / sg2
                }
                m1.release(); m2.release(); wi.release(); wj.release()
            }
            cancel()
        }
        // rámce bez prekryvov: zisk 1
        for (i in 0 until n) if (a[i][i] == 0.0) { a[i][i] = 1.0; b[i] = 1.0 }
        val g = gauss(a, b) ?: DoubleArray(n) { 1.0 }
        return DoubleArray(n) { g[it].coerceIn(0.5, 2.0) }
    }

    private fun gauss(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (abs(m[p][c]) < 1e-12) return null
            val t = m[p]; m[p] = m[c]; m[c] = t
            for (r in 0 until n) {
                if (r == c) continue
                val f = m[r][c] / m[c][c]
                if (f == 0.0) continue
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}

internal class SeamResult(val labels: IntArray, val mismatch: Double, val optimizedFrames: Int, val maxNodes: Int)

/** Švy na nízkorozlíšenom plátne: štart podľa geometrických váh, voliteľne rez grafom pre každý pridaný záber. */
internal object SeamFinder {
    const val W_THR = 13 // ≈ 5 % váhy
    private const val BIG = 1_000_000
    private const val LAMBDA = 16.0

    fun frameBytes(f: LowFrame, lw: Int, gain: Double): Pair<ByteArray, ByteArray> {
        val img = ByteArray(f.rows * lw * 3)
        f.img.get(0, 0, img)
        if (abs(gain - 1.0) > 1e-4) {
            val lut = IntArray(256) { (it * gain).roundToInt().coerceIn(0, 255) }
            for (k in img.indices) img[k] = lut[img[k].toInt() and 0xFF].toByte()
        }
        val w = ByteArray(f.rows * lw)
        f.wgt.get(0, 0, w)
        return img to w
    }

    fun run(
        frames: List<LowFrame>, gains: DoubleArray, lw: Int, lh: Int, optimize: Boolean,
        cancel: () -> Unit, progress: (Float) -> Unit,
    ): SeamResult {
        val total = lw * lh
        val lab = IntArray(total) { -1 }
        val comp = ByteArray(total * 3)
        val ownW = IntArray(total)
        val bestW = IntArray(total)
        val bestIdx = IntArray(total) { -1 }
        var optimized = 0
        var maxNodes = 0
        for ((fi, f) in frames.withIndex()) {
            cancel()
            val (img, wg) = frameBytes(f, lw, gains[fi])
            for (y in 0 until f.rows) {
                val base = (f.y0 + y) * lw
                for (x in 0 until lw) {
                    val wv = wg[y * lw + x].toInt() and 0xFF
                    if (wv > bestW[base + x]) { bestW[base + x] = wv; bestIdx[base + x] = f.idx }
                }
            }
            if (optimize) {
                val nodes = addFrame(f, fi, img, wg, lw, lh, lab, comp, ownW, cancel)
                if (nodes > 0) optimized++
                maxNodes = maxOf(maxNodes, nodes)
            }
            progress((fi + 1f) / frames.size)
        }
        if (!optimize) {
            for (p in 0 until total) lab[p] = bestIdx[p].let { if (bestW[p] > 0) it else -1 }
        } else {
            // zvyšné body, kde je záber platný len okrajovo
            for (p in 0 until total) if (lab[p] < 0 && bestW[p] > 0) lab[p] = bestIdx[p]
        }
        // labels sú indexy záberov v zozname (idx), nie pozície
        val mismatch = mismatch(frames, gains, lw, lh, lab)
        return SeamResult(lab, mismatch, optimized, maxNodes)
    }

    /** Pridá záber k zloženému obrazu: rez grafom v jeho prekryve. Vráti počet uzlov grafu. */
    private fun addFrame(
        f: LowFrame, fi: Int, img: ByteArray, wg: ByteArray, lw: Int, lh: Int,
        lab: IntArray, comp: ByteArray, ownW: IntArray, cancel: () -> Unit,
    ): Int {
        val rows = f.rows
        // stav: 0 nič, 1 len staré, 2 len nové, 3 oboje
        val state = ByteArray(rows * lw)
        val node = IntArray(rows * lw) { -1 }
        var nn = 0
        for (y in 0 until rows) for (x in 0 until lw) {
            val p = (f.y0 + y) * lw + x
            val valid = (wg[y * lw + x].toInt() and 0xFF) >= W_THR
            val old = lab[p] >= 0
            val s = if (valid && old) 3 else if (valid) 2 else if (old) 1 else 0
            state[y * lw + x] = s.toByte()
            if (s == 3) node[y * lw + x] = nn++
        }
        fun stateAt(x: Int, yAbs: Int): Int {
            if (yAbs < 0 || yAbs >= lh) return 0
            val yr = yAbs - f.y0
            if (yr in 0 until rows) return state[yr * lw + x].toInt()
            return if (lab[yAbs * lw + x] >= 0) 1 else 0
        }
        if (nn > 0) {
            val capS = IntArray(nn)
            val capT = IntArray(nn)
            val d = IntArray(nn) // rozdiel farieb starý/nový v uzle
            for (y in 0 until rows) for (x in 0 until lw) {
                val a = node[y * lw + x]
                if (a < 0) continue
                val p = (f.y0 + y) * lw + x
                var diff = 0
                for (c in 0..2) diff += abs((comp[p * 3 + c].toInt() and 0xFF) - (img[(y * lw + x) * 3 + c].toInt() and 0xFF))
                d[a] = diff // súčet cez 3 kanály
                val wo = ownW[p]
                val wn = wg[y * lw + x].toInt() and 0xFF
                val sum = (wo + wn + 1).toDouble()
                capT[a] += (LAMBDA * wo / sum).toInt()
                capS[a] += (LAMBDA * wn / sum).toInt()
            }
            val mf = MaxFlow(nn + 2, nn * 2 + 16)
            val S = nn
            val T = nn + 1
            for (y in 0 until rows) {
                if ((y and 63) == 0) cancel()
                for (x in 0 until lw) {
                    val a = node[y * lw + x]
                    if (a < 0) continue
                    val yAbs = f.y0 + y
                    // susedia: vľavo/vpravo (s obalením), hore/dole
                    for (dir in 0..3) {
                        val qx: Int
                        val qy: Int
                        when (dir) {
                            0 -> { qx = (x + 1) % lw; qy = yAbs }
                            1 -> { qx = (x - 1 + lw) % lw; qy = yAbs }
                            2 -> { qx = x; qy = yAbs + 1 }
                            else -> { qx = x; qy = yAbs - 1 }
                        }
                        if (qy < 0 || qy >= lh) continue
                        val st = stateAt(qx, qy)
                        when (st) {
                            1 -> capT[a] += BIG // sused má len staré: tu musí ostať staré
                            2 -> capS[a] += BIG // sused má len nové: tu musí byť nové
                            3 -> if (dir == 0 || dir == 2) {
                                val b = node[(qy - f.y0) * lw + qx]
                                val m = (d[a] + d[b]) * 2 + 4
                                mf.addEdge(a, b, m, m)
                            }
                        }
                    }
                }
            }
            for (a in 0 until nn) {
                if (capS[a] > 0) mf.addEdge(S, a, capS[a])
                if (capT[a] > 0) mf.addEdge(a, T, capT[a])
            }
            mf.run(S, T, cancel)
            val reach = mf.reachableFromSource(S)
            for (y in 0 until rows) for (x in 0 until lw) {
                val a = node[y * lw + x]
                if (a >= 0 && !reach[a]) continue
                val s = state[y * lw + x].toInt()
                if (s != 3 && s != 2) continue
                val p = (f.y0 + y) * lw + x
                lab[p] = f.idx
                ownW[p] = wg[y * lw + x].toInt() and 0xFF
                for (c in 0..2) comp[p * 3 + c] = img[(y * lw + x) * 3 + c]
            }
        } else {
            for (y in 0 until rows) for (x in 0 until lw) {
                if (state[y * lw + x].toInt() != 2) continue
                val p = (f.y0 + y) * lw + x
                lab[p] = f.idx
                ownW[p] = wg[y * lw + x].toInt() and 0xFF
                for (c in 0..2) comp[p * 3 + c] = img[(y * lw + x) * 3 + c]
            }
        }
        return nn
    }

    /** Stredný rozdiel farby (0..255, šedá) pozdĺž švov: pre dvojice susedných bodov s rôznymi štítkami. */
    private fun mismatch(frames: List<LowFrame>, gains: DoubleArray, lw: Int, lh: Int, lab: IntArray): Double {
        val pp = ArrayList<Int>()
        val qq = ArrayList<Int>()
        for (y in 0 until lh) for (x in 0 until lw) {
            val p = y * lw + x
            val a = lab[p]
            if (a < 0) continue
            val r = y * lw + (x + 1) % lw
            if (lab[r] >= 0 && lab[r] != a) { pp.add(p); qq.add(r) }
            if (y + 1 < lh) {
                val dn = p + lw
                if (lab[dn] >= 0 && lab[dn] != a) { pp.add(p); qq.add(dn) }
            }
        }
        val n = pp.size
        if (n == 0) return 0.0
        val gp = DoubleArray(n * 4) { Double.NaN } // A(p), A(q), B(p), B(q)
        for ((pos, f) in frames.withIndex()) {
            val (img, wg) = frameBytes(f, lw, gains[pos])
            fun gray(p: Int): Double {
                val y = p / lw - f.y0
                if (y < 0 || y >= f.rows) return Double.NaN
                val o = y * lw + p % lw
                if ((wg[o].toInt() and 0xFF) < W_THR) return Double.NaN
                return ((img[o * 3].toInt() and 0xFF) + (img[o * 3 + 1].toInt() and 0xFF) + (img[o * 3 + 2].toInt() and 0xFF)) / 3.0
            }
            for (k in 0 until n) {
                if (lab[pp[k]] == f.idx) { gp[k * 4] = gray(pp[k]); gp[k * 4 + 1] = gray(qq[k]) }
                if (lab[qq[k]] == f.idx) { gp[k * 4 + 2] = gray(pp[k]); gp[k * 4 + 3] = gray(qq[k]) }
            }
        }
        var sum = 0.0
        var cnt = 0
        for (k in 0 until n) {
            val a0 = gp[k * 4]; val a1 = gp[k * 4 + 1]; val b0 = gp[k * 4 + 2]; val b1 = gp[k * 4 + 3]
            if (a0.isNaN() || a1.isNaN() || b0.isNaN() || b1.isNaN()) continue
            sum += (abs(a0 - b0) + abs(a1 - b1)) / 2
            cnt++
        }
        return if (cnt == 0) 0.0 else sum / cnt
    }
}
