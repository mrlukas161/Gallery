package org.fossify.gallery.activities

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.gallery.R
import org.fossify.gallery.adapters.CompareStripAdapter
import org.fossify.gallery.compare.BarModel
import org.fossify.gallery.compare.CompareChrome
import org.fossify.gallery.compare.CompareGridAdapter
import org.fossify.gallery.compare.CompareInfoPanel
import org.fossify.gallery.compare.CompareOptions
import org.fossify.gallery.compare.ComparePane
import org.fossify.gallery.compare.CompareRunner
import org.fossify.gallery.compare.CompareSession
import org.fossify.gallery.compare.CompareStateStore
import org.fossify.gallery.compare.CompareTexts
import org.fossify.gallery.compare.CompareTrash
import org.fossify.gallery.compare.CompareUiState
import org.fossify.gallery.compare.Pane
import org.fossify.gallery.compare.PaneMath
import org.fossify.gallery.compare.PhotoInfo
import org.fossify.gallery.compare.RelativeView
import org.fossify.gallery.compare.Tournament
import org.fossify.gallery.databinding.ActivityComparatorBinding
import org.fossify.gallery.helpers.CompareGroups
import org.fossify.gallery.helpers.PathTransfer
import org.fossify.gallery.helpers.PhotoScore
import java.util.concurrent.Executors

// Porovnanie fotiek jednej skupiny (séria / duplikáty). Aktivita len spája menšie triedy: stav a značky
// CompareSession, turnaj Tournament, panely ComparePane (ostré priblíženie SharpRegion), lišty a rozloženie
// CompareChrome, mazanie a „Späť“ CompareTrash, údaje a skóre CompareRunner, uloženie stavu CompareStateStore.
class ComparatorActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityComparatorBinding::inflate)
    private lateinit var session: CompareSession
    private lateinit var tournament: Tournament
    private lateinit var panes: List<ComparePane>
    private lateinit var strip: CompareStripAdapter
    private lateinit var trash: CompareTrash
    private lateinit var chrome: CompareChrome
    private lateinit var infoPanel: CompareInfoPanel
    private lateinit var runner: CompareRunner
    private var grid: CompareGridAdapter? = null
    private var ui = CompareUiState()
    private val infos = HashMap<String, PhotoInfo>()
    private var scores: Map<String, PhotoScore> = emptyMap()
    private var analysisDone = false
    private var facesNoteShown = false
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val undoTimeout = Runnable { onUndoTimeout() }
    private var syncing = false
    private var duplicates = false
    private var groupPosition = 0
    private var groupCount = 0
    private var hasNextGroup = false

    // posledný presun do Koša (cesta → pôvodné poradie) pre „Späť“; po uplynutí lišty sa skupina prípadne zavrie
    private var lastTrashed: List<Pair<String, Int>> = emptyList()
    private var keepBestPending = false
    private var finishAfterUndo = FINISH_NONE

    // spätné volania menších tried (panely, Kôš, analýza) → súkromné metódy aktivity
    private val paneListener = object : ComparePane.Listener {
        override fun onPaneState(pane: ComparePane) = syncFrom(pane)

        override fun onPaneAction(pane: ComparePane, action: ComparePane.PaneAction) = paneAction(pane, action)
    }
    private val trashListener = object : CompareTrash.Listener {
        override fun onTrashed(gone: List<String>, toBin: Boolean) = trashed(gone, toBin)

        override fun onRestored(restored: List<String>) = restoredFromBin(restored)
    }
    private val runnerCallback = object : CompareRunner.Callback {
        override fun onInfos(infos: Map<String, PhotoInfo>) = infosLoaded(infos)

        override fun onProgress(done: Int, total: Int, waiting: Boolean) = chrome.progress(done, total, waiting)

        override fun onScores(scores: Map<String, PhotoScore>, final: Boolean) = scoresReady(scores, final)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.compareTopbar),
            padBottomSystem = listOf(binding.compareBottombar),
        )
        session = savedInstanceState?.let { CompareStateStore.restoreSession(it) }
            ?: CompareSession(PathTransfer.forCompare.orEmpty())
        PathTransfer.forCompare = null
        if (session.size < 2) {
            finish()
            return
        }
        tournament = Tournament(session)
        savedInstanceState?.let {
            CompareStateStore.restoreTournament(it, tournament)
            ui = CompareStateStore.restoreUi(it)
        }
        readIntent()
        trash = CompareTrash(this, trashListener)
        chrome = CompareChrome(this, binding)
        infoPanel = CompareInfoPanel(this, binding.compareInfoPanel, binding.compareInfoTable)
        runner = CompareRunner(this, runnerCallback)
        panes = listOf(
            ComparePane(this, Pane.A, binding.comparePaneA, executor, paneListener),
            ComparePane(this, Pane.B, binding.comparePaneB, executor, paneListener),
        )
        strip = CompareStripAdapter(this, session, ::onStripTap, ::onStripLong)
        binding.compareStrip.adapter = strip
        setupControls()
        chrome.colors()
        // obnovené priblíženie počká v paneli na fotku (applyRelative bez fotky si ho odloží)
        ui.viewA?.let { panes[0].applyRelative(it) }
        ui.viewB?.let { panes[1].applyRelative(it) }
        applyLayout()
        refresh()
        chrome.tipOnce()
        runner.start(session.paths.toList(), withInfos = true)
    }

    private fun readIntent() {
        groupPosition = intent.getIntExtra(CompareListActivity.EXTRA_GROUP_POSITION, 0)
        groupCount = intent.getIntExtra(CompareListActivity.EXTRA_GROUP_COUNT, 0)
        hasNextGroup = intent.getBooleanExtra(CompareListActivity.EXTRA_HAS_NEXT_GROUP, false)
        val tab = intent.getIntExtra(CompareListActivity.EXTRA_TAB, -1)
        // bez záložky: fotky z rôznych priečinkov sú skôr duplikáty než séria
        duplicates = if (tab >= 0) {
            tab != CompareGroups.TAB_BURSTS
        } else {
            session.paths.map { it.substringBeforeLast('/') }.toSet().size > 1
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::panes.isInitialized) return
        ui = ui.copy(viewA = panes[0].relativeView(), viewB = panes[1].relativeView())
        CompareStateStore.save(outState, session, tournament, ui)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::panes.isInitialized) applyLayout()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::panes.isInitialized) {
            runner.stop()
            panes.forEach { it.release() }
        }
        grid?.cancel()
        executor.shutdown()
        super.onDestroy()
    }

    private fun setupControls() {
        binding.compareBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.compareMore.setOnClickListener { showMoreMenu() }
        binding.compareSelectionDone.setOnClickListener { setSelection(false) }
        binding.compareStripToggle.setOnClickListener {
            ui = ui.copy(stripHidden = !ui.stripHidden)
            applyLayout()
        }
        binding.compareSync.setOnClickListener { toggleSync() }
        binding.compareDuel.setOnClickListener { if (tournament.active) stopDuel() else startDuel() }
        binding.compareDuelEnd.setOnClickListener { stopDuel() }
        binding.compareGridToggle.setOnClickListener { setGrid(nextGridSpan()) }
        binding.compareInfo.setOnClickListener {
            ui = ui.copy(infoOpen = !ui.infoOpen)
            updateBars()
        }
        binding.compareKeepBest.setOnClickListener { keepBest() }
        binding.compareDelete.setOnClickListener { trashMarked() }
        binding.compareUndo.setOnClickListener { undo() }
        binding.compareNextGroup.setOnClickListener { openNextGroup() }
    }

    // na výšku pás dole, na šírku vpravo (dá sa skryť); panely pod sebou alebo vedľa seba podľa fotiek
    private fun applyLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val gridOn = gridSpan() > 0
        // výrez pred zmenou veľkosti (otočenie, pás) — GestureImageView ho pri novej veľkosti zahodí
        val views = panes.map { it.relativeView() }
        chrome.layout(landscape, stripVisible = !ui.stripHidden && !gridOn, gridOn = gridOn)
        binding.compareStage.post { arrangePanes(views) }
        if (gridOn) applyGrid() else grid?.cancel()
    }

    // before = výrez spred zmeny rozloženia, ktorý sa po prekreslení vráti (null = len ak sa zmenia panely)
    private fun arrangePanes(before: List<RelativeView>? = null) {
        if (isDestroyed) return
        val views = before ?: panes.map { it.relativeView() }
        val changed = chrome.arrange(PaneMath.medianAspect(session.paths.mapNotNull { infos[it]?.aspect }))
        if (before == null && !changed) return
        binding.comparePanes.post { panes.forEachIndexed { i, p -> p.applyRelative(views[i]) } }
    }

    // --- obnova obrazovky ---

    private fun refresh() {
        panes.forEach {
            val path = session.pathOf(it.pane)
            it.show(path, infos[path], it.relativeView())
        }
        bindPanes()
        strip.notifyDataSetChanged()
        grid?.notifyDataSetChanged()
        updateBars()
    }

    private fun bindPanes() {
        panes.forEach {
            val path = session.pathOf(it.pane)
            it.bind(
                CompareTexts.infoLine(this, session.paths.indexOf(path) + 1, session.size, infos[path]),
                CompareTexts.scoreLine(this, scores[path], path),
                path in session.marked,
                tournament.active,
                ui.overlays,
            )
        }
    }

    // po zmene značiek: panely, pás, mriežka, lišty
    private fun marksChanged(vararg paths: String) {
        bindPanes()
        if (paths.isEmpty()) strip.notifyDataSetChanged() else strip.refresh(*paths)
        grid?.notifyDataSetChanged()
        updateBars()
    }

    private fun updateBars() {
        val firstTaken = session.paths.mapNotNull { infos[it]?.taken }.filter { it > 0L }.minOrNull()
        chrome.header(
            CompareTexts.title(this, duplicates, session.size, firstTaken, groupPosition, groupCount),
            session.marked.size.takeIf { ui.selection },
        )
        val marked = session.paths.filter { it in session.marked }
        val best = bestPath()
        val open = finishAfterUndo == FINISH_NONE
        chrome.bottom(
            BarModel(
                bin = trash.usesBin,
                markedCount = marked.size,
                markedBytes = bytesOf(marked),
                restBytes = bytesOf(session.paths.filter { it != best }),
                keepBestEnabled = best != null && open,
                deleteEnabled = marked.isNotEmpty() && open,
                sync = ui.sync,
                duelActive = tournament.active,
                duelMatch = tournament.played + 1,
                duelTotal = tournament.total,
                gridAvailable = session.size > GRID_MIN,
                gridOn = gridSpan() > 0,
                infoOpen = ui.infoOpen,
                hasNextGroup = hasNextGroup,
            ),
        )
        infoPanel.update(
            ui.infoOpen && gridSpan() == 0,
            infos[session.a],
            infos[session.b],
            scores[session.a]?.takeIf { analysisDone } to scores[session.b]?.takeIf { analysisDone },
            (binding.root.height * INFO_MAX_HEIGHT).toInt(),
        )
    }

    private fun bestPath(): String? = session.best?.takeIf { analysisDone && it in session.paths }

    private fun bytesOf(paths: Collection<String>): Long = paths.sumOf { infos[it]?.bytes ?: 0L }

    // --- údaje a skóre (CompareRunner) ---

    private fun infosLoaded(infos: Map<String, PhotoInfo>) {
        this.infos.putAll(infos)
        panes.forEach { it.updateInfo(this.infos[session.pathOf(it.pane)]) }
        arrangePanes()
        bindPanes()
        updateBars()
    }

    // priebežné výsledky bez ★ (poradie by skákalo); po dokončení ★ a zdôvodnenia — len štítky, priblíženie ostáva
    private fun scoresReady(scores: Map<String, PhotoScore>, final: Boolean) {
        val current = scores.filterKeys { it in session.paths }
        this.scores = if (final) {
            current
        } else {
            current.mapValues { it.value.copy(rank = PARTIAL_RANK, reasons = emptyList()) }
        }
        analysisDone = final
        strip.scores = this.scores
        strip.bestKnown = final
        grid?.scores = this.scores
        grid?.bestKnown = final
        if (final) {
            chrome.progress(1, 1)
            if (!facesNoteShown && current.values.any { !it.facesChecked }) {
                facesNoteShown = true
                toast(R.string.pa_faces_skipped)
            }
            val (best, second) = CompareTexts.bestTwo(current)
            // ★ do A a druhá najlepšia do B, len kým používateľ sám nič neprepol
            if (!tournament.active && session.placeBest(best, second)) refresh() else session.best = best
        }
        bindPanes()
        strip.notifyDataSetChanged()
        grid?.notifyDataSetChanged()
        updateBars()
    }

    // --- panely ---

    private fun syncFrom(pane: ComparePane) {
        if (!ui.sync || syncing) return
        syncing = true
        try {
            panes.first { it !== pane }.applyRelative(pane.relativeView())
        } finally {
            syncing = false
        }
    }

    private fun paneAction(pane: ComparePane, action: ComparePane.PaneAction) {
        val path = session.pathOf(pane.pane)
        when (action) {
            ComparePane.PaneAction.PREV -> step(pane.pane, -1)
            ComparePane.PaneAction.NEXT -> step(pane.pane, 1)
            ComparePane.PaneAction.KEEP -> if (session.marked.remove(path)) marksChanged(path)
            ComparePane.PaneAction.BIN -> if (session.marked.add(path)) marksChanged(path)
            ComparePane.PaneAction.WIN -> duelPick(pane.pane)
            ComparePane.PaneAction.TAP -> {
                ui = ui.copy(overlays = !ui.overlays)
                bindPanes()
            }
        }
    }

    private fun step(pane: Pane, dir: Int) {
        if (tournament.active || !session.step(pane, dir)) return
        refresh()
        binding.compareStrip.scrollToPosition(session.paths.indexOf(session.pathOf(pane)))
    }

    private fun toggleSync() {
        ui = ui.copy(sync = !ui.sync)
        updateBars()
        toast(if (ui.sync) R.string.pa_sync_on else R.string.pa_sync_off)
        if (ui.sync) syncFrom(panes[0])
    }

    // --- pás, výberový režim, ponuka ---

    private fun onStripTap(path: String) {
        when {
            ui.selection -> toggleMark(path)
            tournament.active -> toast(R.string.pa_duel_locked)
            else -> {
                session.tap(path)
                refresh()
            }
        }
    }

    // podržanie = výberový režim (ďalšie ťuky prepínajú značku); počas turnaja sa panely nemenia
    private fun onStripLong(path: String) {
        if (tournament.active) {
            toast(R.string.pa_duel_locked)
            return
        }
        setSelection(true)
        toggleMark(path)
    }

    private fun setSelection(on: Boolean) {
        if (ui.selection == on) return
        ui = ui.copy(selection = on)
        if (on) toast(R.string.pa_selection_hint)
        updateBars()
    }

    private fun toggleMark(path: String) {
        session.toggleMark(path)
        marksChanged(path)
    }

    // pôvodná ponuka z podržania ostáva ako položka ⋮
    private fun showMoreMenu() {
        val a = session.a
        val actions = ArrayList<Pair<Int, () -> Unit>>()
        actions.add(R.string.pa_menu_keep_only to { keepOnly(a) })
        bestPath()?.let { best -> actions.add(R.string.pa_menu_keep_best to { keepOnly(best) }) }
        actions.add(R.string.pa_menu_toggle to { toggleMark(a) })
        if (!tournament.active) actions.add(R.string.pa_menu_select to { setSelection(true) })
        actions.add(R.string.pa_menu_clear to { clearMarks() })
        actions.add(R.string.pa_menu_legend to { showLegend() })
        val title = binding.compareTitle.text.toString()
        CompareOptions.show(this, title, actions.map { getString(it.first) }) { actions[it].second() }
    }

    private fun keepOnly(path: String) {
        session.keepOnly(path)
        marksChanged()
        toast(getString(R.string.pa_kept_only, session.marked.size))
    }

    private fun clearMarks() {
        session.marked.clear()
        marksChanged()
    }

    private fun showLegend() {
        val legend = getString(R.string.pa_score_legend)
        ConfirmationDialog(this, legend, positive = org.fossify.commons.R.string.ok, negative = 0) {}
    }

    // --- mriežka 2×2 / 3×3 (len pre skupiny nad GRID_MIN fotiek) ---

    private fun gridSpan(): Int = ui.gridSpan.takeIf { session.size > GRID_MIN } ?: 0

    private fun nextGridSpan(): Int = when (ui.gridSpan) {
        0 -> GRID_SMALL
        GRID_SMALL -> GRID_LARGE
        else -> 0
    }

    private fun setGrid(span: Int) {
        if (span > 0 && ui.gridSpan == 0) toast(R.string.pa_grid_hint)
        ui = ui.copy(gridSpan = span)
        applyLayout()
        refresh()
    }

    private fun applyGrid() {
        val span = gridSpan()
        val adapter = grid ?: CompareGridAdapter(this, session, ::toggleMark, ::onGridOpen).also {
            grid = it
            binding.compareGrid.adapter = it
        }
        adapter.scores = scores
        adapter.bestKnown = analysisDone
        (binding.compareGrid.layoutManager as? GridLayoutManager)?.spanCount = span
        binding.compareGrid.post {
            adapter.cellHeight = binding.compareGrid.height / span
            adapter.notifyDataSetChanged()
        }
    }

    // dvojťuk v mriežke: fotka do panela A a späť na porovnanie
    private fun onGridOpen(path: String) {
        session.tap(path)
        setGrid(0)
    }

    // --- turnaj ---

    private fun startDuel() {
        if (!tournament.start()) {
            toast(R.string.pa_duel_need_two)
            return
        }
        ui = ui.copy(selection = false, gridSpan = 0)
        applyLayout()
        refresh()
    }

    private fun stopDuel() {
        tournament.stop()
        refresh()
    }

    private fun duelPick(winner: Pane) {
        if (!tournament.active) return
        val goesOn = tournament.pick(winner)
        refresh()
        if (!goesOn) toast(getString(R.string.pa_duel_done, session.marked.size))
    }

    // --- mazanie, „Späť“ a ďalšia skupina ---

    private fun trashMarked() {
        keepBestPending = false
        val marked = session.paths.filter { it in session.marked }
        trash.confirm(marked, bytesOf(marked))
    }

    // „Ponechať ★, ostatné do Koša“: značky sa nemenia, kým to používateľ nepotvrdí
    private fun keepBest() {
        val best = bestPath() ?: return
        val rest = session.paths.filter { it != best }
        keepBestPending = true
        trash.confirm(rest, bytesOf(rest))
    }

    private fun trashed(gone: List<String>, toBin: Boolean) {
        val keepBestDone = keepBestPending
        keepBestPending = false
        if (gone.isEmpty()) return
        val indexed = gone.map { it to session.paths.indexOf(it) }
        val bytes = bytesOf(gone)
        val enough = tournament.remove(gone)
        val finish = when {
            enough -> FINISH_NONE
            keepBestDone && hasNextGroup -> FINISH_NEXT
            else -> FINISH_LIST
        }
        if (!toBin) {
            if (finish == FINISH_NONE) afterChange() else finishGroup(finish)
            return
        }
        // pri Koši sa dá 8 s vrátiť; až potom sa prípadne skupina zavrie
        lastTrashed = indexed
        finishAfterUndo = finish
        if (enough) afterChange() else updateBars()
        chrome.undo(getString(R.string.pa_moved_to_bin, gone.size, bytes.formatSize()))
        handler.removeCallbacks(undoTimeout)
        handler.postDelayed(undoTimeout, UNDO_MS)
    }

    private fun afterChange() {
        scores = scores.filterKeys { it in session.paths }
        refresh()
        runner.start(session.paths.toList(), withInfos = false)
    }

    private fun undo() {
        val items = lastTrashed
        if (items.isEmpty()) return
        handler.removeCallbacks(undoTimeout)
        chrome.undo(null)
        trash.restore(items.map { it.first })
    }

    private fun restoredFromBin(restored: List<String>) {
        val items = lastTrashed.filter { it.first in restored }
        lastTrashed = emptyList()
        finishAfterUndo = FINISH_NONE
        session.restore(items)
        toast(getString(R.string.pa_restored, items.size))
        if (session.size < 2) finishGroup(FINISH_LIST) else afterChange()
    }

    private fun onUndoTimeout() {
        chrome.undo(null)
        lastTrashed = emptyList()
        val finish = finishAfterUndo
        finishAfterUndo = FINISH_NONE
        if (finish == FINISH_NONE) updateBars() else finishGroup(finish)
    }

    private fun finishGroup(how: Int) {
        if (how == FINISH_NEXT) {
            openNextGroup()
        } else {
            toast(R.string.pa_all_done)
            finish()
        }
    }

    // zoznam skupín (CompareListActivity) po RESULT_OPEN_NEXT hneď otvorí ďalšiu skupinu
    private fun openNextGroup() {
        handler.removeCallbacks(undoTimeout)
        setResult(RESULT_OK, Intent().putExtra(CompareListActivity.RESULT_OPEN_NEXT, true))
        finish()
    }

    // --- späť a klávesnica ---

    override fun onBackPressedCompat(): Boolean {
        when {
            !::panes.isInitialized -> return false
            ui.selection -> setSelection(false)
            ui.infoOpen -> {
                ui = ui.copy(infoOpen = false)
                updateBars()
            }
            gridSpan() > 0 -> setGrid(0)
            tournament.active -> stopDuel()
            else -> return false
        }
        return true
    }

    // scrcpy / klávesnica z PC: ←/→ = fotka v paneli A (v turnaji víťaz), X/Del = označ A, K = ponechať len A,
    // Enter = do Koša označené (vždy cez potvrdenie)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::panes.isInitialized) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (tournament.active) duelPick(Pane.B) else step(Pane.A, 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (tournament.active) duelPick(Pane.A) else step(Pane.A, -1)
            KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> toggleMark(session.a)
            KeyEvent.KEYCODE_K -> keepOnly(session.a)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (event?.repeatCount == 0) trashMarked()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private companion object {
        const val UNDO_MS = 8000L
        const val GRID_MIN = 4
        const val GRID_SMALL = 2
        const val GRID_LARGE = 3
        const val PARTIAL_RANK = 1
        const val INFO_MAX_HEIGHT = 0.4f
        const val FINISH_NONE = 0
        const val FINISH_LIST = 1
        const val FINISH_NEXT = 2
    }
}
