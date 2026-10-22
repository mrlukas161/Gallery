package org.fossify.gallery.dialogs

import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyTextView
import org.fossify.gallery.R
import org.fossify.gallery.family.DeleteProposal
import org.fossify.gallery.family.FaceMoveProposal
import org.fossify.gallery.family.FaceRemoveProposal
import org.fossify.gallery.family.FamilyProposals
import org.fossify.gallery.family.MergeProposal
import org.fossify.gallery.family.RenameProposal
import org.fossify.gallery.family.SameNameProposal

// Zoznam návrhov z druhého telefónu (lišta v Ľuďoch). Ťuk na návrh ponúkne jeho voľby
// (prijať / ponechať / zlúčiť). Nevratné voľby (zlúčenie, odstránenie osoby) idú cez ConfirmationDialog.
// Výberový režim tu nie je: zoznam je v dialógu a hromadná voľba „Ponechať všetky“ nič nemení v Ľuďoch.
// onDecided sa zavolá na hlavnom vlákne po každom rozhodnutí (obnov zoznam osôb a lištu).
class FamilyProposalsDialog(
    private val activity: BaseSimpleActivity,
    private val items: List<FamilyProposals.Item>,
    private val names: Map<Long, String?>,
    private val onDecided: () -> Unit,
) {
    private var dialog: AlertDialog? = null

    init {
        val margin = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, margin / 2, 0, margin / 2)
        }
        if (items.isEmpty()) {
            holder.addView(row(activity.getString(R.string.fp_empty), margin))
        }
        items.forEach { item ->
            holder.addView(
                row(describe(item), margin) {
                    dialog?.dismiss()
                    showChoices(item)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }
        val builder = activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
        if (items.isNotEmpty()) {
            builder.setNeutralButton(R.string.fp_keep_all) { _, _ -> decide { it.keepAll(items) } }
        }
        activity.setupDialogStuff(ScrollView(activity).apply { addView(holder) }, builder, R.string.fp_list_title) {
            dialog = it
        }
    }

    // onClick = null → len text (prázdny zoznam, text v dialógu volieb)
    private fun row(text: String, margin: Int, onClick: (() -> Unit)? = null) = MyTextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
        val vertical = (margin * ROW_VERTICAL_PADDING).toInt()
        setPadding(margin, vertical, margin, vertical)
        if (onClick != null) {
            val ripple = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClick() }
        }
    }

    private fun name(localId: Long) = names[localId] ?: activity.getString(R.string.fp_unnamed_person)

    private fun describe(item: FamilyProposals.Item): String {
        val phone = item.phoneName ?: activity.getString(R.string.fp_unknown_phone)
        return when (val p = item.proposal) {
            is RenameProposal -> activity.getString(R.string.fp_rename, p.myName ?: name(p.localId), p.theirName, phone)
            is SameNameProposal -> activity.getString(R.string.fp_same_name, p.name, phone)
            is MergeProposal -> activity.getString(R.string.fp_merge, name(p.fromLocalId), name(p.toLocalId), phone)
            is FaceMoveProposal ->
                activity.getString(R.string.fp_face_move, name(p.fromLocalId), name(p.toLocalId), phone)

            is FaceRemoveProposal -> activity.getString(R.string.fp_face_remove, name(p.localId), phone)
            is DeleteProposal -> activity.getString(R.string.fp_delete, p.name ?: name(p.localId), phone)
        }
    }

    private fun showChoices(item: FamilyProposals.Item) {
        val p = item.proposal
        val acceptLabel = when (p) {
            is RenameProposal -> activity.getString(R.string.fp_take_theirs, p.theirName)
            is SameNameProposal, is MergeProposal -> activity.getString(R.string.fp_merge_action)
            is DeleteProposal -> activity.getString(R.string.fp_delete_here)
            else -> activity.getString(R.string.fp_accept)
        }
        val keepLabel = when (p) {
            is RenameProposal -> activity.getString(R.string.fp_keep_mine, p.myName ?: name(p.localId))
            is SameNameProposal, is MergeProposal -> activity.getString(R.string.fp_keep_separate)
            else -> activity.getString(R.string.fp_keep)
        }
        activity.getAlertDialogBuilder()
            .setPositiveButton(acceptLabel) { _, _ -> confirmAccept(item) }
            .setNegativeButton(keepLabel) { _, _ -> decide { it.keep(item) } }
            .apply {
                val margin = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
                activity.setupDialogStuff(row(describe(item), margin), this, R.string.fp_list_title)
            }
    }

    // zlúčenie a odstránenie osoby sú nevratné → ešte jedno potvrdenie
    private fun confirmAccept(item: FamilyProposals.Item) {
        val message = when (val p = item.proposal) {
            is MergeProposal -> activity.getString(R.string.c2_merge_confirm, name(p.fromLocalId), name(p.toLocalId))
            is DeleteProposal -> activity.getString(R.string.delete_person_confirm)
            else -> null
        }
        if (message == null) {
            decide { it.accept(item) }
            return
        }
        val title = when (val p = item.proposal) {
            is DeleteProposal -> p.name ?: name(p.localId)
            else -> activity.getString(R.string.c2_merge_title)
        }
        ConfirmationDialog(
            activity,
            message = message,
            positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel,
            dialogTitle = title,
        ) {
            decide { it.accept(item) }
        }
    }

    private fun decide(action: (FamilyProposals) -> Unit) {
        val app = activity.applicationContext
        ensureBackgroundThread {
            val ok = try {
                action(FamilyProposals(app))
                true
            } catch (ignored: RuntimeException) {
                false
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (!ok) activity.toast(R.string.c2_operation_failed)
                onDecided()
            }
        }
    }

    companion object {
        private const val TEXT_SIZE_SP = 16f
        private const val ROW_VERTICAL_PADDING = 0.75f
    }
}
