package org.fossify.gallery.activities

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.alexvasilkov.gestures.GestureController
import com.alexvasilkov.gestures.GestureImageView
import com.alexvasilkov.gestures.State
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.isAccessibleWithSAFSdk30
import org.fossify.commons.extensions.needsStupidWritePermissions
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.MyTextView
import org.fossify.gallery.R
import org.fossify.gallery.adapters.CompareStripAdapter
import org.fossify.gallery.databinding.ActivityComparatorBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.helpers.PathTransfer
import org.fossify.gallery.helpers.Sharpness
import java.io.File

// Porovnávač podobných/burst fotiek: 2 panely so synchronizovaným zoomom, ★ najostrejšia, podrž = označ, vymaž.
class ComparatorActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityComparatorBinding::inflate)
    private var paths = ArrayList<String>()
    private var sharp = HashMap<String, Double>()
    private var quality = HashMap<String, org.fossify.gallery.faces.PhotoQuality.Result>()
    private var totalScore = HashMap<String, Double>()
    private var pq: org.fossify.gallery.faces.PhotoQuality? = null
    private var bestIndex = -1
    private var leftIndex = 0
    private var rightIndex = 0
    private var syncing = false
    private var syncEnabled = true
    private var duelMode = false
    private var duelNext = 1
    private var adapter: CompareStripAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        // jednotne s ostatnými obrazovkami: odsadenie pod stavový a navigačný pruh rieši setupEdgeToEdge
        setupEdgeToEdge(
            padTopSystem = listOf(binding.compareTopbar),
            padBottomSystem = listOf(binding.compareBottombar),
        )
        applyBarColors()
        paths = ArrayList(PathTransfer.forCompare ?: emptyList())
        PathTransfer.forCompare = null
        if (paths.size < 2) {
            finish()
            return
        }
        binding.compareStrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.compareBack.setOnClickListener { finish() }
        binding.compareDelete.text = getString(R.string.compare_delete)
        binding.compareDelete.setOnClickListener { deleteMarked() }
        binding.compareTitle.text = getString(R.string.compare_hint)
        configurePanes()
        setupSync()
        binding.compareSync.setOnClickListener { toggleSync() }
        binding.compareDuel.setOnClickListener { toggleDuel() }
        binding.compareLeftPrev.setOnClickListener { stepPane(true, -1) }
        binding.compareLeftNext.setOnClickListener { stepPane(true, 1) }
        binding.compareRightPrev.setOnClickListener { stepPane(false, -1) }
        binding.compareRightNext.setOnClickListener { stepPane(false, 1) }
        binding.compareLeftWin.setOnClickListener { duelPick(true) }
        binding.compareRightWin.setOnClickListener { duelPick(false) }
        updateSyncIcon()
        leftIndex = 0
        rightIndex = 1
        rebuildAdapter()
        loadPane(true)
        loadPane(false)
        updateActive()
        updateInfo()
        computeSharpness()
    }

    // horná a dolná lišta v akcente témy (nie pevná zelená z commons); text a ikony v kontrastnej farbe
    private fun applyBarColors() {
        val barColor = getProperPrimaryColor()
        val fg = barColor.getContrastColor()
        binding.compareTopbar.setBackgroundColor(barColor)
        binding.compareBottombar.setBackgroundColor(barColor)
        binding.compareTitle.setTextColor(fg)
        binding.compareInfo.setTextColor(fg)
        listOf(binding.compareBack, binding.compareSync, binding.compareDuel).forEach {
            ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(fg))
        }
    }

    private fun toggleSync() {
        syncEnabled = !syncEnabled
        updateSyncIcon()
        toast(if (syncEnabled) R.string.compare_sync_on else R.string.compare_sync_off)
    }

    private fun updateSyncIcon() {
        binding.compareSync.alpha = if (syncEnabled) 1f else 0.35f
    }

    private fun updateActive() {
        adapter?.setActive(leftIndex, rightIndex)
    }

    // šípky: posun panela ZARADOM podľa zoznamu dole; fotku zobrazenú v druhom paneli PRESKOČÍ
    // (Ľ a P tak nikdy nie sú tá istá fotka)
    private fun stepPane(left: Boolean, dir: Int) {
        if (duelMode) return
        val other = if (left) rightIndex else leftIndex
        var next = if (left) leftIndex else rightIndex
        do {
            next += dir
        } while (next in paths.indices && next == other)
        if (next !in paths.indices) return
        if (left) leftIndex = next else rightIndex = next
        loadPane(left, keepZoom = true)
        updateActive()
        binding.compareStrip.scrollToPosition(next)
    }

    private fun toggleDuel() {
        if (duelMode) exitDuel(false) else enterDuel()
    }

    private fun enterDuel() {
        if (paths.size < 2) return
        duelMode = true
        leftIndex = 0
        duelNext = 1
        rightIndex = 1
        binding.compareLeftWin.visibility = View.VISIBLE
        binding.compareRightWin.visibility = View.VISIBLE
        binding.compareTitle.text = getString(R.string.compare_duel_hint)
        loadPane(true)
        loadPane(false)
        updateActive()
    }

    // víťaz ostáva vľavo, porazený sa označí na zmazanie a nastúpi ďalší (štýl "tinder")
    private fun duelPick(leftWins: Boolean) {
        if (!duelMode) return
        val loser = if (leftWins) rightIndex else leftIndex
        val winner = if (leftWins) leftIndex else rightIndex
        adapter?.let { if (!it.marked.contains(loser)) it.toggleMark(loser) }
        leftIndex = winner
        duelNext++
        while (duelNext <= paths.lastIndex && adapter?.marked?.contains(duelNext) == true) duelNext++
        if (duelNext > paths.lastIndex) {
            exitDuel(true)
            return
        }
        rightIndex = duelNext
        loadPane(true)
        loadPane(false)
        updateActive()
        updateInfo()
    }

    private fun exitDuel(finished: Boolean) {
        duelMode = false
        binding.compareLeftWin.visibility = View.GONE
        binding.compareRightWin.visibility = View.GONE
        binding.compareTitle.text = getString(R.string.compare_hint)
        updateInfo()
        if (finished) toast(getString(R.string.compare_duel_done, adapter?.marked?.size ?: 0))
    }

    private fun rebuildAdapter() {
        val a = CompareStripAdapter(this, paths, onTap = { pos -> onStripTap(pos) }, onLong = { pos -> onStripLong(pos) })
        a.bestIndex = bestIndex
        adapter = a
        binding.compareStrip.adapter = a
    }

    // ostrosť + expozícia + tváre (zavreté oči, úsmev) -> celkové skóre; ★ = najlepšia podľa skóre
    private fun computeSharpness() {
        ensureBackgroundThread {
            val map = HashMap<String, Double>()
            val qmap = HashMap<String, org.fossify.gallery.faces.PhotoQuality.Result>()
            val analyzer = pq ?: try {
                org.fossify.gallery.faces.PhotoQuality(this)
            } catch (e: Throwable) {
                null
            }?.also { pq = it }
            for (p in paths) {
                map[p] = try {
                    Sharpness.score(p)
                } catch (e: Throwable) {
                    0.0
                }
                analyzer?.let {
                    try {
                        qmap[p] = it.analyze(p)
                    } catch (ignored: Throwable) {
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                sharp = map
                quality = qmap
                recomputeScores()
                adapter?.bestIndex = bestIndex
                adapter?.warns = paths.indices.filter { (quality[paths[it]]?.closedEyes ?: 0) > 0 }.toSet()
                adapter?.scores = paths.indices.associateWith { ((totalScore[paths[it]] ?: 0.0) * 100).toInt() }
                adapter?.notifyDataSetChanged()
                loadPane(true)
                loadPane(false)
            }
        }
    }

    // váhy: ostrosť 55 % + expozícia 15 % + otvorené oči 25 % + bonus za úsmev 5 %
    private fun recomputeScores() {
        val maxS = sharp.values.maxOrNull() ?: 0.0
        totalScore.clear()
        for (p in paths) {
            val sn = if (maxS > 0) (sharp[p] ?: 0.0) / maxS else 0.0
            val q = quality[p]
            totalScore[p] = if (q == null) {
                sn
            } else {
                val openRatio = if (q.faces > 0) 1.0 - q.closedEyes.toDouble() / q.faces else 1.0
                val smileBonus = if (q.faces > 0 && q.smiles > 0) 0.05 else 0.0
                (0.55 * sn + 0.15 * q.exposure + 0.25 * openRatio + smileBonus).coerceIn(0.0, 1.0)
            }
        }
        bestIndex = paths.indices.maxByOrNull { totalScore[paths[it]] ?: 0.0 } ?: -1
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            pq?.close()
        } catch (ignored: Throwable) {
        }
        pq = null
    }

    // ťuk na fotku vo filmstripe -> zobrazí sa VĽAVO (Ľ), doterajšia ľavá sa presunie VPRAVO (P)
    private fun onStripTap(pos: Int) {
        if (duelMode) return
        if (pos == leftIndex) return
        rightIndex = leftIndex
        leftIndex = pos
        loadPane(true, keepZoom = true)
        loadPane(false, keepZoom = true)
        updateActive()
    }

    // podržanie fotky vo filmstripe -> jasná ponuka namiesto tichého prepínania. Podržanie je tu výber
    // (označenie na zmazanie, vratné); ponuka nesie aj hromadné „Ponechať len túto", ktoré nemá iné miesto.
    private fun onStripLong(pos: Int) {
        val a = adapter ?: return
        val name = paths.getOrNull(pos)?.substringAfterLast('/') ?: return
        val markLabel = getString(if (a.marked.contains(pos)) R.string.compare_unmark else R.string.compare_mark_delete)
        val options = listOf(getString(R.string.compare_keep_only_this), markLabel)
        showOptionsDialog(name, options) { which ->
            when (which) {
                0 -> {
                    a.markAllExcept(pos)
                    // ponechaná fotka rovno do ľavého panela, nech vidí čo ostáva
                    rightIndex = leftIndex.takeIf { it != pos } ?: rightIndex
                    leftIndex = pos
                    loadPane(true, keepZoom = true)
                    loadPane(false, keepZoom = true)
                    updateActive()
                    updateInfo()
                    toast(getString(R.string.compare_kept_only, a.marked.size))
                }
                1 -> {
                    a.toggleMark(pos)
                    updateInfo()
                }
            }
        }
    }

    // Zoznam akcií s dynamickým titulkom (názov súboru) — témovaný dialóg cez getAlertDialogBuilder +
    // setupDialogStuff, rovnaký vzor ako PeopleActivity.showOptionsDialog (nie surový AlertDialog.Builder).
    private fun showOptionsDialog(title: String, options: List<String>, onPick: (Int) -> Unit) {
        val margin = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, margin / 2, 0, margin / 2)
        }
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        var dialog: AlertDialog? = null
        options.forEachIndexed { index, option ->
            holder.addView(
                MyTextView(this).apply {
                    text = option
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setPadding(margin, margin * 3 / 4, margin, margin * 3 / 4)
                    setBackgroundResource(ripple.resourceId)
                    setOnClickListener {
                        dialog?.dismiss()
                        onPick(index)
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        val view = ScrollView(this).apply { addView(holder) }
        getAlertDialogBuilder().apply {
            setupDialogStuff(view, this, titleText = title) { alertDialog -> dialog = alertDialog }
        }
    }

    private fun loadPane(left: Boolean, keepZoom: Boolean = false) {
        val index = if (left) leftIndex else rightIndex
        if (index !in paths.indices) return
        val view = if (left) binding.compareLeft else binding.compareRight
        val label = if (left) binding.compareLeftLabel else binding.compareRightLabel
        // pri prepínaní fotiek zachovaj priblíženie/posun panela -> porovnanie toho istého výrezu
        val saved = if (keepZoom) State().apply { set(view.controller.state) } else null
        Glide.with(this).load(File(paths[index]))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean) = false
                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    if (saved != null) {
                        view.post {
                            try {
                                view.controller.state.set(saved)
                                view.controller.updateState()
                            } catch (e: Throwable) {
                            }
                        }
                    }
                    return false
                }
            })
            .into(view)
        val name = paths[index].substringAfterLast('/')
        val side = if (left) "Ľ · " else "P · "
        val score = totalScore[paths[index]]
        if (score == null) {
            label.text = side + name
            return
        }
        val q = quality[paths[index]]
        val icons = buildString {
            if (q != null && q.faces > 0) {
                if (q.smiles > 0) append(" 😊")
                if (q.closedEyes > 0) append(" 😑${if (q.closedEyes > 1) q.closedEyes else ""}")
            }
        }
        val star = if (index == bestIndex) " ★" else ""
        label.text = side + getString(R.string.compare_pane_label, name, (score * 100).toInt()) + icons + star
    }

    private fun updateInfo() {
        binding.compareInfo.text = getString(R.string.compare_marked, adapter?.marked?.size ?: 0)
    }

    // väčší rozsah priblíženia pre porovnanie detailu
    private fun configurePanes() {
        listOf(binding.compareLeft, binding.compareRight).forEach { v ->
            try {
                v.controller.settings.maxZoom = 12f
                v.controller.settings.doubleTapZoom = 4f
            } catch (e: Throwable) {
            }
        }
    }

    private fun setupSync() {
        binding.compareLeft.controller.addOnStateChangeListener(object : GestureController.OnStateChangeListener {
            override fun onStateChanged(state: State) = mirror(binding.compareRight, state)
        })
        binding.compareRight.controller.addOnStateChangeListener(object : GestureController.OnStateChangeListener {
            override fun onStateChanged(state: State) = mirror(binding.compareLeft, state)
        })
    }

    private fun mirror(to: GestureImageView, state: State) {
        if (syncing || !syncEnabled) return
        syncing = true
        try {
            to.controller.state.set(state)
            to.controller.updateState()
        } catch (e: Throwable) {
        }
        syncing = false
    }

    private fun deleteMarked() {
        val markedPaths = adapter?.markedPaths() ?: emptyList()
        if (markedPaths.isEmpty()) {
            toast(R.string.compare_marked_none)
            return
        }
        confirmDelete(ArrayList(markedPaths))
    }

    // rovnaký vzor ako PhotoPathsAdapter/MediaAdapter: potvrdenie s počtom a veľkosťou; pri zapnutom
    // Koši sa text aj samotné mazanie riadi nastavením (fotky idú do Koša, nie natrvalo preč)
    private fun confirmDelete(toDelete: ArrayList<String>) {
        val itemsText = if (toDelete.size == 1) {
            "\"${toDelete.first().getFilenameFromPath()}\""
        } else {
            val size = toDelete.sumOf { File(it).length() }.formatSize()
            val count = resources.getQuantityString(org.fossify.commons.R.plurals.delete_items, toDelete.size, toDelete.size)
            "$count ($size)"
        }
        val baseString = if (config.useRecycleBin) {
            org.fossify.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            org.fossify.commons.R.string.deletion_confirmation
        }
        ConfirmationDialog(this, String.format(resources.getString(baseString), itemsText)) {
            deleteConfirmed(toDelete)
        }
    }

    private fun deleteConfirmed(toDelete: ArrayList<String>) {
        val safPath = toDelete.firstOrNull { needsStupidWritePermissions(it) } ?: toDelete.first()
        handleSAFDialog(safPath) { safOk ->
            if (!safOk) return@handleSAFDialog
            val sdk30Path = toDelete.firstOrNull { isAccessibleWithSAFSdk30(it) } ?: toDelete.first()
            checkManageMediaOrHandleSAFDialogSdk30(sdk30Path) { manageOk ->
                if (!manageOk) return@checkManageMediaOrHandleSAFDialogSdk30
                if (config.useRecycleBin) {
                    toast(resources.getQuantityString(org.fossify.commons.R.plurals.moving_items_into_bin, toDelete.size, toDelete.size))
                    movePathsInRecycleBin(toDelete) { moved ->
                        if (moved) {
                            deleteOriginals(toDelete, wasMovedToBin = true)
                        } else {
                            toast(org.fossify.commons.R.string.unknown_error_occurred)
                        }
                    }
                } else {
                    toast(resources.getQuantityString(org.fossify.commons.R.plurals.deleting_items, toDelete.size, toDelete.size))
                    deleteOriginals(toDelete, wasMovedToBin = false)
                }
            }
        }
    }

    // zmaže originály zo zdieľanej cesty appky (rieši SAF aj scoped storage); zo zoznamu odoberie len
    // fotky, ktoré na disku naozaj zmizli, takže čiastočné zlyhanie nenechá v porovnávači duchov
    private fun deleteOriginals(toDelete: List<String>, wasMovedToBin: Boolean) {
        val items = toDelete.map { FileDirItem(it, it.getFilenameFromPath()) }
        deleteFiles(items) { success ->
            ensureBackgroundThread {
                val gone = toDelete.filter { !getDoesFilePathExist(it) }
                // pri Koši riadok v DB už prešiel na cestu v Koši (movePathsInRecycleBin), pri trvalom mazaní ho zmaž
                if (!wasMovedToBin) gone.forEach { deleteDBPath(it) }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    if (!success) toast(org.fossify.commons.R.string.unknown_error_occurred)
                    removeDeleted(gone)
                }
            }
        }
    }

    private fun removeDeleted(removed: List<String>) {
        if (removed.isEmpty()) return
        paths.removeAll(removed.toSet())
        if (paths.size < 2) {
            toast(R.string.compare_done)
            finish()
            return
        }
        leftIndex = leftIndex.coerceIn(0, paths.lastIndex)
        rightIndex = rightIndex.coerceIn(0, paths.lastIndex)
        if (leftIndex == rightIndex) rightIndex = (leftIndex + 1) % paths.size
        sharp = HashMap(sharp.filterKeys { paths.contains(it) })
        quality = HashMap(quality.filterKeys { paths.contains(it) })
        recomputeScores()
        rebuildAdapter()
        adapter?.warns = paths.indices.filter { (quality[paths[it]]?.closedEyes ?: 0) > 0 }.toSet()
        adapter?.scores = paths.indices.associateWith { ((totalScore[paths[it]] ?: 0.0) * 100).toInt() }
        loadPane(true)
        loadPane(false)
        updateActive()
        updateInfo()
    }

    // scrcpy / hardvérová klávesnica z PC: rýchle triedenie skupiny bez myši.
    // ←/→ = prechádzanie (ďalšia fotka do ľavého panela), X/Del = označ ľavú, K = ponechať len ľavú,
    // Enter = vymazať označené. V turnajovom režime ←/→ = vyber víťaza.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (duelMode) duelPick(false) else advanceLeft(1)
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (duelMode) duelPick(true) else advanceLeft(-1)
                return true
            }
            android.view.KeyEvent.KEYCODE_X, android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.KEYCODE_FORWARD_DEL -> {
                adapter?.toggleMark(leftIndex)
                updateInfo()
                return true
            }
            android.view.KeyEvent.KEYCODE_K -> {
                adapter?.let { a ->
                    a.markAllExcept(leftIndex)
                    updateInfo()
                    toast(getString(R.string.compare_kept_only, a.marked.size))
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // vždy cez potvrdenie a Kôš (deleteMarked); podržaný kláves dialóg nenaskladá opakovane
                if (event?.repeatCount == 0) deleteMarked()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // klávesové šípky (aj scrcpy): mení fotku ľavého panela zaradom, pravý panel ostáva ako referencia
    private fun advanceLeft(dir: Int) = stepPane(true, dir)
}
