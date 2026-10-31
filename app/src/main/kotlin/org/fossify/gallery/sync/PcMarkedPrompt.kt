package org.fossify.gallery.sync

import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bumptech.glide.Glide
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isAccessibleWithSAFSdk30
import org.fossify.commons.extensions.needsStupidWritePermissions
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.gallery.R
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.movePathsInRecycleBin
import java.io.File

// Fotky označené na zmazanie z webu (PC) — potvrdenie v mobile (MainActivity.onResume).
// Dialóg ukáže počet, veľkosť a 3–4 náhľady; samotné mazanie ide rovnakou cestou ako mazanie v appke
// (ComparatorActivity): štandardný ConfirmationDialog, SAF/scoped storage, pri zapnutom Koši
// movePathsInRecycleBin a až potom originály. Pri vypnutom Koši natrvalo a dialóg to jasne povie.
object PcMarkedPrompt {
    data class Summary(val paths: List<String>, val bytes: Long)

    private const val THUMBS = 4
    private const val THUMB_DP = 64
    private const val GAP_DP = 6
    private const val PAD_DP = 20

    // aby sa dialóg neopakoval pri každom onResume, pýtame sa znova až pri zmene počtu
    @Volatile
    private var lastPrompt = -1

    // existujúce označené cesty (zmiznuté sa tíško upracú) a ich veľkosť — mimo hlavného vlákna
    fun load(context: android.content.Context): Summary {
        val prefs = SyncStore.prefs(context)
        val all = SyncStore.markedPaths(prefs)
        val gone = all.filter { !File(it).exists() }
        if (gone.isNotEmpty()) SyncStore.removeMarked(prefs, gone)
        val paths = (all - gone.toSet()).sorted()
        return Summary(paths, paths.sumOf { File(it).length() })
    }

    // onDone: po zmazaní (obnoviť zoznam priečinkov)
    fun check(activity: BaseSimpleActivity, onDone: () -> Unit) {
        ensureBackgroundThread {
            try {
                val s = load(activity)
                if (s.paths.isEmpty() || s.paths.size == lastPrompt) return@ensureBackgroundThread
                activity.runOnUiThread {
                    if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                    lastPrompt = s.paths.size
                    show(activity, s, onDone)
                }
            } catch (ignored: Throwable) {
                // kontrola označení z PC je len pomôcka — jej zlyhanie nesmie zhodiť appku
                android.util.Log.e("GaleriaPlus", "zlyhala kontrola fotiek označených z PC", ignored)
            }
        }
    }

    private fun show(activity: BaseSimpleActivity, s: Summary, onDone: () -> Unit) {
        val res = activity.resources
        val density = res.displayMetrics.density
        val pad = (PAD_DP * density).toInt()
        val count = res.getQuantityString(R.plurals.pcc_marked_count, s.paths.size, s.paths.size)
        val useBin = activity.config.useRecycleBin
        val text = activity.getString(R.string.pcc_marked_message, count, s.bytes.formatSize()) + "\n\n" +
            activity.getString(if (useBin) R.string.pcc_marked_to_bin else R.string.pcc_marked_permanent)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        box.addView(
            TextView(activity).apply {
                this.text = text
                setTextColor(activity.getProperTextColor())
            }
        )
        box.addView(thumbRow(activity, s.paths.take(THUMBS), density))
        val positive = if (useBin) R.string.pcc_marked_move else R.string.pcc_marked_delete_permanent
        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton(positive) { _, _ -> confirm(activity, s, onDone) }
            .setNegativeButton(R.string.pc_marked_later, null)
            .setNeutralButton(R.string.pc_marked_clear) { _, _ ->
                ensureBackgroundThread { SyncStore.clearMarked(SyncStore.prefs(activity)) }
                lastPrompt = -1
            }
        activity.setupDialogStuff(ScrollView(activity).apply { addView(box) }, builder, R.string.pc_marked_title)
    }

    private fun thumbRow(activity: BaseSimpleActivity, paths: List<String>, density: Float): LinearLayout {
        val size = (THUMB_DP * density).toInt()
        val gap = (GAP_DP * density).toInt()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, gap * 2, 0, 0)
        }
        paths.forEach { path ->
            val iv = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = path.getFilenameFromPath()
            }
            row.addView(iv, LinearLayout.LayoutParams(size, size).apply { setMargins(gap / 2, 0, gap / 2, 0) })
            Glide.with(activity).load(File(path)).centerCrop().into(iv)
        }
        return row
    }

    // štandardné potvrdenie ako pri mazaní v appke (počet + veľkosť; Kôš / natrvalo podľa nastavenia)
    private fun confirm(activity: BaseSimpleActivity, s: Summary, onDone: () -> Unit) {
        val res = activity.resources
        val count = res.getQuantityString(org.fossify.commons.R.plurals.delete_items, s.paths.size, s.paths.size)
        val base = if (activity.config.useRecycleBin) {
            org.fossify.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            org.fossify.commons.R.string.deletion_confirmation
        }
        val message = String.format(res.getString(base), "$count (${s.bytes.formatSize()})")
        ConfirmationDialog(activity, message) { delete(activity, ArrayList(s.paths), onDone) }
    }

    private fun delete(activity: BaseSimpleActivity, paths: ArrayList<String>, onDone: () -> Unit) {
        if (paths.isEmpty()) return
        val safPath = paths.firstOrNull { activity.needsStupidWritePermissions(it) } ?: paths.first()
        activity.handleSAFDialog(safPath) { safOk ->
            if (!safOk) return@handleSAFDialog
            val sdk30Path = paths.firstOrNull { activity.isAccessibleWithSAFSdk30(it) } ?: paths.first()
            activity.checkManageMediaOrHandleSAFDialogSdk30(sdk30Path) { manageOk ->
                if (!manageOk) return@checkManageMediaOrHandleSAFDialogSdk30
                if (activity.config.useRecycleBin) {
                    ensureBackgroundThread {
                        // fotky, ktoré Galéria+ ešte nemala v DB, by sa v Koši neukázali
                        PcDeleter.ensureMediumRows(activity, paths)
                        activity.movePathsInRecycleBin(paths) { moved ->
                            if (moved) {
                                deleteOriginals(activity, paths, true, onDone)
                            } else {
                                activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                            }
                        }
                    }
                } else {
                    deleteOriginals(activity, paths, false, onDone)
                }
            }
        }
    }

    // zmaže originály (rieši SAF aj scoped storage); značky zruší len pri súboroch, ktoré naozaj zmizli
    private fun deleteOriginals(
        activity: BaseSimpleActivity,
        paths: List<String>,
        movedToBin: Boolean,
        onDone: () -> Unit,
    ) {
        val items = paths.map { FileDirItem(it, it.getFilenameFromPath()) }
        activity.deleteFiles(ArrayList(items)) { success ->
            ensureBackgroundThread {
                val gone = paths.filter { !activity.getDoesFilePathExist(it) }
                // pri Koši riadok v DB už prešiel na cestu v Koši (movePathsInRecycleBin), pri trvalom mazaní ho zmaž
                if (!movedToBin) gone.forEach { activity.deleteDBPath(it) }
                SyncStore.removeMarked(SyncStore.prefs(activity), gone)
                activity.runOnUiThread {
                    if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                    if (!success) activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                    if (gone.isNotEmpty()) {
                        val msg = if (movedToBin) R.string.pcc_marked_moved else R.string.pc_marked_deleted
                        activity.toast(activity.getString(msg, gone.size))
                    }
                    lastPrompt = -1
                    onDone()
                }
            }
        }
    }
}
