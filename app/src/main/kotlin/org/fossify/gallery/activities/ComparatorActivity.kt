package org.fossify.gallery.activities

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.alexvasilkov.gestures.GestureController
import com.alexvasilkov.gestures.GestureImageView
import android.graphics.drawable.Drawable
import com.alexvasilkov.gestures.State
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.gallery.R
import org.fossify.gallery.adapters.CompareStripAdapter
import org.fossify.gallery.databinding.ActivityComparatorBinding
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.helpers.PathTransfer
import org.fossify.gallery.helpers.Sharpness
import java.io.File

// Porovnávač podobných/burst fotiek: 2 panely so synchronizovaným zoomom, ★ najostrejšia, podrž = označ, vymaž.
class ComparatorActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityComparatorBinding::inflate)
    private var paths = ArrayList<String>()
    private var sharp = HashMap<String, Double>()
    private var bestIndex = -1
    private var leftIndex = 0
    private var rightIndex = 0
    private var syncing = false
    private var syncEnabled = true
    private var duelMode = false
    private var duelNext = 1
    private var adapter: CompareStripAdapter? = null
    private var pendingDelete: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        applyInsets()
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

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.comparatorRoot) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.compareTopbar.updatePadding(top = sb.top)
            binding.compareBottombar.updatePadding(bottom = sb.bottom)
            insets
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

    // šípky pod panelom: posun konkrétny panel na predošlú/ďalšiu fotku (deterministicky, aj pri priblížení)
    private fun stepPane(left: Boolean, dir: Int) {
        if (duelMode) return
        val cur = if (left) leftIndex else rightIndex
        val next = (cur + dir).coerceIn(0, paths.lastIndex)
        if (next == cur) return
        if (left) leftIndex = next else rightIndex = next
        loadPane(left, keepZoom = true)
        updateActive()
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

    private fun computeSharpness() {
        ensureBackgroundThread {
            val map = HashMap<String, Double>()
            for (p in paths) map[p] = try {
                Sharpness.score(p)
            } catch (e: Throwable) {
                0.0
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                sharp = map
                bestIndex = paths.indices.maxByOrNull { sharp[paths[it]] ?: 0.0 } ?: -1
                adapter?.bestIndex = bestIndex
                adapter?.notifyDataSetChanged()
                loadPane(true)
                loadPane(false)
            }
        }
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

    private fun onStripLong(pos: Int) {
        adapter?.toggleMark(pos)
        updateInfo()
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
        if (sharp.isEmpty()) {
            label.text = side + name
            return
        }
        val maxS = sharp.values.maxOrNull() ?: 0.0
        val pct = if (maxS > 0) ((sharp[paths[index]] ?: 0.0) / maxS * 100).toInt() else 0
        val star = if (index == bestIndex) " ★" else ""
        label.text = side + getString(R.string.compare_pane_label, name, pct) + star
    }

    private fun updateInfo() {
        binding.compareInfo.text = getString(R.string.compare_marked, adapter?.marked?.size ?: 0)
    }

    // uvoľni obmedzenie hraníc -> obraz sa po uvoľnení prsta NEPRESKOČÍ späť, ostane kde ho nastavíš
    private fun configurePanes() {
        listOf(binding.compareLeft, binding.compareRight).forEach { v ->
            try {
                v.controller.settings
                    .setMaxZoom(12f)
                    .setDoubleTapZoom(3f)
                    .setBoundsType(com.alexvasilkov.gestures.Settings.Bounds.NONE)
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
        ensureBackgroundThread {
            val uris = markedPaths.mapNotNull { contentUriForPath(it) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (uris.isEmpty()) {
                    toast(R.string.action_failed)
                    return@runOnUiThread
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val pi = MediaStore.createDeleteRequest(contentResolver, uris)
                        pendingDelete = markedPaths
                        startIntentSenderForResult(pi.intentSender, REQ_DELETE, null, 0, 0, 0)
                    } catch (e: Throwable) {
                        toast(R.string.action_failed)
                    }
                } else {
                    var ok = false
                    uris.forEach {
                        try {
                            if (contentResolver.delete(it, null, null) > 0) ok = true
                        } catch (e: Throwable) {
                        }
                    }
                    if (ok) removeDeleted(markedPaths)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE && resultCode == RESULT_OK) {
            removeDeleted(pendingDelete)
        }
        pendingDelete = emptyList()
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
        bestIndex = paths.indices.maxByOrNull { sharp[paths[it]] ?: 0.0 } ?: -1
        rebuildAdapter()
        loadPane(true)
        loadPane(false)
        updateActive()
        updateInfo()
    }

    private fun contentUriForPath(path: String): Uri? {
        return try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DATA} = ?",
                arrayOf(path),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)),
                    )
                } else {
                    null
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    companion object {
        private const val REQ_DELETE = 7013
    }
}
