package org.fossify.gallery.spherecore

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Viacpásmové prelínanie (Burt–Adelson) na plátne s okrajom (obalenie 0°/360°).
 * Súčty Laplaceových pásiem × maska sú v CV_16S, súčty masiek v CV_16S (maska 0..256).
 * `bands = 0` = jednoduché plynulé prelínanie (jedna úroveň, rozmazaná maska).
 */
internal class Blender(val w: Int, val h: Int, val bands: Int, val margin: Int) {
    val levels = if (bands <= 0) 1 else bands + 1
    val align = maxOf(1 shl bands.coerceAtLeast(0), 8)
    val extW = w + 2 * margin
    val padW = roundUp(extW, align)
    val padH = roundUp(h, align)
    private val num = ArrayList<Mat>()
    private val den = ArrayList<Mat>()

    init {
        for (l in 0 until levels) {
            num.add(Mat.zeros(padH shr l, padW shr l, CvType.CV_16SC3))
            den.add(Mat.zeros(padH shr l, padW shr l, CvType.CV_16SC1))
        }
    }

    /** img8 = výrez (8UC3, platnosť už vyplnená), mask8 = 0/255 maska prideleného územia. */
    fun addRoi(img8: Mat, mask8: Mat, gain: Double, roi: Roi) {
        val img16 = Mat()
        img8.convertTo(img16, CvType.CV_16SC3, gain)
        val gp = ArrayList<Mat>()
        gp.add(img16)
        for (l in 1 until levels) {
            val d = Mat()
            Imgproc.pyrDown(gp[l - 1], d)
            gp.add(d)
        }
        for (l in 0 until levels - 1) {
            val up = Mat()
            Imgproc.pyrUp(gp[l + 1], up, Size(gp[l].cols().toDouble(), gp[l].rows().toDouble()))
            Core.subtract(gp[l], up, gp[l])
            up.release()
        }
        val m0 = Mat()
        if (bands <= 0) {
            val b = Mat()
            Imgproc.GaussianBlur(mask8, b, Size(0.0, 0.0), 4.0)
            b.convertTo(m0, CvType.CV_16S, 256.0 / 255.0)
            b.release()
        } else {
            mask8.convertTo(m0, CvType.CV_16S, 256.0 / 255.0)
        }
        var mcur = m0
        for (l in 0 until levels) {
            if (l > 0) {
                val d = Mat()
                Imgproc.pyrDown(mcur, d)
                mcur.release()
                mcur = d
            }
            val rect = Rect(roi.x0 shr l, roi.y0 shr l, roi.w shr l, roi.h shr l)
            val m3 = Mat()
            Core.merge(listOf(mcur, mcur, mcur), m3)
            val prod = Mat()
            Core.multiply(gp[l], m3, prod, 1.0 / 256.0, CvType.CV_16S)
            val ns = num[l].submat(rect)
            Core.add(ns, prod, ns)
            val ds = den[l].submat(rect)
            Core.add(ds, mcur, ds)
            ns.release(); ds.release(); m3.release(); prod.release()
            gp[l].release()
        }
        mcur.release()
    }

    class Output(val image: Mat, val rowCoverage: IntArray, val coveredPixels: Long)

    /** Normalizuje, zloží pyramídu, vyreže stĺpce bez okraja a riadky do výšky h. Uvoľní vnútorné matice. */
    fun finish(): Output {
        val cov = Mat()
        Core.compare(den[0], org.opencv.core.Scalar(0.0), cov, Core.CMP_GT)
        // delenie po pásmach, po riadkových blokoch
        for (l in 0 until levels) {
            val rows = num[l].rows()
            val cols = num[l].cols()
            val chunk = 128
            var y = 0
            while (y < rows) {
                val hh = minOf(chunk, rows - y)
                val r = Rect(0, y, cols, hh)
                val nc = num[l].submat(r)
                val dc = den[l].submat(r)
                val n32 = Mat()
                val d32 = Mat()
                nc.convertTo(n32, CvType.CV_32FC3)
                dc.convertTo(d32, CvType.CV_32F)
                val d3 = Mat()
                Core.merge(listOf(d32, d32, d32), d3)
                Core.divide(n32, d3, n32, 256.0)
                n32.convertTo(nc, CvType.CV_16SC3)
                nc.release(); dc.release(); n32.release(); d32.release(); d3.release()
                y += hh
            }
            den[l].release()
        }
        den.clear()
        var cur = num[levels - 1]
        for (l in levels - 2 downTo 0) {
            val up = Mat()
            Imgproc.pyrUp(cur, up, Size(num[l].cols().toDouble(), num[l].rows().toDouble()))
            cur.release()
            Core.add(up, num[l], up)
            num[l].release()
            cur = up
        }
        num.clear()
        val out8 = Mat()
        cur.convertTo(out8, CvType.CV_8UC3)
        cur.release()
        // čierna mimo pokrytia
        val inv = Mat()
        Core.bitwise_not(cov, inv)
        out8.setTo(org.opencv.core.Scalar(0.0, 0.0, 0.0), inv)
        inv.release()
        val crop = Rect(margin, 0, w, h)
        val res = Mat()
        val o8s = out8.submat(crop)
        o8s.copyTo(res)
        o8s.release()
        out8.release()
        val covCrop = Mat()
        val cvs = cov.submat(crop)
        cvs.copyTo(covCrop)
        cvs.release()
        cov.release()
        val ones = Mat()
        covCrop.convertTo(ones, CvType.CV_8U, 1.0 / 255.0)
        covCrop.release()
        val rs = Mat()
        Core.reduce(ones, rs, 1, Core.REDUCE_SUM, CvType.CV_32S)
        ones.release()
        val rc = IntArray(h)
        rs.get(0, 0, rc)
        rs.release()
        var tot = 0L
        for (v in rc) tot += v
        return Output(res, rc, tot)
    }

    fun release() {
        for (m in num) m.release()
        for (m in den) m.release()
        num.clear(); den.clear()
    }

    companion object {
        fun roundUp(v: Int, a: Int) = ((v + a - 1) / a) * a
    }
}
