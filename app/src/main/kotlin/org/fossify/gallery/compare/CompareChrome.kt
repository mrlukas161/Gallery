package org.fossify.gallery.compare

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityComparatorBinding

// Stav spodnej lišty porovnávača (CompareChrome.bottom)
data class BarModel(
    val bin: Boolean = true,
    val markedCount: Int = 0,
    val markedBytes: Long = 0L,
    val restBytes: Long = 0L,
    val keepBestEnabled: Boolean = false,
    val deleteEnabled: Boolean = false,
    val sync: Boolean = true,
    val duelActive: Boolean = false,
    val duelMatch: Int = 0,
    val duelTotal: Int = 0,
    val gridAvailable: Boolean = false,
    val gridOn: Boolean = false,
    val infoOpen: Boolean = false,
    val hasNextGroup: Boolean = false,
)

// „Rám“ obrazovky porovnania: farby líšt, rozloženie (na výšku pás dole a panely podľa tvaru, na šírku pás
// vpravo), titulok, spodná lišta na dosah palca, priebeh analýzy, lišta „Späť“ a jednorazový tip.
class CompareChrome(private val activity: Activity, private val b: ActivityComparatorBinding) {
    // panely pod sebou (true) / vedľa seba (false), null = ešte neurčené
    var stacked: Boolean? = null
        private set

    // horná a dolná lišta v akcente témy; text a ikony v kontrastnej farbe
    fun colors() {
        val barColor = activity.getProperPrimaryColor()
        val fg = barColor.getContrastColor()
        b.compareTopbar.setBackgroundColor(barColor)
        b.compareBottombar.setBackgroundColor(barColor)
        listOf(
            b.compareTitle, b.compareSubtitle, b.compareSelectionDone, b.compareProgressText,
            b.compareTipText, b.compareDuelText, b.compareDuelEnd, b.compareNextGroup,
        ).forEach { it.setTextColor(fg) }
        listOf(
            b.compareBack, b.compareMore, b.compareStripToggle, b.compareSync, b.compareDuel,
            b.compareGridToggle, b.compareInfo, b.compareTipClose,
        ).forEach { ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(fg)) }
    }

    fun layout(landscape: Boolean, stripVisible: Boolean, gridOn: Boolean) {
        val size = activity.resources.getDimensionPixelSize(R.dimen.compare_strip_size)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        b.compareContent.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        b.compareStrip.layoutParams = if (landscape) params(size, match) else params(match, size)
        (b.compareStrip.layoutManager as? LinearLayoutManager)?.orientation =
            if (landscape) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL
        b.compareStrip.visibility = if (stripVisible) View.VISIBLE else View.GONE
        b.compareControls.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        b.compareTools.layoutParams = LinearLayout.LayoutParams(if (landscape) wrap else match, wrap)
        b.compareActions.layoutParams = if (landscape) LinearLayout.LayoutParams(0, wrap, 1f) else params(match, wrap)
        b.compareGrid.visibility = if (gridOn) View.VISIBLE else View.GONE
        b.comparePanes.visibility = if (gridOn) View.GONE else View.VISIBLE
        stacked = null
    }

    // panely pod sebou alebo vedľa seba podľa priestoru a typického pomeru strán; true = rozloženie sa zmenilo
    fun arrange(aspect: Float): Boolean {
        val w = b.compareStage.width.toFloat()
        val h = b.compareStage.height.toFloat()
        if (w <= 0f || h <= 0f) return false
        val vertical = PaneMath.stacked(w, h, aspect)
        if (vertical == stacked) return false
        stacked = vertical
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        b.comparePanes.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        listOf(b.comparePaneA.root, b.comparePaneB.root).forEach {
            it.layoutParams = if (vertical) weighted(match, 0) else weighted(0, match)
        }
        val line = (DIVIDER_DP * activity.resources.displayMetrics.density).toInt()
        b.compareDivider.layoutParams = if (vertical) params(match, line) else params(line, match)
        return true
    }

    // titulok; vo výberovom režime podtitulok s počtom označených a „Hotovo“
    fun header(title: String, selectionCount: Int?) {
        b.compareTitle.text = title
        val sub = selectionCount?.let { activity.getString(R.string.pa_selection_title, it) }.orEmpty()
        b.compareSubtitle.text = sub
        b.compareSubtitle.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        b.compareSelectionDone.visibility = if (selectionCount != null) View.VISIBLE else View.GONE
    }

    fun bottom(m: BarModel) {
        b.compareKeepBest.isEnabled = m.keepBestEnabled
        b.compareKeepBest.text = activity.getString(
            if (m.bin) R.string.pa_keep_best_rest else R.string.pa_keep_best_rest_permanent,
            m.restBytes.formatSize(),
        )
        b.compareDelete.isEnabled = m.deleteEnabled
        b.compareDelete.text = when {
            m.markedCount == 0 -> activity.getString(if (m.bin) R.string.pa_bin_none else R.string.pa_delete_none)
            m.bin -> activity.getString(R.string.pa_bin_marked, m.markedCount, m.markedBytes.formatSize())
            else -> activity.getString(R.string.pa_delete_marked, m.markedCount, m.markedBytes.formatSize())
        }
        b.compareSync.setImageResource(if (m.sync) R.drawable.ic_compare_sync_on else R.drawable.ic_compare_sync_off)
        val syncText = activity.getString(if (m.sync) R.string.pa_sync_on else R.string.pa_sync_off)
        b.compareSync.contentDescription = syncText
        b.compareSync.tooltipText = syncText
        b.compareDuel.alpha = if (m.duelActive) 1f else INACTIVE_ALPHA
        b.compareGridToggle.visibility = if (m.gridAvailable) View.VISIBLE else View.GONE
        b.compareGridToggle.setImageResource(if (m.gridOn) R.drawable.ic_compare_panes else R.drawable.ic_compare_grid)
        b.compareInfo.alpha = if (m.infoOpen) 1f else INACTIVE_ALPHA
        b.compareNextGroup.visibility = if (m.hasNextGroup) View.VISIBLE else View.GONE
        b.compareDuelRow.visibility = if (m.duelActive) View.VISIBLE else View.GONE
        b.compareDuelText.text = activity.getString(R.string.pa_duel_status, m.duelMatch, m.duelTotal)
    }

    // „Vyhodnocujem 4 / 24…“; done >= total skryje riadok, waiting = čaká sa na model tvárí
    fun progress(done: Int, total: Int, waiting: Boolean = false) {
        b.compareProgressRow.visibility = if (done >= total && !waiting) View.GONE else View.VISIBLE
        b.compareProgress.max = total
        b.compareProgress.progress = done
        b.compareProgressText.text = if (waiting) {
            activity.getString(R.string.pa_waiting_faces)
        } else {
            activity.getString(R.string.pa_progress, done, total)
        }
    }

    // lišta „Späť“ po presune do Koša; null skryje
    fun undo(message: String?) {
        b.compareUndoText.text = message.orEmpty()
        b.compareUndoRow.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    // nápoveda len raz (nie v titulku)
    fun tipOnce() {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_TIP_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_TIP_SHOWN, true).apply()
        b.compareTipText.setText(R.string.pa_tip)
        b.compareTipRow.visibility = View.VISIBLE
        b.compareTipClose.setOnClickListener { b.compareTipRow.visibility = View.GONE }
    }

    private fun params(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun weighted(w: Int, h: Int) = LinearLayout.LayoutParams(w, h, 1f)

    private companion object {
        const val PREFS = "comparator"
        const val PREF_TIP_SHOWN = "tip_shown"
        const val INACTIVE_ALPHA = 0.55f
        const val DIVIDER_DP = 2
    }
}
