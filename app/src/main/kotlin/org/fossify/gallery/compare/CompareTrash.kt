package org.fossify.gallery.compare

import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.isAccessibleWithSAFSdk30
import org.fossify.commons.extensions.needsStupidWritePermissions
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.gallery.R
import org.fossify.gallery.activities.SimpleActivity
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.restoreRecycleBinPaths
import java.io.File

// Mazanie z porovnávača VŽDY cez ConfirmationDialog s počtom a veľkosťou: pri zapnutom Koši presun do Koša
// (Kôš súbor kopíruje, miesto sa uvoľní až po vysypaní — dialóg to povie) a „Späť“ vráti fotky cez
// restoreRecycleBinPaths; pri vypnutom Koši dialóg jasne povie „natrvalo“. Zlyhanie presunu do Koša
// (aj výnimka v movePathsInRecycleBin) skončí hláškou a nič sa nevymaže.
class CompareTrash(private val activity: SimpleActivity, private val listener: Listener) {
    interface Listener {
        // gone = fotky, ktoré z disku naozaj zmizli; toBin = sú v Koši (dá sa vrátiť)
        fun onTrashed(gone: List<String>, toBin: Boolean)

        fun onRestored(restored: List<String>)
    }

    val usesBin: Boolean get() = activity.config.useRecycleBin

    fun confirm(paths: List<String>, bytes: Long) {
        if (paths.isEmpty()) {
            activity.toast(R.string.pa_nothing_marked)
            return
        }
        val what = CompareTexts.photos(activity, paths.size)
        val size = bytes.formatSize()
        val template = if (usesBin) R.string.pa_confirm_bin else R.string.pa_confirm_permanent
        val message = activity.getString(template, what, size)
        val positive = if (usesBin) R.string.pa_confirm_bin_button else R.string.pa_confirm_permanent_button
        ConfirmationDialog(activity, message, positive = positive, negative = org.fossify.commons.R.string.cancel) {
            run(ArrayList(paths))
        }
    }

    // porovnavac-W2: hromadné „Ponechať najlepšiu“ zo zoznamu skupín — prehľad s počtom, veľkosťou a náhľadmi
    // už používateľ potvrdil (CompareBulkKeep); ďalej rovnaká cesta ako z porovnávača (SAF, Kôš / natrvalo)
    fun trashConfirmed(paths: List<String>) {
        if (paths.isNotEmpty()) run(ArrayList(paths))
    }

    private fun run(toDelete: ArrayList<String>) {
        val safPath = toDelete.firstOrNull { activity.needsStupidWritePermissions(it) } ?: toDelete.first()
        activity.handleSAFDialog(safPath) { safOk ->
            if (!safOk) return@handleSAFDialog
            val sdk30Path = toDelete.firstOrNull { activity.isAccessibleWithSAFSdk30(it) } ?: toDelete.first()
            activity.checkManageMediaOrHandleSAFDialogSdk30(sdk30Path) { manageOk ->
                if (manageOk) moveOrDelete(toDelete)
            }
        }
    }

    private fun moveOrDelete(toDelete: ArrayList<String>) {
        val res = activity.resources
        if (!usesBin) {
            val n = toDelete.size
            activity.toast(res.getQuantityString(org.fossify.commons.R.plurals.deleting_items, n, n))
            deleteOriginals(toDelete, toBin = false)
            return
        }
        val n = toDelete.size
        activity.toast(res.getQuantityString(org.fossify.commons.R.plurals.moving_items_into_bin, n, n))
        // callback beží na vlákne na pozadí
        activity.movePathsInRecycleBin(toDelete) { moved ->
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (moved) deleteOriginals(toDelete, toBin = true) else activity.toast(R.string.pa_bin_failed)
            }
        }
    }

    // zmaže originály (rieši SAF aj scoped storage); ďalej idú len fotky, ktoré na disku naozaj zmizli
    private fun deleteOriginals(toDelete: List<String>, toBin: Boolean) {
        val items = toDelete.map { FileDirItem(it, it.getFilenameFromPath()) }
        activity.deleteFiles(items) { _ ->
            ensureBackgroundThread {
                val gone = toDelete.filter { !activity.getDoesFilePathExist(it) }
                // pri Koši riadok v DB už prešiel na cestu v Koši (movePathsInRecycleBin), pri trvalom mazaní ho zmaž
                if (!toBin) gone.forEach { activity.deleteDBPath(it) }
                activity.runOnUiThread {
                    if (activity.isDestroyed) return@runOnUiThread
                    val failed = toDelete.size - gone.size
                    if (failed > 0) activity.toast(activity.getString(R.string.pa_delete_failed, failed))
                    listener.onTrashed(gone, toBin)
                }
            }
        }
    }

    // „Späť“: fotky z Koša na pôvodné miesta (existujúca obnova z Koša), do skupiny sa vrátia tie, ktoré sú späť
    fun restore(originals: List<String>) {
        val inBin = ArrayList(originals.map { binPathOf(it) })
        activity.restoreRecycleBinPaths(inBin) {
            ensureBackgroundThread {
                val back = originals.filter { File(it).exists() }
                activity.runOnUiThread {
                    if (activity.isDestroyed) return@runOnUiThread
                    if (back.size < originals.size) activity.toast(R.string.pa_restore_failed)
                    listener.onRestored(back)
                }
            }
        }
    }

    // rovnako ako movePathsInRecycleBin: kópia v Koši = recycleBinPath + pôvodná cesta
    private fun binPathOf(path: String): String = File(activity.recycleBinPath, path).path
}
