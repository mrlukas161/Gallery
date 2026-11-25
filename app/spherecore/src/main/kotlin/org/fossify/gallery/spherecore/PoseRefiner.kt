package org.fossify.gallery.spherecore

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

internal class RefineResult(
    val rot: Array<Mat3>,
    /** Násobok počiatočného ohniska (exp(s)). */
    val focalScale: Double,
    val rmsDeg: Double,
    val iterations: Int,
    val matchCount: Int,
    val initialRmsDeg: Double,
)

/**
 * Spoločné doladenie orientácií záberov a ohniska (Levenberg–Marquardt, husté normálové rovnice).
 * Parametre: pre každý voľný záber 3 (aditívna zmena rotácie sprava, exp mapa) + 1 (log mierky ohniska).
 * Rezíduum zhody: R_i·b_i − R_j·b_j, strata Huber; slabé väzby na orientácie zo senzorov a na počiatočné ohnisko.
 */
internal object PoseRefiner {
    const val SIGMA_MATCH_DEG = 0.2
    const val HUBER_DEG = 0.3
    const val PRIOR_SIGMA_DEG = 3.0
    const val FOCAL_SIGMA = 0.08

    fun refine(
        sens: Array<Mat3>,
        geoms: Array<FrameGeom>,
        fixed: Int,
        pairs: List<PairMatches>,
        cancel: () -> Unit,
        log: (String) -> Unit,
    ): RefineResult {
        val n = sens.size
        // spoločné pole zhôd
        var mc = 0
        for (p in pairs) mc += p.count
        val mi = IntArray(mc)
        val mj = IntArray(mc)
        val xy = DoubleArray(mc * 4)
        var o = 0
        for (p in pairs) for (k in 0 until p.count) {
            mi[o] = p.i; mj[o] = p.j
            for (c in 0..3) xy[4 * o + c] = p.xy[4 * k + c]
            o++
        }
        val idx = IntArray(n)
        var free = 0
        for (k in 0 until n) idx[k] = if (k == fixed) -1 else 3 * (free++)
        val P = 3 * free + 1
        val sIdx = 3 * free
        val sigM = Math.toRadians(SIGMA_MATCH_DEG)
        val hub = Math.toRadians(HUBER_DEG) / sigM
        val sigP = IntArray(0).let { DoubleArray(n) { Math.toRadians(PRIOR_SIGMA_DEG) } }

        var rot = Array(n) { sens[it] }
        var s = 0.0
        val H = Array(P) { DoubleArray(P) }
        val g = DoubleArray(P)

        // pomocné polia pre jednu zhodu
        val bI = DoubleArray(3)
        val bJ = DoubleArray(3)
        val dbI = DoubleArray(3)
        val dbJ = DoubleArray(3)
        val ji = DoubleArray(9)
        val jj = DoubleArray(9)
        val js = DoubleArray(3)

        fun bearingAndDeriv(gm: FrameGeom, x: Double, y: Double, f: Double, b: DoubleArray, db: DoubleArray) {
            val vx = x / f
            val vy = y / f
            val nr = sqrt(vx * vx + vy * vy + 1.0)
            b[0] = vx / nr; b[1] = vy / nr; b[2] = 1.0 / nr
            val dx = -vx
            val dy = -vy
            val bd = b[0] * dx + b[1] * dy
            db[0] = (dx - b[0] * bd) / nr
            db[1] = (dy - b[1] * bd) / nr
            db[2] = (0.0 - b[2] * bd) / nr
        }

        /** Spočíta cenu; ak build, naplní H a g (Gauss–Newton s IRLS váhami). */
        fun evaluate(r: Array<Mat3>, sv: Double, build: Boolean): Double {
            if (build) {
                for (a in 0 until P) { java.util.Arrays.fill(H[a], 0.0) }
                java.util.Arrays.fill(g, 0.0)
            }
            var cost = 0.0
            val es = exp(sv)
            val inv2 = 1.0 / (sigM * sigM)
            for (m in 0 until mc) {
                val i = mi[m]
                val j = mj[m]
                val fi = geoms[i].f * es
                val fj = geoms[j].f * es
                bearingAndDeriv(geoms[i], xy[4 * m], xy[4 * m + 1], fi, bI, dbI)
                bearingAndDeriv(geoms[j], xy[4 * m + 2], xy[4 * m + 3], fj, bJ, dbJ)
                val wi = r[i].mul(bI)
                val wj = r[j].mul(bJ)
                val rx = wi[0] - wj[0]; val ry = wi[1] - wj[1]; val rz = wi[2] - wj[2]
                val e = sqrt(rx * rx + ry * ry + rz * rz) / sigM
                val wgt: Double
                if (e <= hub) { cost += 0.5 * e * e; wgt = 1.0 } else { cost += hub * (e - 0.5 * hub); wgt = hub / e }
                if (!build) continue
                val ri = r[i].m
                val rj = r[j].m
                // Ji = −R_i [b_i]×, Jj = +R_j [b_j]×
                crossMat(ri, bI, ji, -1.0)
                crossMat(rj, bJ, jj, 1.0)
                val dwi = mulv(ri, dbI)
                val dwj = mulv(rj, dbJ)
                js[0] = dwi[0] - dwj[0]; js[1] = dwi[1] - dwj[1]; js[2] = dwi[2] - dwj[2]
                val res = doubleArrayOf(rx, ry, rz)
                val wv = wgt * inv2
                val a = idx[i]
                val b = idx[j]
                if (a >= 0) {
                    for (p in 0..2) {
                        var gv = 0.0
                        for (q in 0..2) gv += ji[q * 3 + p] * res[q]
                        g[a + p] += wv * gv
                        for (p2 in 0..2) {
                            var h = 0.0
                            for (q in 0..2) h += ji[q * 3 + p] * ji[q * 3 + p2]
                            H[a + p][a + p2] += wv * h
                        }
                    }
                }
                if (b >= 0) {
                    for (p in 0..2) {
                        var gv = 0.0
                        for (q in 0..2) gv += jj[q * 3 + p] * res[q]
                        g[b + p] += wv * gv
                        for (p2 in 0..2) {
                            var h = 0.0
                            for (q in 0..2) h += jj[q * 3 + p] * jj[q * 3 + p2]
                            H[b + p][b + p2] += wv * h
                        }
                    }
                }
                if (a >= 0 && b >= 0) {
                    for (p in 0..2) for (p2 in 0..2) {
                        var h = 0.0
                        for (q in 0..2) h += ji[q * 3 + p] * jj[q * 3 + p2]
                        H[a + p][b + p2] += wv * h
                        H[b + p2][a + p] += wv * h
                    }
                }
                for (p in 0..2) {
                    if (a >= 0) {
                        var h = 0.0
                        for (q in 0..2) h += ji[q * 3 + p] * js[q]
                        H[a + p][sIdx] += wv * h; H[sIdx][a + p] += wv * h
                    }
                    if (b >= 0) {
                        var h = 0.0
                        for (q in 0..2) h += jj[q * 3 + p] * js[q]
                        H[b + p][sIdx] += wv * h; H[sIdx][b + p] += wv * h
                    }
                }
                var hs = 0.0
                for (q in 0..2) hs += js[q] * js[q]
                H[sIdx][sIdx] += wv * hs
                g[sIdx] += wv * (js[0] * rx + js[1] * ry + js[2] * rz)
            }
            // väzby na senzory a ohnisko
            for (k in 0 until n) {
                if (idx[k] < 0) continue
                val p = (sens[k].transpose() * r[k]).log()
                val w = 1.0 / (sigP[k] * sigP[k])
                cost += 0.5 * w * (p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
                if (build) for (c in 0..2) { g[idx[k] + c] += w * p[c]; H[idx[k] + c][idx[k] + c] += w }
            }
            val wf = 1.0 / (FOCAL_SIGMA * FOCAL_SIGMA)
            cost += 0.5 * wf * sv * sv
            if (build) { g[sIdx] += wf * sv; H[sIdx][sIdx] += wf }
            return cost
        }

        fun rms(r: Array<Mat3>, sv: Double): Double {
            val es = exp(sv)
            var sum = 0.0
            var cnt = 0
            val lim = Math.toRadians(1.0)
            for (m in 0 until mc) {
                val i = mi[m]; val j = mj[m]
                bearingAndDeriv(geoms[i], xy[4 * m], xy[4 * m + 1], geoms[i].f * es, bI, dbI)
                bearingAndDeriv(geoms[j], xy[4 * m + 2], xy[4 * m + 3], geoms[j].f * es, bJ, dbJ)
                val wi = r[i].mul(bI); val wj = r[j].mul(bJ)
                val d2 = (wi[0] - wj[0]).let { it * it } + (wi[1] - wj[1]).let { it * it } + (wi[2] - wj[2]).let { it * it }
                if (d2 < lim * lim) { sum += d2; cnt++ }
            }
            return if (cnt == 0) Double.NaN else Math.toDegrees(sqrt(sum / cnt))
        }

        val initialRms = if (mc > 0) rms(rot, s) else Double.NaN
        if (mc == 0) return RefineResult(rot, 1.0, Double.NaN, 0, 0, initialRms)

        var cost = evaluate(rot, s, true)
        var mu = 1e-3
        var iter = 0
        var smallSteps = 0
        while (iter < 60) {
            cancel()
            iter++
            // LM krok
            var accepted = false
            var tries = 0
            while (tries < 12) {
                tries++
                val A = Array(P) { a -> DoubleArray(P) { b -> H[a][b] } }
                for (a in 0 until P) A[a][a] += mu * maxOf(H[a][a], 1e-9)
                val rhs = DoubleArray(P) { -g[it] }
                val dx = solveCholesky(A, rhs)
                if (dx == null) { mu *= 10; continue }
                val nr = Array(n) { k ->
                    if (idx[k] < 0) rot[k] else rot[k] * Mat3.exp(doubleArrayOf(dx[idx[k]], dx[idx[k] + 1], dx[idx[k] + 2]))
                }
                val ns = s + dx[sIdx]
                val nc = evaluate(nr, ns, false)
                if (nc < cost) {
                    val rel = (cost - nc) / maxOf(cost, 1e-12)
                    var mx = 0.0
                    for (v in dx) mx = maxOf(mx, abs(v))
                    rot = nr; s = ns
                    cost = evaluate(rot, s, true)
                    mu = maxOf(mu / 3, 1e-9)
                    accepted = true
                    if (rel < 1e-6 || mx < 1e-8) smallSteps++ else smallSteps = 0
                    break
                } else {
                    mu *= 4
                }
            }
            if (!accepted || smallSteps >= 2) break
        }
        val finalRms = rms(rot, s)
        log("LM: iter=$iter zhôd=$mc rms ${"%.3f".format(initialRms)}° → ${"%.3f".format(finalRms)}° ohnisko ×${"%.4f".format(exp(s))}")
        return RefineResult(rot, exp(s), finalRms, iter, mc, initialRms)
    }

    /** out = k · M · [b]× (M riadkovo 3×3). */
    private fun crossMat(m: DoubleArray, b: DoubleArray, out: DoubleArray, k: Double) {
        // [b]× = [[0,−bz,by],[bz,0,−bx],[−by,bx,0]]
        for (r in 0..2) {
            val m0 = m[r * 3]; val m1 = m[r * 3 + 1]; val m2 = m[r * 3 + 2]
            out[r * 3] = k * (m1 * b[2] - m2 * b[1])
            out[r * 3 + 1] = k * (-m0 * b[2] + m2 * b[0])
            out[r * 3 + 2] = k * (m0 * b[1] - m1 * b[0])
        }
    }

    private fun mulv(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    /** Rieši A x = b pre symetrickú pozitívne definitnú A (Cholesky); null ak nie je PD. */
    fun solveCholesky(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                var sum = a[i][j]
                for (k in 0 until j) sum -= l[i][k] * l[j][k]
                if (i == j) {
                    if (sum <= 1e-18) return null
                    l[i][i] = sqrt(sum)
                } else l[i][j] = sum / l[j][j]
            }
        }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var sum = b[i]
            for (k in 0 until i) sum -= l[i][k] * y[k]
            y[i] = sum / l[i][i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = y[i]
            for (k in i + 1 until n) sum -= l[k][i] * x[k]
            x[i] = sum / l[i][i]
        }
        return x
    }
}
