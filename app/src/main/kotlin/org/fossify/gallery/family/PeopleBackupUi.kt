package org.fossify.gallery.family

import android.net.Uri
import android.util.Log
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Nastavenia → „Zálohovať osoby“ / „Obnoviť osoby“ (F6). Súbor vyberá používateľ cez SAF
// (ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT, spúšťa SettingsActivity), práca beží na pozadí.
// Dialógy cez ConfirmationDialog (getAlertDialogBuilder + setupDialogStuff).
class PeopleBackupUi(private val activity: BaseSimpleActivity) {
    private val app = activity.applicationContext

    fun suggestedFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "galeria-osoby-$date.${PeopleBackupFormat.EXTENSION}"
    }

    @Suppress("TooGenericExceptionCaught")
    fun export(uri: Uri) {
        activity.toast(R.string.fb_backup_running)
        ensureBackgroundThread {
            val summary = try {
                PeopleBackup(app).export(uri)
            } catch (e: IOException) {
                Log.w(TAG, "záloha osôb zlyhala", e)
                null
            } catch (e: RuntimeException) {
                Log.w(TAG, "záloha osôb zlyhala", e)
                null
            }
            ui {
                if (summary == null) {
                    activity.toast(R.string.fb_backup_failed)
                } else {
                    val text = activity.getString(
                        R.string.fb_backup_done, summary.persons, summary.assignments, summary.groups, summary.anchors,
                    )
                    message(text)
                }
            }
        }
    }

    // prečíta zálohu a pred obnovou sa opýta
    @Suppress("TooGenericExceptionCaught")
    fun confirmRestore(uri: Uri) {
        ensureBackgroundThread {
            val backup = try {
                PeopleBackup(app).read(uri)
            } catch (e: IOException) {
                Log.w(TAG, "zálohu osôb sa nepodarilo prečítať", e)
                null
            } catch (e: RuntimeException) {
                Log.w(TAG, "zálohu osôb sa nepodarilo prečítať", e)
                null
            }
            ui {
                if (backup == null || backup.ops.isEmpty()) {
                    activity.toast(R.string.fb_restore_invalid)
                } else {
                    val persons = backup.ops.count { it.type == OpType.PERSON_CREATE }
                    val source = label(backup)
                    ConfirmationDialog(
                        activity,
                        message = activity.getString(R.string.fb_restore_confirm, source, persons),
                        positive = R.string.fb_restore_button,
                        negative = org.fossify.commons.R.string.cancel,
                    ) {
                        restore(backup, source)
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun restore(backup: PeopleBackupFormat.Backup, source: String) {
        activity.toast(R.string.fb_restore_running)
        ensureBackgroundThread {
            val result = try {
                PeopleBackup(app).restore(backup, source)
            } catch (e: RuntimeException) {
                Log.w(TAG, "obnova osôb zlyhala", e)
                null
            }
            ui {
                when (result) {
                    is PeopleBackup.RestoreResult.Done -> {
                        val r = result.report
                        message(
                            activity.getString(
                                R.string.fb_restore_done,
                                maxOf(0, r.applied - r.proposals),
                                r.proposals,
                                r.deferred,
                                r.skipped + r.duplicates,
                            )
                        )
                    }

                    PeopleBackup.RestoreResult.Invalid -> activity.toast(R.string.fb_restore_invalid)
                    null -> activity.toast(R.string.fb_restore_failed)
                }
            }
        }
    }

    // „záloha osôb z 20. 10. 2026 (Xiaomi 15)“ — ukáže sa aj pri návrhoch v Ľuďoch
    private fun label(backup: PeopleBackupFormat.Backup): String {
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(backup.createdAt))
        val device = backup.deviceName?.takeIf { it.isNotBlank() }
        return if (device == null) {
            activity.getString(R.string.fb_source, date)
        } else {
            activity.getString(R.string.fb_source_device, date, device)
        }
    }

    private fun message(text: String) {
        ConfirmationDialog(activity, message = text, positive = org.fossify.commons.R.string.ok, negative = 0) {}
    }

    private fun ui(block: () -> Unit) {
        activity.runOnUiThread {
            if (!activity.isDestroyed && !activity.isFinishing) block()
        }
    }

    companion object {
        private const val TAG = "PeopleBackupUi"
    }
}
