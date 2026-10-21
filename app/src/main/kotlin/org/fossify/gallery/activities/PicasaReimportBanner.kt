package org.fossify.gallery.activities

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.faces.FaceEmbedVersion
import org.fossify.gallery.faces.PicasaImportService

// Lišta „Vzory z Picasy treba znova naimportovať (nová verzia rozpoznávania)“ v Ľuďoch. Po prepnutí
// odtlačkov na v2 sa staré vzory z Picasy nepoužívajú (sú v inom priestore); ťuk otvorí výber ZIP-u
// a spustí import (priebeh priamo v lište). Vytvárať ako vlastnosť aktivity (registerForActivityResult
// musí prebehnúť pred onStart).
class PicasaReimportBanner(
    private val activity: ComponentActivity,
    private val banner: () -> TextView,
    private val onImported: () -> Unit,
) {
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) start(uri)
    }

    fun refresh() {
        ensureBackgroundThread {
            val needed = try {
                FaceEmbedVersion.needsPicasaReimport(activity)
            } catch (ignored: RuntimeException) {
                false
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                show(needed)
            }
        }
    }

    private fun show(needed: Boolean) {
        val view = banner()
        if (!needed && !PicasaImportService.isRunning) {
            view.beGone()
            return
        }
        if (!PicasaImportService.isRunning) view.text = activity.getString(R.string.tb_picasa_reimport)
        view.setTextColor(activity.getProperPrimaryColor())
        view.setOnClickListener {
            if (PicasaImportService.isRunning) return@setOnClickListener
            try {
                picker.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                activity.toast(R.string.picasa_import_failed)
            }
        }
        view.beVisible()
    }

    private fun start(uri: Uri) {
        banner().text = activity.getString(R.string.picasa_import_running, 0, 0)
        PicasaImportService.import(
            activity, uri,
            onProgress = { done, total ->
                ui { banner().text = activity.getString(R.string.picasa_import_running, done, total) }
            },
            onDone = { persons, anchors, skipped, already ->
                ui {
                    activity.toast(
                        if (already) {
                            activity.getString(R.string.picasa_import_already)
                        } else {
                            activity.getString(R.string.picasa_import_done, persons, anchors, skipped)
                        }
                    )
                    refresh()
                    onImported()
                }
            },
            onError = { msg -> ui { banner().text = activity.getString(R.string.picasa_import_error, msg) } },
        )
    }

    private fun ui(block: () -> Unit) {
        activity.runOnUiThread {
            if (!activity.isDestroyed && !activity.isFinishing) block()
        }
    }
}
