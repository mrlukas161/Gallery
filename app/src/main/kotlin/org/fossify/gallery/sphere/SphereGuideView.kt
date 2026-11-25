package org.fossify.gallery.sphere

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.fossify.gallery.spherecore.Mat3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Vrstva navádzania nad náhľadom kamery: krúžok v strede s priebehom držania, cieľové body v priestore,
 * šípka k najbližšiemu bodu mimo obrazovky, horizont a pripnuté zmenšeniny nasnímaných záberov.
 */
class SphereGuideView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private class Thumb(val key: String, val bmp: Bitmap, val rCw: Mat3, val hFov: Double, val vFov: Double)

    private val dp = resources.displayMetrics.density
    private var guide: CaptureGuide? = null
    private var state: GuideState? = null
    private var rCw: Mat3 = Mat3.identity()
    private var hFov = 58.0
    private var vFov = 73.0
    private var aspect = 3.0 / 4.0 // šírka / výška zobrazeného obrazu (na výšku)
    private val thumbs = ArrayList<Thumb>()

    private val rect = RectF()
    private val out = DoubleArray(3)
    private val matrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val arrowPath = Path()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f * dp; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#FF4CD964")
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = Color.argb(90, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(10f * dp, 8f * dp), 0f)
    }
    private val thumbPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 153 } // 60 %
    private val horizonPath = Path()
    private val horizonDirs: List<DoubleArray> = (0 until 90).map {
        val a = Math.toRadians(it * 4.0)
        doubleArrayOf(cos(a), sin(a), 0.0)
    }

    fun setGuide(g: CaptureGuide?) {
        guide = g
        invalidate()
    }

    /** Zorné pole aktuálneho objektívu (obraz na výšku). */
    fun setFov(h: Double, v: Double) {
        hFov = h
        vFov = v
        invalidate()
    }

    /** Pomer strán zobrazeného obrazu (šírka / výška), predvolene 3:4. */
    fun setImageAspect(a: Double) {
        if (a > 0.1) aspect = a
        invalidate()
    }

    /** Volá sa pri každej zmene natočenia; prekreslí sa najviac raz za snímku displeja. */
    fun update(r: Mat3, s: GuideState?) {
        rCw = r
        state = s
        postInvalidateOnAnimation()
    }

    fun addThumb(key: String, bmp: Bitmap, r: Mat3) {
        thumbs.add(Thumb(key, bmp, r, hFov, vFov))
        postInvalidateOnAnimation()
    }

    fun removeThumb(key: String) {
        val i = thumbs.indexOfFirst { it.key == key }
        if (i >= 0) thumbs.removeAt(i)
        postInvalidateOnAnimation()
    }

    fun clearThumbs() {
        thumbs.clear()
        postInvalidateOnAnimation()
    }

    /** Obdĺžnik, v ktorom sa zobrazuje obraz z kamery (FIT_CENTER). */
    private fun computeRect() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val rw: Float
        val rh: Float
        if (vw / vh < aspect) {
            rw = vw
            rh = (vw / aspect).toFloat()
        } else {
            rh = vh
            rw = (vh * aspect).toFloat()
        }
        rect.set((vw - rw) / 2, (vh - rh) / 2, (vw + rw) / 2, (vh + rh) / 2)
    }

    private fun proj(d: DoubleArray): Boolean =
        SphereProjection.project(
            rCw, d, hFov, vFov, rect.left.toDouble(), rect.top.toDouble(), rect.width().toDouble(), rect.height().toDouble(), out
        )

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        computeRect()
        val g = guide
        val st = state
        val now = System.currentTimeMillis()
        val cx = width / 2f
        val cy = height / 2f

        drawHorizon(canvas)
        drawThumbs(canvas)
        if (g != null && g.hasFirstFrame) drawTargets(canvas, g, st, now, cx, cy)
        drawReticle(canvas, st, cx, cy)
    }

    private fun drawHorizon(canvas: Canvas) {
        val pf = guide?.panoFrame
        horizonPath.reset()
        var pen = false
        val n = horizonDirs.size
        for (i in 0..n) {
            val h = horizonDirs[i % n]
            val d = if (pf != null) pf.dir(Math.toDegrees(atan2(h[1], h[0])), 0.0) else h
            if (proj(d)) {
                val x = out[0].toFloat()
                val y = out[1].toFloat()
                if (pen) horizonPath.lineTo(x, y) else horizonPath.moveTo(x, y)
                pen = true
            } else {
                pen = false
            }
        }
        canvas.drawPath(horizonPath, horizonPaint)
    }

    private fun drawThumbs(canvas: Canvas) {
        for (t in thumbs) {
            val tx = Math.tan(Math.toRadians(t.hFov / 2))
            val ty = Math.tan(Math.toRadians(t.vFov / 2))
            // rohy záberu v súradniciach jeho kamery: vľavo hore, vpravo hore, vpravo dole, vľavo dole
            val cs = arrayOf(
                doubleArrayOf(-tx, -ty, 1.0), doubleArrayOf(tx, -ty, 1.0),
                doubleArrayOf(tx, ty, 1.0), doubleArrayOf(-tx, ty, 1.0),
            )
            var ok = true
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (k in 0..3) {
                val w = t.rCw.mul(cs[k]) // smer vo svete (nenormalizovaný)
                val len = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
                val d = doubleArrayOf(w[0] / len, w[1] / len, w[2] / len)
                val c = rCw.mulT(d)
                if (c[2] <= 0.1) {
                    ok = false
                    break
                }
                proj(d)
                dst[k * 2] = out[0].toFloat()
                dst[k * 2 + 1] = out[1].toFloat()
                minX = min(minX, dst[k * 2]); maxX = max(maxX, dst[k * 2])
                minY = min(minY, dst[k * 2 + 1]); maxY = max(maxY, dst[k * 2 + 1])
            }
            if (!ok) continue
            if (maxX < 0 || minX > width || maxY < 0 || minY > height) continue
            val bw = t.bmp.width.toFloat()
            val bh = t.bmp.height.toFloat()
            src[0] = 0f; src[1] = 0f; src[2] = bw; src[3] = 0f; src[4] = bw; src[5] = bh; src[6] = 0f; src[7] = bh
            if (matrix.setPolyToPoly(src, 0, dst, 0, 4)) canvas.drawBitmap(t.bmp, matrix, thumbPaint)
        }
    }

    private fun drawTargets(canvas: Canvas, g: CaptureGuide, st: GuideState?, now: Long, cx: Float, cy: Float) {
        val nearest = st?.nearest ?: -1
        val pulse = 1f + 0.12f * sin(now / 180.0).toFloat()
        val nearCos = cos(Math.toRadians(25.0))
        var nearestVisible = false
        for (i in g.targets.indices) {
            val d = g.targetDir(i)
            val visible = proj(d)
            val x = out[0].toFloat()
            val y = out[1].toFloat()
            val inView = visible && x > -40 * dp && x < width + 40 * dp && y > -40 * dp && y < height + 40 * dp
            if (g.isCaptured(i)) {
                if (inView) {
                    fillPaint.color = Color.parseColor("#FF4CD964")
                    canvas.drawCircle(x, y, 5f * dp, fillPaint)
                }
                continue
            }
            if (i == nearest && inView) nearestVisible = true
            if (!inView) continue
            val isNearest = i == nearest
            val near = out[2] > nearCos
            val r = (if (isNearest) 20f * pulse else 14f) * dp
            if (near || isNearest) {
                fillPaint.color = Color.argb(if (isNearest) 130 else 90, 33, 150, 243)
                canvas.drawCircle(x, y, r, fillPaint)
            }
            ringPaint.color = Color.WHITE
            canvas.drawCircle(x, y, r, ringPaint)
        }
        if (nearest >= 0 && !nearestVisible) drawEdgeArrow(canvas, g.targetDir(nearest), cx, cy)
    }

    /** Šípka na okraji obrazovky smerom k bodu, ktorý je mimo záberu. */
    private fun drawEdgeArrow(canvas: Canvas, d: DoubleArray, cx: Float, cy: Float) {
        val c = rCw.mulT(d)
        var ang = atan2(c[1], c[0]) // x doprava, y dole
        if (Math.abs(c[0]) < 1e-6 && Math.abs(c[1]) < 1e-6) ang = Math.PI / 2
        val rx = (width / 2f) - 44f * dp
        val ry = (height / 2f) - 150f * dp
        val px = cx + (cos(ang) * rx).toFloat().coerceIn(-rx, rx)
        val py = cy + (sin(ang) * ry).toFloat().coerceIn(-ry, ry)
        val s = 16f * dp
        canvas.save()
        canvas.translate(px, py)
        canvas.rotate(Math.toDegrees(ang).toFloat())
        arrowPath.reset()
        arrowPath.moveTo(s, 0f)
        arrowPath.lineTo(-s * 0.7f, -s * 0.8f)
        arrowPath.lineTo(-s * 0.3f, 0f)
        arrowPath.lineTo(-s * 0.7f, s * 0.8f)
        arrowPath.close()
        fillPaint.color = Color.WHITE
        canvas.drawPath(arrowPath, fillPaint)
        canvas.restore()
    }

    private fun drawReticle(canvas: Canvas, st: GuideState?, cx: Float, cy: Float) {
        val r = 28f * dp
        val aligned = st?.aligned == true
        ringPaint.color = if (aligned) Color.parseColor("#FF4CD964") else Color.WHITE
        canvas.drawCircle(cx, cy, r, ringPaint)
        fillPaint.color = ringPaint.color
        canvas.drawCircle(cx, cy, 2.5f * dp, fillPaint)
        val p = st?.holdProgress ?: 0f
        if (p > 0f) {
            val rr = r + 7f * dp
            canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -90f, 360f * p, false, arcPaint)
        }
    }
}
