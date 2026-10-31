package org.fossify.gallery.compare

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.View
import com.alexvasilkov.gestures.GestureController
import com.alexvasilkov.gestures.State
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ComparePaneBinding
import org.fossify.gallery.family.RelBox
import java.io.File
import java.util.concurrent.ExecutorService

// Jeden panel porovnávača (A alebo B): načítanie fotky ~2× väčšej ako panel (strop MAX_BITMAP), priblíženie
// v relatívnych súradniciach (RelativeView — synchronizácia aj prepnutie fotky ukážu to isté miesto scény),
// ostrý výrez pri veľkom priblížení (SharpRegion), štítok a tlačidlá Ponechať / Do Koša / Táto je lepšia.
class ComparePane(
    private val activity: Activity,
    val pane: Pane,
    val views: ComparePaneBinding,
    executor: ExecutorService,
    private val listener: Listener,
) {
    interface Listener {
        fun onPaneState(pane: ComparePane)

        fun onPaneAction(pane: ComparePane, action: PaneAction)
    }

    enum class PaneAction { PREV, NEXT, KEEP, BIN, WIN, TAP }

    var path: String? = null
        private set

    // false = zmenu stavu spôsobila synchronizácia z druhého panela (neposiela sa späť)
    private var applying = false
    private var pending: RelativeView? = null
    private var lastFit = 0f
    private val sharp = SharpRegion(views.paneImage, views.paneSharp, executor)

    init {
        val name = activity.getString(if (pane == Pane.A) R.string.pa_pane_a else R.string.pa_pane_b)
        views.paneBadge.text = name
        views.paneBadge.setBackgroundColor(if (pane == Pane.A) COLOR_A else COLOR_B)
        views.panePrev.contentDescription = activity.getString(R.string.pa_prev_desc, name)
        views.paneNext.contentDescription = activity.getString(R.string.pa_next_desc, name)
        views.paneKeep.contentDescription = activity.getString(R.string.pa_keep_desc, name)
        views.paneBin.contentDescription = activity.getString(R.string.pa_to_bin_desc, name)
        views.paneWin.contentDescription = activity.getString(R.string.pa_duel_better_desc, name)
        mapOf(
            views.panePrev to PaneAction.PREV,
            views.paneNext to PaneAction.NEXT,
            views.paneKeep to PaneAction.KEEP,
            views.paneBin to PaneAction.BIN,
            views.paneWin to PaneAction.WIN,
            views.paneImage to PaneAction.TAP,
        ).forEach { (view, action) -> view.setOnClickListener { listener.onPaneAction(this, action) } }
        views.paneImage.controller.addOnStateChangeListener(object : GestureController.OnStateChangeListener {
            override fun onStateChanged(state: State) = onState()
        })
    }

    // keep = priblíženie, ktoré sa má zachovať (relatívne), null = celá fotka
    fun show(newPath: String, info: PhotoInfo?, keep: RelativeView?) {
        if (newPath == path && keep != null) return
        path = newPath
        views.paneImage.contentDescription = newPath.substringAfterLast('/')
        pending = keep?.takeIf { it.isZoomed }
        sharp.reset(newPath, info, orientationOf(info))
        val v = views.paneImage
        val screenHalf = activity.resources.displayMetrics.widthPixels / 2
        val side = (maxOf(v.width, v.height, screenHalf) * 2).coerceIn(MIN_BITMAP, MAX_BITMAP)
        Glide.with(activity).load(File(newPath))
            .override(side, side)
            .downsample(DownsampleStrategy.CENTER_INSIDE)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ) = false

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    v.post { applyPending() }
                    return false
                }
            })
            .into(v)
    }

    // údaje o fotke prišli neskôr (rozmery originálu a EXIF orientácia pre ostrý výrez)
    fun updateInfo(info: PhotoInfo?) {
        val p = path ?: return
        if (info?.path == p) sharp.reset(p, info, orientationOf(info))
    }

    // relatívny výrez (stred a priblíženie voči „celá fotka“)
    fun relativeView(): RelativeView {
        val s = views.paneImage.controller.settings
        if (!s.hasImageSize() || !s.hasViewportSize()) return pending ?: RelativeView.FIT
        val st = views.paneImage.controller.state
        return PaneMath.toRelative(
            s.viewportWidth.toFloat(), s.viewportHeight.toFloat(), s.imageWidth, s.imageHeight,
            AbsoluteView(st.x, st.y, st.zoom),
        )
    }

    // nastaví výrez (synchronizácia z druhého panela, obnova stavu); ak fotka ešte nie je, počká na ňu
    fun applyRelative(rel: RelativeView) {
        val c = views.paneImage.controller
        val s = c.settings
        if (!s.hasImageSize() || !s.hasViewportSize()) {
            pending = rel
            return
        }
        updateZoomLimits()
        val vw = s.viewportWidth.toFloat()
        val vh = s.viewportHeight.toFloat()
        val abs = PaneMath.toAbsolute(vw, vh, s.imageWidth, s.imageHeight, rel)
        applying = true
        try {
            c.state.set(abs.x, abs.y, abs.zoom, 0f)
            c.updateState()
        } finally {
            applying = false
        }
    }

    private fun applyPending() {
        val rel = pending ?: return
        pending = null
        applyRelative(rel)
    }

    private fun onState() {
        updateZoomLimits()
        sharp.onState()
        // kým fotka čaká na odložený výrez (načítava sa), druhý panel sa nesynchronizuje na „celú fotku“
        if (!applying && pending == null) listener.onPaneState(this)
    }

    // priblíženie v GestureViews je absolútne (px panela na px bitmapy) → limity podľa „celej fotky“
    private fun updateZoomLimits() {
        val s = views.paneImage.controller.settings
        if (!s.hasImageSize() || !s.hasViewportSize()) return
        val fit = PaneMath.fitZoom(s.viewportWidth.toFloat(), s.viewportHeight.toFloat(), s.imageWidth, s.imageHeight)
        if (fit == lastFit) return
        lastFit = fit
        s.maxZoom = fit * MAX_ZOOM
        s.doubleTapZoom = fit * DOUBLE_TAP_ZOOM
    }

    // štítok: info riadok, skóre so zdôvodnením, stav značky a režim (turnaj skryje Ponechať / Do Koša)
    fun bind(info: String, score: String, marked: Boolean, duel: Boolean, overlays: Boolean) {
        views.paneInfo.text = info
        views.paneScore.text = score
        views.paneScore.visibility = if (score.isEmpty()) View.GONE else View.VISIBLE
        views.paneTrash.visibility = if (marked) View.VISIBLE else View.GONE
        views.paneBorder.setBackgroundResource(
            when {
                marked -> R.drawable.border_compare_marked
                pane == Pane.A -> R.drawable.border_pane_left
                else -> R.drawable.border_pane_right
            },
        )
        views.paneKeep.setBackgroundResource(if (marked) R.drawable.bg_compare_chip else R.drawable.bg_compare_chip_on)
        views.paneBin.setBackgroundResource(if (marked) R.drawable.bg_compare_chip_bin else R.drawable.bg_compare_chip)
        val show = if (overlays) View.VISIBLE else View.GONE
        views.paneHeader.visibility = show
        views.panePrev.visibility = if (overlays && !duel) View.VISIBLE else View.GONE
        views.paneNext.visibility = views.panePrev.visibility
        views.paneKeep.visibility = if (duel) View.GONE else show
        views.paneBin.visibility = if (duel) View.GONE else show
        views.paneWin.visibility = if (duel) View.VISIBLE else View.GONE
    }

    fun release() {
        sharp.release()
        if (!activity.isDestroyed) Glide.with(activity).clear(views.paneImage)
    }

    private fun orientationOf(info: PhotoInfo?): RelBox.Transform =
        info?.let { RelBox.exifTransform(it.orientation) } ?: RelBox.Transform(0, false)

    private companion object {
        const val MIN_BITMAP = 1024
        const val MAX_BITMAP = 3000
        const val MAX_ZOOM = 12f
        const val DOUBLE_TAP_ZOOM = 4f
        const val COLOR_A = 0xFF1976D2.toInt()
        const val COLOR_B = 0xFFFB8C00.toInt()
    }
}
