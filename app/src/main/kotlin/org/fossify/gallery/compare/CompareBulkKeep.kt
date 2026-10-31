package org.fossify.gallery.compare

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyTextView
import org.fossify.gallery.R
import org.fossify.gallery.activities.SimpleActivity
import org.fossify.gallery.helpers.CompareAnalysis
import org.fossify.gallery.helpers.CompareBulk
import org.fossify.gallery.helpers.CompareGroup
import org.fossify.gallery.helpers.CompareKind
import org.fossify.gallery.helpers.PhotoMeasure
import org.fossify.gallery.helpers.PhotoScore
import java.io.File

// Hromadné „Ponechať najlepšiu v označených skupinách“ a „Upratať presné kópie“ zo zoznamu „Podobné a duplikáty“:
//  1. vyhodnotenie na pozadí — séria/podobné cez CompareAnalysis.analyzeGroups (jeden model tvárí na celý beh,
//     cez HeavyGate; na spracovanie knižnice sa čaká najviac FACE_WAIT_MS, potom beh ide bez tvárí a vyžaduje
//     väčší náskok ★), presné kópie bez analýzy (rozlíšenie, priečinok, vek); priebeh „Vyhodnocujem skupinu
//     3 / 23…“ s tlačidlom Zrušiť; v jednom kole najviac CompareBulk.MAX_PHOTOS_PER_RUN fotiek,
//  2. plán CompareBulk (neisté skupiny sa preskočia, ★ ostane vždy, žiadna skupina nepríde o všetky fotky),
//  3. prehľad v ConfirmationDialog (počet, skupiny, veľkosť, preskočené),
//  4. mazanie rovnakou cestou ako porovnávač (CompareTrash: SAF, Kôš / natrvalo) a „Späť“ = obnova celej dávky.
class CompareBulkKeep(private val activity: SimpleActivity, private val listener: Listener) {
    interface Listener {
        // gone = fotky, ktoré naozaj zmizli, bytes = ich veľkosť, toBin = sú v Koši (dá sa „Späť“)
        fun onBulkTrashed(gone: List<String>, toBin: Boolean, bytes: Long)

        fun onBulkRestored(restored: List<String>)
    }

    // výsledok prípravy: plán, čo naozaj zmazať (len existujúce), veľkosť a čo ukázať v prehľade
    private class Prepared(
        val plan: CompareBulk.Plan,
        val delete: List<String>,
        val bytes: Long,
        val overLimit: Int,
        val facesMissing: Boolean,
        val hasExact: Boolean,
    )

    @Volatile
    private var cancelled = false
    private var busy = false
    private var progress: Progress? = null
    private var plannedSizes: Map<String, Long> = emptyMap()

    private val trash = CompareTrash(
        activity,
        object : CompareTrash.Listener {
            override fun onTrashed(gone: List<String>, toBin: Boolean) =
                listener.onBulkTrashed(gone, toBin, gone.sumOf { plannedSizes[it] ?: 0L })

            override fun onRestored(restored: List<String>) = listener.onBulkRestored(restored)
        },
    )

    // vybrané skupiny (série a podobné sa vyhodnotia, presné kópie podľa pravidla); all = všetky skupiny
    // zoznamu (aj skryté) kvôli invariantu, sizes = veľkosti fotiek (CompareRepository.Snapshot.sizes)
    fun keepBest(selected: List<CompareGroup>, all: List<CompareGroup>, sizes: Map<String, Long>) =
        start(selected, all, sizes, R.string.pw_keep_best_title)

    // rýchla akcia bez analýzy tvárí: všetky (viditeľné) skupiny presných kópií
    fun cleanExact(groups: List<CompareGroup>, all: List<CompareGroup>, sizes: Map<String, Long>) =
        start(groups.filter { it.kind == CompareKind.EXACT }, all, sizes, R.string.pw_clean_exact_title)

    // „Späť“: celá dávka z Koša na pôvodné miesta
    fun restore(paths: List<String>) = trash.restore(paths)

    // zatvorenie obrazovky: výpočet skončí po aktuálnej fotke (model tvárí sa zatvára vo vlákne behu)
    fun stop() {
        cancelled = true
        progress?.dismiss()
        progress = null
    }

    private fun start(groups: List<CompareGroup>, all: List<CompareGroup>, sizes: Map<String, Long>, title: Int) {
        if (busy) {
            activity.toast(R.string.pw_busy)
            return
        }
        if (groups.isEmpty()) return
        busy = true
        cancelled = false
        val exact = groups.filter { it.kind == CompareKind.EXACT }
        val quality = groups.filter { it.kind != CompareKind.EXACT }
        val fit = CompareBulk.fitRun(quality.map { it.paths })
        val run = quality.take(fit)
        val dialog = Progress(activity, title) { cancelled = true }
        progress = dialog
        val app = activity.applicationContext
        ensureBackgroundThread {
            val prepared = try {
                val measured = measureExact(exact, dialog)
                val scores = analyze(app, run, dialog)
                if (cancelled) null else prepare(run, scores, exact, measured, all, sizes, quality.size - fit)
            } catch (ignored: Exception) {
                null
            }
            activity.runOnUiThread {
                dialog.dismiss()
                if (progress === dialog) progress = null
                busy = false
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                when {
                    cancelled -> activity.toast(R.string.pw_cancelled)
                    prepared == null -> activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                    else -> confirm(prepared, title)
                }
            }
        }
    }

    // presné kópie: rozlíšenie z hlavičky súboru, čas zmeny, cesta (pravidlo CompareBulk.exactKeeper)
    private fun measureExact(exact: List<CompareGroup>, dialog: Progress): List<List<PhotoMeasure>> {
        val total = exact.sumOf { it.paths.size }
        var done = 0
        return exact.map { g ->
            g.paths.mapNotNull { path ->
                if (cancelled) return@mapNotNull null
                done++
                dialog.photos(done, total)
                readMeta(path)
            }
        }
    }

    private fun readMeta(path: String): PhotoMeasure? {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(path, bounds)
        } catch (ignored: Exception) {
        }
        val w = bounds.outWidth.coerceAtLeast(0)
        val h = bounds.outHeight.coerceAtLeast(0)
        return PhotoMeasure(path, 0.0, 0.0, width = w, height = h, modified = file.lastModified())
    }

    private fun analyze(app: Context, run: List<CompareGroup>, dialog: Progress) =
        if (run.isEmpty() || cancelled) {
            emptyList()
        } else {
            dialog.group(1, run.size)
            CompareAnalysis.analyzeGroups(
                app,
                run.map { it.paths },
                FACE_WAIT_MS,
                onGroup = { done, total -> if (done < total) dialog.group(done + 1, total) },
                onPhoto = { done, total -> dialog.photos(done, total) },
                isCancelled = { cancelled },
                listener = object : CompareAnalysis.Listener {
                    override fun onWaitingForFaces() = dialog.waiting()
                },
            )
        }

    @Suppress("LongParameterList") // jedna príprava plánu: vstupy z oboch druhov skupín
    private fun prepare(
        run: List<CompareGroup>,
        scores: List<Map<String, PhotoScore>>,
        exact: List<CompareGroup>,
        measured: List<List<PhotoMeasure>>,
        all: List<CompareGroup>,
        sizes: Map<String, Long>,
        overLimit: Int,
    ): Prepared {
        val decisions = run.mapIndexed { i, g ->
            CompareBulk.qualityDecision(CompareBulk.keeperFirst(g.paths, g.keeper), scores.getOrElse(i) { emptyMap() })
        } + exact.mapIndexed { i, g ->
            CompareBulk.exactDecision(CompareBulk.keeperFirst(g.paths, g.keeper), measured.getOrElse(i) { emptyList() })
        }
        val plan = CompareBulk.plan(decisions, all.map { CompareBulk.keeperFirst(it.paths, it.keeper) })
        // medzitým zmazané fotky vynechať (podmnožina plánu invariant neporuší)
        val delete = plan.delete.filter { File(it).exists() }
        val bytes = delete.associateWith { sizes[it] ?: File(it).length() }
        plannedSizes = bytes
        val facesMissing = scores.any { s -> s.size >= 2 && !s.values.all { it.facesChecked } }
        return Prepared(plan, delete, bytes.values.sum(), overLimit, facesMissing, exact.isNotEmpty())
    }

    private fun confirm(p: Prepared, title: Int) {
        val res = activity.resources
        val notes = ArrayList<String>()
        val plan = p.plan
        if (plan.uncertain > 0) {
            notes.add(res.getQuantityString(R.plurals.pw_skipped_uncertain, plan.uncertain, plan.uncertain))
        }
        if (plan.notMeasured > 0) {
            notes.add(res.getQuantityString(R.plurals.pw_skipped_unread, plan.notMeasured, plan.notMeasured))
        }
        if (p.overLimit > 0) notes.add(res.getQuantityString(R.plurals.pw_over_limit, p.overLimit, p.overLimit))
        val spared = plan.spared.size
        if (spared > 0) notes.add(res.getQuantityString(R.plurals.pw_spared, spared, spared))
        if (p.facesMissing) notes.add(activity.getString(R.string.pw_no_faces))
        val count = p.delete.size
        if (count == 0) {
            val text = (listOf(activity.getString(R.string.pw_nothing)) + notes).joinToString(" ")
            activity.toast(text, Toast.LENGTH_LONG)
            return
        }
        if (p.hasExact) notes.add(activity.getString(R.string.pw_exact_rule))
        val usesBin = trash.usesBin
        val what = res.getQuantityString(if (usesBin) R.plurals.pw_to_bin else R.plurals.pw_to_delete, count, count)
        val from = res.getQuantityString(R.plurals.pw_from_groups, plan.affected, plan.affected)
        val head = activity.getString(
            if (usesBin) R.string.pw_summary_bin else R.string.pw_summary_permanent,
            what,
            from,
            p.bytes.formatSize(),
        )
        val message = (listOf(head) + notes).joinToString("\n\n")
        val positive = if (usesBin) R.string.pa_confirm_bin_button else R.string.pa_confirm_permanent_button
        ConfirmationDialog(
            activity = activity,
            message = message,
            positive = positive,
            negative = org.fossify.commons.R.string.cancel,
            dialogTitle = activity.getString(title),
        ) {
            trash.trashConfirmed(p.delete)
        }
    }

    // Priebeh: „Vyhodnocujem skupinu 3 / 23…“, pruh podľa fotiek, Zrušiť (aj šípka späť); mimo dialógu
    // ťuk nič nezruší. Aktualizácie prichádzajú z vlákna na pozadí.
    private class Progress(private val activity: Activity, title: Int, private val onCancel: () -> Unit) {
        private val text = MyTextView(activity)
        private val detail = MyTextView(activity)
        private val bar = LinearProgressIndicator(activity)
        private var dialog: AlertDialog? = null

        init {
            val pad = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, 0)
            }
            val wrap = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            text.setText(R.string.pw_progress_prepare)
            bar.max = 1
            bar.setIndicatorColor(activity.getProperPrimaryColor())
            box.addView(text, wrap)
            box.addView(bar, LinearLayout.LayoutParams(wrap).apply { topMargin = pad / 2 })
            box.addView(detail, LinearLayout.LayoutParams(wrap).apply { topMargin = pad / 2 })
            activity.getAlertDialogBuilder()
                .setNegativeButton(org.fossify.commons.R.string.cancel) { _, _ -> onCancel() }
                .apply {
                    activity.setupDialogStuff(box, this, title) { d ->
                        dialog = d
                        d.setCanceledOnTouchOutside(false)
                        d.setOnCancelListener { onCancel() }
                    }
                }
        }

        fun group(n: Int, total: Int) = ui { text.text = activity.getString(R.string.pw_progress_group, n, total) }

        fun photos(done: Int, total: Int) = ui {
            bar.max = total.coerceAtLeast(1)
            bar.setProgressCompat(done, true)
            detail.text = activity.getString(R.string.pw_progress_photos, done, total)
        }

        fun waiting() = ui { detail.setText(R.string.pw_progress_waiting) }

        fun dismiss() {
            dialog?.setOnCancelListener(null)
            dialog?.dismiss()
            dialog = null
        }

        private fun ui(action: () -> Unit) {
            activity.runOnUiThread { if (dialog != null && !activity.isDestroyed) action() }
        }
    }

    private companion object {
        // dlhšie nečakať na spracovanie knižnice — potom beh pokračuje bez tvárí (a vyžaduje väčší náskok ★)
        const val FACE_WAIT_MS = 15_000L
    }
}
