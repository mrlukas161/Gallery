package org.fossify.gallery.spherecore

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Kamera záberu v súradniciach panorámy: R_cp = svet→panoráma · R_cw; f a rozmery plného záberu. */
internal class FrameCam(val rCp: Mat3, val f: Double, val w: Int, val h: Int)

/** Obdĺžnik na plátne (stĺpce môžu byť v rozšírených súradniciach). */
internal class Roi(val x0: Int, val y0: Int, val w: Int, val h: Int)

internal object Warp {
    const val STEP = 8

    /**
     * Mapy plátno → zdrojový obraz pre výrez (x0,y0,w,h) rozšíreného plátna (stĺpce posunuté o offX).
     * Počíta sa na hrubej mriežke po STEP px a zväčší sa bilineárne. Súradnice sú indexy pixelov zdroja,
     * zdroj je zmenšený o `sc` oproti plnému záberu.
     */
    fun coarseMaps(cam: FrameCam, sc: Double, canvasW: Int, canvasH: Int, offX: Int, r: Roi, mapX: Mat, mapY: Mat) {
        val gw = r.w / STEP + 3
        val gh = r.h / STEP + 3
        val cx = FloatArray(gw * gh)
        val cy = FloatArray(gw * gh)
        val m = cam.rCp.m
        val fs = cam.f * sc
        val cxs = cam.w * sc / 2.0
        val cys = cam.h * sc / 2.0
        val sinLon = DoubleArray(gw)
        val cosLon = DoubleArray(gw)
        for (gx in 0 until gw) {
            val xc = r.x0 + STEP * gx - 4.0 - offX
            val lon = (xc / canvasW - 0.5) * 2 * Math.PI
            sinLon[gx] = sin(lon); cosLon[gx] = cos(lon)
        }
        for (gy in 0 until gh) {
            val yc = (r.y0 + STEP * gy - 4.0).coerceIn(0.0, canvasH.toDouble())
            val lat = (0.5 - yc / canvasH) * Math.PI
            val sl = sin(lat)
            val cl = cos(lat)
            for (gx in 0 until gw) {
                val dx = cl * sinLon[gx]
                val dy = sl
                val dz = cl * cosLon[gx]
                val c0 = m[0] * dx + m[3] * dy + m[6] * dz
                val c1 = m[1] * dx + m[4] * dy + m[7] * dz
                var c2 = m[2] * dx + m[5] * dy + m[8] * dz
                if (c2 < 1e-3) c2 = 1e-3
                val px = (fs * c0 / c2 + cxs - 0.5).coerceIn(-1e6, 1e6)
                val py = (fs * c1 / c2 + cys - 0.5).coerceIn(-1e6, 1e6)
                cx[gy * gw + gx] = px.toFloat()
                cy[gy * gw + gx] = py.toFloat()
            }
        }
        val cmx = Mat(gh, gw, CvType.CV_32F)
        val cmy = Mat(gh, gw, CvType.CV_32F)
        cmx.put(0, 0, cx)
        cmy.put(0, 0, cy)
        val bx = Mat()
        val by = Mat()
        Imgproc.resize(cmx, bx, Size((gw * STEP).toDouble(), (gh * STEP).toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.resize(cmy, by, Size((gw * STEP).toDouble(), (gh * STEP).toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val rect = Rect(8, 8, r.w, r.h)
        val sx = bx.submat(rect)
        val sy = by.submat(rect)
        sx.copyTo(mapX)
        sy.copyTo(mapY)
        sx.release(); sy.release()
        cmx.release(); cmy.release(); bx.release(); by.release()
    }

    /** 8U maska bodov, ktoré padajú do vnútra zdroja (šírka wS × výška hS). */
    fun validMask(mapX: Mat, mapY: Mat, wS: Int, hS: Int): Mat {
        val a = Mat()
        val b = Mat()
        val out = Mat()
        Core.compare(mapX, Scalar(0.0), out, Core.CMP_GE)
        Core.compare(mapX, Scalar(wS - 1.0), a, Core.CMP_LE)
        Core.bitwise_and(out, a, out)
        Core.compare(mapY, Scalar(0.0), a, Core.CMP_GE)
        Core.bitwise_and(out, a, out)
        Core.compare(mapY, Scalar(hS - 1.0), b, Core.CMP_LE)
        Core.bitwise_and(out, b, out)
        a.release(); b.release()
        return out
    }

    /** Geometrická váha (1−|xn|)(1−|yn|) v 0..255 (CV_8U). */
    fun weightMask(mapX: Mat, mapY: Mat, wS: Int, hS: Int): Mat {
        val cxs = wS / 2.0
        val cys = hS / 2.0
        val wx = axisWeight(mapX, cxs)
        val wy = axisWeight(mapY, cys)
        Core.multiply(wx, wy, wx)
        val out = Mat()
        wx.convertTo(out, CvType.CV_8U, 255.0)
        wx.release(); wy.release()
        return out
    }

    private fun axisWeight(map: Mat, c: Double): Mat {
        val t = Mat()
        Core.add(map, Scalar(0.5 - c), t)
        Core.multiply(t, Scalar(1.0 / c), t)
        val a = Mat()
        Core.absdiff(t, Scalar(0.0), a)
        Core.multiply(a, Scalar(-1.0), a)
        Core.add(a, Scalar(1.0), a)
        Core.max(a, Scalar(0.0), a)
        t.release()
        return a
    }

    fun remap(src: Mat, mapX: Mat, mapY: Mat): Mat {
        val dst = Mat()
        Imgproc.remap(src, dst, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))
        return dst
    }

    /**
     * Neplatné pixely dostanú odhad farby z okolia (normalizovaná konvolúcia na štvrtinovom rozlíšení),
     * aby nízkopásmové vrstvy pyramídy nepreberali čiernu z okraja záberu.
     */
    fun fillInvalid(img8: Mat, valid8: Mat, bands: Int) {
        val w = img8.cols()
        val h = img8.rows()
        if (w < 16 || h < 16) return
        val tmp = Mat.zeros(h, w, CvType.CV_8UC3)
        img8.copyTo(tmp, valid8)
        val t32 = Mat()
        tmp.convertTo(t32, CvType.CV_32FC3)
        tmp.release()
        val v32 = Mat()
        valid8.convertTo(v32, CvType.CV_32F, 1.0 / 255.0)
        val sw = maxOf(1, w / 4)
        val sh = maxOf(1, h / 4)
        val sa = Mat()
        val sb = Mat()
        Imgproc.resize(t32, sa, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.resize(v32, sb, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        t32.release(); v32.release()
        val sigma = maxOf(2.0, (1 shl bands.coerceAtLeast(1)) / 4.0)
        Imgproc.GaussianBlur(sa, sa, Size(0.0, 0.0), sigma)
        Imgproc.GaussianBlur(sb, sb, Size(0.0, 0.0), sigma)
        val sb3 = Mat()
        Core.merge(listOf(sb, sb, sb), sb3)
        Core.divide(sa, sb3, sa)
        val est = Mat()
        Imgproc.resize(sa, est, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val est8 = Mat()
        est.convertTo(est8, CvType.CV_8UC3)
        val inv = Mat()
        Core.bitwise_not(valid8, inv)
        est8.copyTo(img8, inv)
        sa.release(); sb.release(); sb3.release(); est.release(); est8.release(); inv.release()
    }

    /**
     * Výrezy plátna, v ktorých je záber platný: skenuje sa mriežka po `step` px.
     * `cols` = šírka rozšíreného plátna, `offX` = posun (okraj), výška `rowsLimit`. Vráti najviac pár výrezov
     * (oddelené súvislé bloky stĺpcov); výrezy sú zarovnané na násobky `align`.
     */
    fun findRois(cam: FrameCam, canvasW: Int, canvasH: Int, offX: Int, extW: Int, padH: Int, step: Int, align: Int, mergeSingle: Boolean = false): List<Roi> {
        val nx = extW / step + 1
        val ny = canvasH / step + 1
        val m = cam.rCp.m
        val tw = (cam.w / 2.0) / cam.f * 1.03
        val th = (cam.h / 2.0) / cam.f * 1.03
        val colAny = BooleanArray(nx)
        val colMin = IntArray(nx) { Int.MAX_VALUE }
        val colMax = IntArray(nx) { -1 }
        for (gx in 0 until nx) {
            val xc = gx * step - offX.toDouble()
            val lon = (xc / canvasW - 0.5) * 2 * Math.PI
            val sl0 = sin(lon)
            val cl0 = cos(lon)
            for (gy in 0 until ny) {
                val yc = (gy * step).toDouble().coerceAtMost(canvasH.toDouble())
                val lat = (0.5 - yc / canvasH) * Math.PI
                val dx = cos(lat) * sl0
                val dy = sin(lat)
                val dz = cos(lat) * cl0
                val c2 = m[2] * dx + m[5] * dy + m[8] * dz
                if (c2 <= 0.02) continue
                val c0 = m[0] * dx + m[3] * dy + m[6] * dz
                val c1 = m[1] * dx + m[4] * dy + m[7] * dz
                if (abs(c0 / c2) <= tw && abs(c1 / c2) <= th) {
                    colAny[gx] = true
                    if (gy < colMin[gx]) colMin[gx] = gy
                    if (gy > colMax[gx]) colMax[gx] = gy
                }
            }
        }
        val rois = ArrayList<Roi>()
        var gx = 0
        // bloky stĺpcov; medzera ≤ 2 uzly sa zlieva
        val runs = ArrayList<IntArray>()
        while (gx < nx) {
            if (!colAny[gx]) { gx++; continue }
            var e = gx
            var last = gx
            while (e < nx && e - last <= 2) { if (colAny[e]) last = e; e++ }
            runs.add(intArrayOf(gx, last))
            gx = last + 1
        }
        for (run in runs) {
            var lo = Int.MAX_VALUE
            var hi = -1
            for (k in run[0]..run[1]) if (colAny[k]) { lo = minOf(lo, colMin[k]); hi = maxOf(hi, colMax[k]) }
            var x0 = ((run[0] - 1) * step).coerceAtLeast(0)
            var x1 = ((run[1] + 2) * step).coerceAtMost(extW)
            var y0 = ((lo - 1) * step).coerceAtLeast(0)
            var y1 = ((hi + 2) * step).coerceAtMost(canvasH)
            x0 = (x0 / align) * align
            y0 = (y0 / align) * align
            x1 = ((x1 + align - 1) / align) * align
            y1 = ((y1 + align - 1) / align) * align
            x1 = minOf(x1, ((extW + align - 1) / align) * align)
            y1 = minOf(y1, padH)
            if (x1 > x0 && y1 > y0) rois.add(Roi(x0, y0, x1 - x0, y1 - y0))
        }
        return rois
    }

    /** Rozsah riadkov [y0, y1) plátna, kde je záber platný (pre nízke rozlíšenie, celá šírka). */
    fun rowRange(cam: FrameCam, canvasW: Int, canvasH: Int, step: Int): IntArray? {
        val rois = findRois(cam, canvasW, canvasH, 0, canvasW, canvasH, step, 1)
        if (rois.isEmpty()) return null
        var y0 = Int.MAX_VALUE
        var y1 = 0
        for (r in rois) { y0 = minOf(y0, r.y0); y1 = maxOf(y1, r.y0 + r.h) }
        return intArrayOf(y0, y1)
    }
}
