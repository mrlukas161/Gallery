package org.fossify.gallery.spherecore

import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.CvType
import org.opencv.core.Core
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan

/** Príznaky jedného záberu; súradnice sú spojité v pixeloch PLNÉHO záberu (stred pixela = +0,5). */
internal class FrameFeat(val n: Int, val u: FloatArray, val v: FloatArray, val desc: FloatArray)

/** Rozmery a ohnisko záberu v pixeloch plného záberu. */
internal class FrameGeom(val w: Int, val h: Int, val f: Double) {
    val tanHalfW get() = (w / 2.0) / f
    val tanHalfH get() = (h / 2.0) / f
}

/** Zhody medzi záberom i a j: štvorice (xi, yi, xj, yj) v px vzhľadom na stred záberu. */
internal class PairMatches(val i: Int, val j: Int, val xy: DoubleArray, val count: Int, val raw: Int, val gated: Int, val ransacInliers: Int)

internal object Matching {
    const val RATIO = 0.75
    const val GATE_DEG = 8.0
    const val RANSAC_THR_DEG = 0.5
    const val MIN_INLIERS = 12
    const val MAX_KEPT = 150

    fun detect(gray: Mat, wFull: Int, hFull: Int, maxFeatures: Int = 3000): FrameFeat {
        val sift = SIFT.create(maxFeatures, 3, 0.04, 10.0, 1.6)
        val kp = MatOfKeyPoint()
        val desc = Mat()
        sift.detectAndCompute(gray, Mat(), kp, desc)
        val pts = kp.toArray()
        val n = pts.size
        if (n == 0 || desc.empty()) return FrameFeat(0, FloatArray(0), FloatArray(0), FloatArray(0))
        val sx = wFull.toDouble() / gray.cols()
        val sy = hFull.toDouble() / gray.rows()
        val u = FloatArray(n) { ((pts[it].pt.x + 0.5) * sx).toFloat() }
        val v = FloatArray(n) { ((pts[it].pt.y + 0.5) * sy).toFloat() }
        val d = FloatArray(n * 128)
        desc.get(0, 0, d)
        desc.release()
        kp.release()
        return FrameFeat(n, u, v, d)
    }

    fun bearing(g: FrameGeom, u: Double, v: Double): DoubleArray {
        val x = (u - g.w / 2.0) / g.f
        val y = (v - g.h / 2.0) / g.f
        val n = sqrt(x * x + y * y + 1.0)
        return doubleArrayOf(x / n, y / n, 1.0 / n)
    }

    fun matchPair(
        i: Int, j: Int, fi: FrameFeat, fj: FrameFeat, gi: FrameGeom, gj: FrameGeom, ri: Mat3, rj: Mat3,
    ): PairMatches? {
        if (fi.n < MIN_INLIERS || fj.n < MIN_INLIERS) return null
        // podmnožiny príznakov, ktoré padajú do záberu druhého (podľa senzorov)
        val si = subset(fi, gi, ri, gj, rj)
        val sj = subset(fj, gj, rj, gi, ri)
        if (si.size < MIN_INLIERS || sj.size < MIN_INLIERS) return null
        val mi = descMat(fi, si)
        val mj = descMat(fj, sj)
        val matcher = BFMatcher.create(Core.NORM_L2, false)
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(mi, mj, knn, 2)
        mi.release(); mj.release()
        val ca = ArrayList<DoubleArray>() // sveť. bearing i
        val cb = ArrayList<DoubleArray>()
        val px = ArrayList<DoubleArray>()
        var raw = 0
        val cosGate = cos(Math.toRadians(GATE_DEG))
        for (m in knn) {
            val arr = m.toArray()
            if (arr.size < 2) { m.release(); continue }
            if (arr[0].distance >= RATIO * arr[1].distance) { m.release(); continue }
            raw++
            val a = si[arr[0].queryIdx]
            val b = sj[arr[0].trainIdx]
            val ba = bearing(gi, fi.u[a].toDouble(), fi.v[a].toDouble())
            val bb = bearing(gj, fj.u[b].toDouble(), fj.v[b].toDouble())
            val wa = ri.mul(ba)
            val wb = rj.mul(bb)
            if (Vec.dot(wa, wb) < cosGate) { m.release(); continue }
            ca.add(wa); cb.add(wb)
            px.add(doubleArrayOf(fi.u[a] - gi.w / 2.0, fi.v[a] - gi.h / 2.0, fj.u[b] - gj.w / 2.0, fj.v[b] - gj.h / 2.0))
            m.release()
        }
        val m = ca.size
        if (m < MIN_INLIERS) return PairMatches(i, j, DoubleArray(0), 0, raw, m, 0)
        val a = DoubleArray(3 * m)
        val b = DoubleArray(3 * m)
        for (k in 0 until m) for (c in 0..2) { a[3 * k + c] = ca[k][c]; b[3 * k + c] = cb[k][c] }
        val inl = ransac(a, b, m, Math.toRadians(RANSAC_THR_DEG), Random(1234L + 31L * i + j)) ?: return PairMatches(i, j, DoubleArray(0), 0, raw, m, 0)
        if (inl.size < MIN_INLIERS) return PairMatches(i, j, DoubleArray(0), 0, raw, m, inl.size)
        val chosen = spread(inl, px, gi)
        val xy = DoubleArray(4 * chosen.size)
        for ((q, k) in chosen.withIndex()) for (c in 0..3) xy[4 * q + c] = px[k][c]
        return PairMatches(i, j, xy, chosen.size, raw, m, inl.size)
    }

    private fun subset(fa: FrameFeat, ga: FrameGeom, ra: Mat3, gb: FrameGeom, rb: Mat3): IntArray {
        val out = IntArray(fa.n)
        var c = 0
        val limX = gb.tanHalfW * 1.12
        val limY = gb.tanHalfH * 1.12
        for (k in 0 until fa.n) {
            val w = ra.mul(bearing(ga, fa.u[k].toDouble(), fa.v[k].toDouble()))
            val cb = rb.mulT(w)
            if (cb[2] < 0.05) continue
            if (abs(cb[0] / cb[2]) > limX || abs(cb[1] / cb[2]) > limY) continue
            out[c++] = k
        }
        return out.copyOf(c)
    }

    private fun descMat(f: FrameFeat, idx: IntArray): Mat {
        val d = FloatArray(idx.size * 128)
        for ((r, k) in idx.withIndex()) System.arraycopy(f.desc, k * 128, d, r * 128, 128)
        val m = Mat(idx.size, 128, CvType.CV_32F)
        m.put(0, 0, d)
        return m
    }

    /** Najviac [MAX_KEPT] rovnomerne rozložených zhôd (mriežka v obraze i). */
    private fun spread(inl: IntArray, px: List<DoubleArray>, g: FrameGeom): IntArray {
        if (inl.size <= MAX_KEPT) return inl
        val gx = 10
        val gy = 12
        val buckets = Array(gx * gy) { ArrayList<Int>() }
        for (k in inl) {
            val bx = (((px[k][0] + g.w / 2.0) / g.w) * gx).toInt().coerceIn(0, gx - 1)
            val by = (((px[k][1] + g.h / 2.0) / g.h) * gy).toInt().coerceIn(0, gy - 1)
            buckets[by * gx + bx].add(k)
        }
        val out = ArrayList<Int>()
        var round = 0
        while (out.size < MAX_KEPT) {
            var any = false
            for (b in buckets) {
                if (round < b.size && out.size < MAX_KEPT) { out.add(b[round]); any = true }
            }
            if (!any) break
            round++
        }
        return out.toIntArray()
    }

    /** RANSAC na rotáciu a→b (svetové jednotkové vektory); vráti indexy inlierov po spresnení Hornovou metódou. */
    fun ransac(a: DoubleArray, b: DoubleArray, m: Int, thr: Double, rnd: Random, iters: Int = 600): IntArray? {
        val cosThr = cos(thr)
        var best: IntArray? = null
        var bestCount = 0
        val minSep = cos(Math.toRadians(2.0))
        val tmp = IntArray(m)
        for (it in 0 until iters) {
            val k1 = rnd.nextInt(m)
            var k2 = rnd.nextInt(m)
            if (k1 == k2) continue
            val a1 = doubleArrayOf(a[3 * k1], a[3 * k1 + 1], a[3 * k1 + 2])
            val a2 = doubleArrayOf(a[3 * k2], a[3 * k2 + 1], a[3 * k2 + 2])
            val b1 = doubleArrayOf(b[3 * k1], b[3 * k1 + 1], b[3 * k1 + 2])
            val b2 = doubleArrayOf(b[3 * k2], b[3 * k2 + 1], b[3 * k2 + 2])
            val da = Vec.dot(a1, a2)
            val db = Vec.dot(b1, b2)
            if (da > minSep || db > minSep) continue
            if (abs(Math.acos(da.coerceIn(-1.0, 1.0)) - Math.acos(db.coerceIn(-1.0, 1.0))) > thr * 3) continue
            val q = frameMat(b1, b2) * frameMat(a1, a2).transpose()
            if (q.angleDeg() > 12.0) continue
            var cnt = 0
            val qm = q.m
            for (k in 0 until m) {
                val x = a[3 * k]; val y = a[3 * k + 1]; val z = a[3 * k + 2]
                val rx = qm[0] * x + qm[1] * y + qm[2] * z
                val ry = qm[3] * x + qm[4] * y + qm[5] * z
                val rz = qm[6] * x + qm[7] * y + qm[8] * z
                if (rx * b[3 * k] + ry * b[3 * k + 1] + rz * b[3 * k + 2] > cosThr) cnt++
            }
            if (cnt > bestCount) {
                bestCount = cnt
                var c = 0
                for (k in 0 until m) {
                    val x = a[3 * k]; val y = a[3 * k + 1]; val z = a[3 * k + 2]
                    val rx = qm[0] * x + qm[1] * y + qm[2] * z
                    val ry = qm[3] * x + qm[4] * y + qm[5] * z
                    val rz = qm[6] * x + qm[7] * y + qm[8] * z
                    if (rx * b[3 * k] + ry * b[3 * k + 1] + rz * b[3 * k + 2] > cosThr) tmp[c++] = k
                }
                best = tmp.copyOf(c)
            }
        }
        var inl = best ?: return null
        if (inl.size < 3) return inl
        // spresnenie: Hornova metóda na inlieroch + znovu spočítať inlierov
        for (r in 0 until 3) {
            val q = horn(a, b, inl) ?: break
            val qm = q.m
            var c = 0
            for (k in 0 until m) {
                val x = a[3 * k]; val y = a[3 * k + 1]; val z = a[3 * k + 2]
                val rx = qm[0] * x + qm[1] * y + qm[2] * z
                val ry = qm[3] * x + qm[4] * y + qm[5] * z
                val rz = qm[6] * x + qm[7] * y + qm[8] * z
                if (rx * b[3 * k] + ry * b[3 * k + 1] + rz * b[3 * k + 2] > cosThr) tmp[c++] = k
            }
            if (c < inl.size * 0.8) break
            inl = tmp.copyOf(c)
        }
        return inl
    }

    private fun frameMat(a1: DoubleArray, a2: DoubleArray): Mat3 {
        val e1 = Vec.normalize(a1)
        val e3 = Vec.normalize(Vec.cross(a1, a2))
        val e2 = Vec.cross(e3, e1)
        return Mat3.fromCols(e1, e2, e3)
    }

    /** Rotácia q minimalizujúca Σ|q·a − b|² (Horn, kvaternióny). */
    fun horn(a: DoubleArray, b: DoubleArray, idx: IntArray): Mat3? {
        var sxx = 0.0; var sxy = 0.0; var sxz = 0.0
        var syx = 0.0; var syy = 0.0; var syz = 0.0
        var szx = 0.0; var szy = 0.0; var szz = 0.0
        for (k in idx) {
            val ax = a[3 * k]; val ay = a[3 * k + 1]; val az = a[3 * k + 2]
            val bx = b[3 * k]; val by = b[3 * k + 1]; val bz = b[3 * k + 2]
            sxx += ax * bx; sxy += ax * by; sxz += ax * bz
            syx += ay * bx; syy += ay * by; syz += ay * bz
            szx += az * bx; szy += az * by; szz += az * bz
        }
        val n = arrayOf(
            doubleArrayOf(sxx + syy + szz, syz - szy, szx - sxz, sxy - syx),
            doubleArrayOf(syz - szy, sxx - syy - szz, sxy + syx, szx + sxz),
            doubleArrayOf(szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy),
            doubleArrayOf(sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz),
        )
        val q = maxEigenvector(n) ?: return null
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        return Mat3(
            doubleArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
                2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
                2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
            )
        )
    }

    /** Vlastný vektor najväčšej vlastnej hodnoty symetrickej 4×4 matice (Jacobiho metóda). */
    private fun maxEigenvector(s: Array<DoubleArray>): DoubleArray? {
        val n = 4
        val a = Array(n) { s[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { if (it == i) 1.0 else 0.0 } }
        for (sweep in 0 until 60) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += a[p][q] * a[p][q]
            if (off < 1e-24) break
            for (p in 0 until n - 1) for (q in p + 1 until n) {
                if (abs(a[p][q]) < 1e-30) continue
                val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1)
                val sn = t * c
                for (k in 0 until n) {
                    val akp = a[k][p]; val akq = a[k][q]
                    a[k][p] = c * akp - sn * akq
                    a[k][q] = sn * akp + c * akq
                }
                for (k in 0 until n) {
                    val apk = a[p][k]; val aqk = a[q][k]
                    a[p][k] = c * apk - sn * aqk
                    a[q][k] = sn * apk + c * aqk
                }
                for (k in 0 until n) {
                    val vkp = v[k][p]; val vkq = v[k][q]
                    v[k][p] = c * vkp - sn * vkq
                    v[k][q] = sn * vkp + c * vkq
                }
            }
        }
        var bi = 0
        for (i in 1 until n) if (a[i][i] > a[bi][bi]) bi = i
        val out = DoubleArray(n) { v[it][bi] }
        val nn = sqrt(out.sumOf { it * it })
        if (nn < 1e-12) return null
        return DoubleArray(n) { out[it] / nn }
    }

    @Suppress("unused")
    private fun tanDeg(d: Double) = tan(Math.toRadians(d))
}
