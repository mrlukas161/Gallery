package org.fossify.gallery.safety

import android.util.Log
import android.view.View
import android.widget.TextView
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.gallery.R
import org.fossify.gallery.family.PeopleBackupUi
import org.fossify.gallery.jobs.PeopleBackupJob
import java.io.IOException

// Nastavenia → „Automatická záloha osôb“ (zapnutá, posledná záloha <dátum>; zapnúť/vypnúť,
// zálohovať teraz) a „Obnoviť zo zálohy“ (výber automatickej zálohy → existujúca F6 obnova, ktorá
// len dopĺňa). Keď sa automatická záloha nenájde (napr. po preinštalovaní bez prístupu ku všetkým
// súborom), otvorí sa ručný výber súboru. V zozname je pri zálohe počet osôb a priradení (N8); ak ZIP
// k vybranej zálohe obsahuje obľúbené, ponúkne sa doplnenie chýbajúcich (N13).
class AutoBackupSettings(
    private val activity: BaseSimpleActivity,
    private val backupUi: PeopleBackupUi,
    private val pickFile: () -> Unit,
) {
    private val app = activity.applicationContext
    private var summaryView: TextView? = null

    fun setup(backupHolder: View, summary: TextView, restoreHolder: View) {
        summaryView = summary
        refreshSummary()
        backupHolder.setOnClickListener { showOptions() }
        restoreHolder.setOnClickListener { chooseBackup() }
    }

    private fun refreshSummary() {
        val view = summaryView ?: return
        val s = AutoBackup.status(app)
        val text = when {
            !s.enabled -> activity.getString(R.string.ta1_auto_backup_off)
            s.lastAt <= 0L -> activity.getString(R.string.ta1_auto_backup_on_never)
            else -> activity.getString(R.string.ta1_auto_backup_on_last, DataSafetyUi.formatDate(s.lastAt))
        }
        val omitted = if (s.enabled && s.lastAt > 0L && s.lastOmitted > 0) {
            " " + activity.getString(R.string.ta1_auto_backup_omitted, s.lastOmitted)
        } else {
            ""
        }
        val failed = if (s.enabled && s.lastFailedAt > s.lastAt) {
            " " + activity.getString(R.string.ta1_auto_backup_failed)
        } else {
            ""
        }
        view.text = text + omitted + failed + healthNote(s)
    }

    // N8: automatická záloha čaká na obnovu poškodenej DB, alebo posledná má výrazne menej osôb
    private fun healthNote(s: AutoBackup.Status): String = when {
        !s.enabled -> ""
        s.lastSkippedAt > s.lastAt && s.lastSkippedAt >= s.lastFailedAt ->
            " " + activity.getString(R.string.fix068_auto_backup_skipped)

        s.lastAt > 0L && s.lastSuspect -> " " + activity.getString(R.string.fix068_auto_backup_suspect)
        else -> ""
    }

    private fun showOptions() {
        val enabled = AutoBackup.status(app).enabled
        val items = arrayListOf(
            RadioItem(
                OPTION_TOGGLE,
                activity.getString(if (enabled) R.string.ta1_auto_backup_disable else R.string.ta1_auto_backup_enable),
            ),
            RadioItem(OPTION_NOW, activity.getString(R.string.ta1_auto_backup_now)),
        )
        RadioGroupDialog(activity, items, titleId = R.string.ta1_auto_backup_title) { choice ->
            when (choice as Int) {
                OPTION_TOGGLE -> {
                    AutoBackup.setEnabled(app, !enabled)
                    refreshSummary()
                    ensureBackgroundThread { PeopleBackupJob.refresh(app) }
                }

                OPTION_NOW -> backupNow()
            }
        }
    }

    private fun backupNow() {
        activity.toast(R.string.fb_backup_running)
        ensureBackgroundThread {
            val summary = try {
                AutoBackup.run(app, manual = true)
            } catch (e: IOException) {
                Log.w(TAG, "záloha osôb zlyhala", e)
                null
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                refreshSummary()
                if (summary == null) {
                    activity.toast(R.string.fb_backup_failed)
                } else {
                    val text = activity.getString(
                        R.string.fb_backup_done, summary.persons, summary.assignments, summary.groups, summary.anchors,
                    )
                    val where = activity.getString(R.string.ta1_auto_backup_done)
                    DataSafetyUi.messageDialog(activity, "$text\n\n${backupUi.extrasSummary(summary)}\n\n$where") {
                        setPositiveButton(org.fossify.commons.R.string.ok, null)
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun chooseBackup() {
        ensureBackgroundThread {
            val store = AutoBackupStore(app)
            var all: List<AutoBackupStore.Item> = emptyList()
            var metas: Map<String, AutoBackupHealth.Meta> = emptyMap()
            try {
                all = store.list()
                metas = AutoBackupMeta.metasFor(app, store, all)
            } catch (e: RuntimeException) {
                Log.w(TAG, "zálohy sa nepodarilo načítať", e)
            }
            val backups = all.filter { it.kind == AutoBackupNames.Kind.F6 }
            val zips = all.filter { it.kind == AutoBackupNames.Kind.DB }
                .mapNotNull { z -> AutoBackupNames.stampOf(z.name)?.let { it to z } }.toMap()
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (backups.isEmpty()) {
                    activity.toast(R.string.ta1_restore_auto_none)
                    pickFile()
                    return@runOnUiThread
                }
                val items = ArrayList(
                    backups.mapIndexed { i, b ->
                        val meta = AutoBackupNames.stampOf(b.name)?.let { metas[it] }
                        RadioItem(i, AutoBackupMeta.label(activity, b.createdAt, meta))
                    },
                )
                RadioGroupDialog(activity, items, titleId = R.string.ta1_restore_auto_title) { choice ->
                    backups.getOrNull(choice as Int)?.let { b ->
                        val zip = AutoBackupNames.stampOf(b.name)?.let { zips[it] }
                        offerFavorites(store, zip) { backupUi.confirmRestore(b.uri) }
                    }
                }
            }
        }
    }

    // N13: obľúbené zo ZIP-u tej istej zálohy — ponúkne doplnenie chýbajúcich, potom pokračuje obnovou osôb
    @Suppress("TooGenericExceptionCaught")
    private fun offerFavorites(store: AutoBackupStore, zip: AutoBackupStore.Item?, then: () -> Unit) {
        if (zip == null) {
            then()
            return
        }
        ensureBackgroundThread {
            val missing = try {
                AutoBackupFavorites.read(store, zip)?.let { AutoBackupFavorites.missing(app, it) }.orEmpty()
            } catch (e: IOException) {
                Log.w(TAG, "obľúbené zo zálohy sa nepodarilo prečítať", e)
                emptyList()
            } catch (e: RuntimeException) {
                Log.w(TAG, "obľúbené zo zálohy sa nepodarilo prečítať", e)
                emptyList()
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (missing.isEmpty()) {
                    then()
                    return@runOnUiThread
                }
                val text = activity.getString(R.string.fix068_favorites_restore, missing.size)
                DataSafetyUi.messageDialog(activity, text) {
                    setPositiveButton(R.string.fix068_favorites_restore_yes) { _, _ -> restoreFavorites(missing, then) }
                    setNegativeButton(R.string.fix068_favorites_restore_skip) { _, _ -> then() }
                }
            }
        }
    }

    private fun restoreFavorites(paths: List<String>, then: () -> Unit) {
        ensureBackgroundThread {
            val done = AutoBackupFavorites.restore(app, paths)
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                activity.toast(activity.getString(R.string.fix068_favorites_restored, done))
                then()
            }
        }
    }

    companion object {
        private const val TAG = "AutoBackupSettings"
        private const val OPTION_TOGGLE = 0
        private const val OPTION_NOW = 1
    }
}
