package org.fossify.gallery.safety

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.gallery.R
import org.fossify.gallery.jobs.PeopleBackupJob
import java.io.IOException
import java.text.DateFormat
import java.util.Date

// Hlásenia ochrany dát pri štarte appky (volá MainActivity):
//  - A4: dáta z novšej verzie appky → NewerDataActivity namiesto pádu;
//  - A1: poškodená DB bola premenovaná → dialóg „Databáza osôb je poškodená – obnoviť zo zálohy?“
//    s ponukou automatických záloh spred poškodenia (A3, núdzová obnova surovej kópie cez DbRestore);
//    predvolená je najnovšia zdravá, ostatné sa dajú vybrať zo zoznamu s dátumom a počtom osôb (N8);
//  - po nasadení obnovy jedno potvrdenie, že sa podarila.
// Dialógy cez getAlertDialogBuilder + setupDialogStuff.
object DataSafetyUi {
    private const val TAG = "DataSafetyUi"

    // tento proces už dialóg o poškodení ukázal („Neskôr“ = znova pri ďalšom spustení appky)
    @Volatile
    private var corruptionAsked = false

    // MainActivity.onCreate hneď po super.onCreate: true = aktivita sa zavrie, ukáže sa NewerDataActivity
    @Suppress("TooGenericExceptionCaught")
    fun blockIfNewerData(activity: Activity): Boolean {
        val newer = try {
            DbVersionGuard.newerDatabases(activity)
        } catch (e: RuntimeException) {
            Log.w(TAG, "verziu databáz sa nepodarilo overiť", e)
            emptyList()
        }
        if (newer.isEmpty()) return false
        activity.startActivity(
            Intent(activity, NewerDataActivity::class.java)
                .putExtra(NewerDataActivity.EXTRA_DATABASES, newer.joinToString(", ")),
        )
        activity.finish()
        return true
    }

    // MainActivity.onCreate (prvé spustenie): naplánovanie týždennej zálohy na pozadí
    fun onAppStarted(activity: Activity) {
        val app = activity.applicationContext
        ensureBackgroundThread {
            if (!DbVersionGuard.hasNewerData(app)) PeopleBackupJob.refresh(app)
        }
    }

    // MainActivity.onResume: hlásenie o poškodení alebo dokončenej obnove
    @Suppress("TooGenericExceptionCaught")
    fun onResume(activity: Activity) {
        val app = activity.applicationContext
        val state = DbSafetyState(app)
        state.restoredNotice?.let { names ->
            state.restoredNotice = null
            messageDialog(activity, activity.getString(R.string.ta1_restored_notice, names)) {
                setPositiveButton(org.fossify.commons.R.string.ok, null)
            }
            return
        }
        if (corruptionAsked) return
        val events = state.corruptions()
        if (events.isEmpty()) return
        corruptionAsked = true
        ensureBackgroundThread {
            val offers = try {
                AutoBackupMeta.offers(app, events)
            } catch (e: RuntimeException) {
                Log.w(TAG, "zálohy sa nepodarilo načítať", e)
                emptyList()
            }
            activity.runOnUiThread { showCorruption(activity, events, offers) }
        }
    }

    private fun showCorruption(
        activity: Activity,
        events: List<DbSafetyState.Corruption>,
        offers: List<Pair<AutoBackupHealth.Candidate, AutoBackupStore.Item>>,
    ) {
        if (activity.isDestroyed || activity.isFinishing) return
        val state = DbSafetyState(activity)
        val aside = events.joinToString(", ") { it.asideName }
        val restorable = events.map { it.dbName }.filter { it in DbFiles.BACKED_UP }.toSet()
        if (restorable.isEmpty()) {
            // len faces.db: dá sa nanovo spracovať, mená k tváram vráti F6 obnova
            messageDialog(activity, activity.getString(R.string.ta1_corrupt_faces, aside)) {
                setPositiveButton(org.fossify.commons.R.string.ok) { _, _ -> state.clearCorruptions() }
            }
            return
        }
        val title = activity.getString(R.string.ta1_corrupt_title)
        val recommended = AutoBackupHealth.recommended(offers.map { it.first })
        val latest = offers.firstOrNull { it.first == recommended }?.second
        if (recommended == null || latest == null) {
            messageDialog(activity, title + "\n\n" + activity.getString(R.string.fix068_corrupt_no_backup, aside)) {
                setPositiveButton(org.fossify.commons.R.string.ok, null)
                setNeutralButton(R.string.ta1_dont_ask) { _, _ -> state.clearCorruptions() }
            }
            return
        }
        val date = AutoBackupMeta.label(activity, recommended.createdAt, recommended.meta)
        val hint = if (offers.size > 1) "\n\n" + activity.getString(R.string.fix068_corrupt_choose_hint) else ""
        val detail = activity.getString(R.string.fix068_corrupt_detail, aside, date)
        messageDialog(activity, title + "\n\n" + detail + hint) {
            setPositiveButton(R.string.ta1_restore_from_backup) { _, _ ->
                if (offers.size > 1) {
                    chooseOffer(activity, offers, latest, restorable)
                } else {
                    confirmRawRestore(activity, latest, restorable)
                }
            }
            setNegativeButton(R.string.ta1_later, null)
            setNeutralButton(R.string.ta1_dont_ask) { _, _ -> state.clearCorruptions() }
        }
    }

    // výber zo záloh spred poškodenia; predvolená je odporúčaná (najnovšia zdravá)
    private fun chooseOffer(
        activity: Activity,
        offers: List<Pair<AutoBackupHealth.Candidate, AutoBackupStore.Item>>,
        recommended: AutoBackupStore.Item,
        names: Set<String>,
    ) {
        if (activity.isDestroyed || activity.isFinishing) return
        val items = ArrayList(
            offers.mapIndexed { i, (c, _) -> RadioItem(i, AutoBackupMeta.label(activity, c.createdAt, c.meta)) },
        )
        val checked = offers.indexOfFirst { it.second == recommended }
        val title = R.string.ta1_restore_auto_title
        RadioGroupDialog(activity, items, checkedItemId = checked, titleId = title) { choice ->
            offers.getOrNull(choice as Int)?.let { confirmRawRestore(activity, it.second, names) }
        }
    }

    // obnova surovej kópie až po výslovnom potvrdení; súčasná DB sa pri štarte premenuje, nezmaže
    private fun confirmRawRestore(activity: Activity, zip: AutoBackupStore.Item, names: Set<String>) {
        val text = activity.getString(
            R.string.ta1_raw_restore_confirm,
            names.sorted().joinToString(", "),
            formatDate(zip.createdAt),
        )
        messageDialog(activity, text) {
            setPositiveButton(R.string.ta1_restore_from_backup) { _, _ -> stageAndClose(activity, zip, names) }
            setNegativeButton(org.fossify.commons.R.string.cancel, null)
        }
    }

    private fun stageAndClose(activity: Activity, zip: AutoBackupStore.Item, names: Set<String>) {
        val app = activity.applicationContext
        ensureBackgroundThread {
            val staged = try {
                DbRestore.stage(app, AutoBackupStore(app), zip, names)
            } catch (e: IOException) {
                Log.w(TAG, "zálohu sa nepodarilo pripraviť", e)
                emptySet()
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (staged.isEmpty()) {
                    activity.toast(R.string.ta1_raw_restore_failed)
                    return@runOnUiThread
                }
                messageDialog(activity, activity.getString(R.string.ta1_raw_restore_restart)) {
                    setCancelable(false)
                    setPositiveButton(org.fossify.commons.R.string.ok) { _, _ -> closeApp(activity) }
                }
            }
        }
    }

    // Otvorené Room DB sa za behu vymeniť nedajú: appka sa zavrie a obnova sa nasadí pri ďalšom štarte
    // (DbRestore.applyPending v App.onCreate) skôr, než sa ktorákoľvek DB otvorí.
    private fun closeApp(activity: Activity) {
        activity.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    fun formatDate(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun messageDialog(activity: Activity, message: String, configure: AlertDialog.Builder.() -> Unit) {
        if (activity.isDestroyed || activity.isFinishing) return
        val padding = (activity.resources.displayMetrics.density * DIALOG_PADDING_DP).toInt()
        val view = TextView(activity).apply {
            text = message
            setTextColor(activity.getProperTextColor())
            setPadding(padding, padding, padding, 0)
        }
        val scroll = ScrollView(activity).apply { addView(view) }
        val builder = activity.getAlertDialogBuilder()
        builder.configure()
        activity.setupDialogStuff(scroll, builder)
    }

    private const val DIALOG_PADDING_DP = 20
}
