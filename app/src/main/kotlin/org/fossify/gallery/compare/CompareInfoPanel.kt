package org.fossify.gallery.compare

import android.app.Activity
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import org.fossify.gallery.R
import org.fossify.gallery.helpers.PhotoScore

// Spodný panel „Údaje vedľa seba“ (ⓘ) pre fotky v paneloch A a B: čas, rozlíšenie, MB, ISO, uzávierka,
// clona, ohnisko, fotoaparát, GPS, priečinok a skóre. Lepšia hodnota je zelená a tučná, odlišná tučná.
class CompareInfoPanel(private val activity: Activity, private val panel: ScrollView, private val table: TableLayout) {
    // open = false skryje panel; maxHeight = strop výšky (zvyšok sa posúva)
    fun update(open: Boolean, a: PhotoInfo?, b: PhotoInfo?, scores: Pair<PhotoScore?, PhotoScore?>, maxHeight: Int) {
        panel.visibility = if (open) View.VISIBLE else View.GONE
        if (!open) return
        fill(a, b, scores.first, scores.second)
        panel.layoutParams = panel.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        panel.post {
            if (maxHeight > 0 && panel.height > maxHeight) {
                panel.layoutParams = panel.layoutParams.apply { height = maxHeight }
            }
        }
    }

    private fun fill(a: PhotoInfo?, b: PhotoInfo?, scoreA: PhotoScore?, scoreB: PhotoScore?) {
        table.removeAllViews()
        row("", activity.getString(R.string.pa_pane_a), activity.getString(R.string.pa_pane_b), 0, header = true)
        if (a != null && b != null) {
            val rows = ExifCompare.rows(
                a,
                b,
                { CompareTexts.folderName(activity, it) },
                activity.getString(R.string.pa_yes),
                activity.getString(R.string.pa_no),
            )
            rows.forEach { row(label(it.field), it.a, it.b, it.better, differs = it.differs) }
        }
        if (scoreA != null && scoreB != null) {
            row(
                activity.getString(R.string.pa_info_score),
                "${scoreA.percent} %",
                "${scoreB.percent} %",
                scoreB.percent.compareTo(scoreA.percent),
                differs = scoreA.percent != scoreB.percent,
            )
        }
    }

    private fun row(
        label: String,
        va: String,
        vb: String,
        better: Int,
        differs: Boolean = false,
        header: Boolean = false,
    ) {
        val tr = TableRow(activity)
        tr.addView(cell(label, bold = header, color = LABEL))
        tr.addView(cell(va, bold = header || differs, color = if (better < 0) BETTER else VALUE))
        tr.addView(cell(vb, bold = header || differs, color = if (better > 0) BETTER else VALUE))
        table.addView(tr)
    }

    private fun cell(text: String, bold: Boolean, color: Int): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        gravity = Gravity.START
        val pad = (PAD_DP * activity.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun label(field: InfoField): String = activity.getString(
        when (field) {
            InfoField.TIME -> R.string.pa_info_time
            InfoField.RESOLUTION -> R.string.pa_info_resolution
            InfoField.SIZE -> R.string.pa_info_size
            InfoField.ISO -> R.string.pa_info_iso
            InfoField.SHUTTER -> R.string.pa_info_shutter
            InfoField.APERTURE -> R.string.pa_info_aperture
            InfoField.FOCAL -> R.string.pa_info_focal
            InfoField.CAMERA -> R.string.pa_info_camera
            InfoField.GPS -> R.string.pa_info_gps
            InfoField.FOLDER -> R.string.pa_info_folder
        },
    )

    private companion object {
        const val TEXT_SP = 13f
        const val PAD_DP = 6
        const val LABEL = 0xFFBDBDBD.toInt()
        const val VALUE = 0xFFFFFFFF.toInt()
        const val BETTER = 0xFF81C784.toInt()
    }
}
