package org.fossify.gallery.compare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.alexvasilkov.gestures.GestureImageView
import org.fossify.gallery.family.RelBox
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

// Ostré priblíženie panela: základná bitmapa má ~2× rozlíšenie panela; keď sa priblíži viac, než unesie,
// dočíta sa po ustálení (SETTLE_MS) viditeľný výrez v plnom rozlíšení cez BitmapRegionDecoder a vykreslí sa
// v [overlay] nad fotkou presne podľa stavu priblíženia (rovnaká matica ako GestureImageView). Pamäť stráži
// RegionMath.MAX_PIXELS (aj pri 50 Mpx); dekóder patrí jednému vláknu [executor].
class SharpRegion(
    private val image: GestureImageView,
    private val overlay: ImageView,
    private val executor: ExecutorService,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable { request() }
    private var path: String? = null
    private var uprightW = 0
    private var uprightH = 0
    private var transform = RelBox.Transform(0, false)
    private var plan: RegionPlan? = null
    private var bitmap: Bitmap? = null
    private var generation = 0
    private var released = false

    // len na vlákne executor
    private var decoder: BitmapRegionDecoder? = null
    private var decoderPath: String? = null

    fun reset(newPath: String?, info: PhotoInfo?, orientation: RelBox.Transform) {
        clear()
        generation++
        path = newPath
        uprightW = info?.width ?: 0
        uprightH = info?.height ?: 0
        transform = orientation
    }

    // volať pri každej zmene stavu panela: výrez sa posunie s fotkou, nový sa dočíta po ustálení
    fun onState() {
        if (released) return
        updateMatrix()
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    private fun request() {
        val p = path?.takeIf { !released } ?: return
        val s = image.controller.settings
        if (!s.hasImageSize() || !s.hasViewportSize()) return
        val state = image.controller.state
        val abs = AbsoluteView(state.x, state.y, state.zoom)
        val vw = s.viewportWidth.toFloat()
        val vh = s.viewportHeight.toFloat()
        val visible = PaneMath.visibleRect(vw, vh, s.imageWidth, s.imageHeight, abs)
        val want = RegionMath.plan(visible, s.imageWidth, s.imageHeight, uprightW, uprightH, state.zoom, transform)
        if (want == null) {
            clear()
            return
        }
        if (RegionMath.covers(plan, want)) return
        val gen = ++generation
        try {
            executor.execute {
                val bmp = decode(p, want)
                handler.post {
                    if (gen != generation || p != path || released) bmp?.recycle() else if (bmp != null) show(want, bmp)
                }
            }
        } catch (ignored: RejectedExecutionException) {
        }
    }

    // vlákno executor
    private fun decode(p: String, want: RegionPlan): Bitmap? = try {
        if (decoderPath != p) {
            decoder?.recycle()
            decoder = null
            decoderPath = p
            decoder = open(p)
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = want.sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rect = Rect(want.raw.left, want.raw.top, want.raw.right, want.raw.bottom)
        decoder?.decodeRegion(rect, opts)?.let { upright(it) }
    } catch (ignored: IOException) {
        null
    } catch (ignored: IllegalArgumentException) {
        null
    } catch (ignored: OutOfMemoryError) {
        null
    }

    @Suppress("DEPRECATION") // newInstance(String) je až od API 31, minSdk je 26
    private fun open(p: String): BitmapRegionDecoder? = BitmapRegionDecoder.newInstance(p, false)

    // surový výrez → vzpriamene (rovnako ako UprightDecoder: najprv zrkadlenie, potom otočenie)
    private fun upright(raw: Bitmap): Bitmap {
        if (transform.rotationDegrees == 0 && !transform.mirrored) return raw
        val m = Matrix()
        if (transform.mirrored) m.postScale(-1f, 1f)
        if (transform.rotationDegrees != 0) m.postRotate(transform.rotationDegrees.toFloat())
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    private fun show(newPlan: RegionPlan, bmp: Bitmap) {
        val old = bitmap
        bitmap = bmp
        plan = newPlan
        overlay.setImageBitmap(bmp)
        overlay.visibility = View.VISIBLE
        updateMatrix()
        old?.recycle()
    }

    private fun updateMatrix() {
        val p = plan ?: return
        val d = overlay.drawable ?: return
        val s = image.controller.settings
        if (uprightW <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        if (image.controller.state.zoom < RegionMath.MIN_ZOOM) {
            overlay.visibility = View.GONE
            return
        }
        // pixely bitmapy panela na pixel originálu
        val scale = s.imageWidth / uprightW
        val m = Matrix()
        image.controller.state.get(m)
        m.preTranslate(p.upright.left * scale, p.upright.top * scale)
        m.preScale(p.upright.width * scale / d.intrinsicWidth, p.upright.height * scale / d.intrinsicHeight)
        overlay.imageMatrix = m
        overlay.visibility = View.VISIBLE
    }

    fun clear() {
        overlay.setImageDrawable(null)
        overlay.visibility = View.GONE
        bitmap?.recycle()
        bitmap = null
        plan = null
    }

    // koniec panela (onDestroy): zruší čakajúce dočítanie a zavrie dekóder na jeho vlákne
    fun release() {
        released = true
        handler.removeCallbacks(settle)
        generation++
        clear()
        try {
            executor.execute {
                decoder?.recycle()
                decoder = null
                decoderPath = null
            }
        } catch (ignored: RejectedExecutionException) {
        }
    }

    private companion object {
        const val SETTLE_MS = 180L
    }
}
