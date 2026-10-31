package org.fossify.gallery.compare

import android.app.Activity
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.views.MyTextView

// Zoznam akcií s vlastným titulkom — témovaný dialóg cez getAlertDialogBuilder + setupDialogStuff
// (rovnaký vzor ako PeopleActivity.showOptionsDialog, nie surový AlertDialog.Builder).
object CompareOptions {
    private const val TEXT_SP = 16f
    private const val PAD_NUM = 3
    private const val PAD_DEN = 4

    fun show(activity: Activity, title: String, options: List<String>, onPick: (Int) -> Unit) {
        val margin = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, margin / 2, 0, margin / 2)
        }
        val ripple = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        var dialog: AlertDialog? = null
        options.forEachIndexed { index, option ->
            holder.addView(
                MyTextView(activity).apply {
                    text = option
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
                    setPadding(margin, margin * PAD_NUM / PAD_DEN, margin, margin * PAD_NUM / PAD_DEN)
                    setBackgroundResource(ripple.resourceId)
                    setOnClickListener {
                        dialog?.dismiss()
                        onPick(index)
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val view = ScrollView(activity).apply { addView(holder) }
        activity.getAlertDialogBuilder().apply {
            activity.setupDialogStuff(view, this, titleText = title) { alertDialog -> dialog = alertDialog }
        }
    }
}
