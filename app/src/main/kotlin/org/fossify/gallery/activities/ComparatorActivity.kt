package org.fossify.gallery.activities

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.recyclerview.widget.LinearLayoutManager
import com.alexvasilkov.gestures.GestureController
import com.alexvasilkov.gestures.GestureImageView
import com.alexvasilkov.gestures.State
import com.bumptech.glide.Glide
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
    private var nextLeft = true
    private var syncing = false
    private var adapter: CompareStripAdapter? = null
    private var pendingDelete: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
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
        setupSync()
        leftIndex = 0
        rightIndex = 1
        rebuildAdapter()
        loadPane(true)
        loadPane(false)
        updateInfo()
        computeSharpness()
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

    private fun onStripTap(pos: Int) {
        if (nextLeft) {
            leftIndex = pos
            loadPane(true)
        } else {
            rightIndex = pos
            loadPane(false)
        }
        nextLeft = !nextLeft
    }

    private fun onStripLong(pos: Int) {
        adapter?.toggleMark(pos)
        updateInfo()
    }

    private fun loadPane(left: Boolean) {
        val index = if (left) leftIndex else rightIndex
        if (index !in paths.indices) return
        val view = if (left) binding.compareLeft else binding.compareRight
        val label = if (left) binding.compareLeftLabel else binding.compareRightLabel
        Glide.with(this).load(File(paths[index])).into(view)
        val name = paths[index].substringAfterLast('/')
        if (sharp.isEmpty()) {
            label.text = name
            return
        }
        val maxS = sharp.values.maxOrNull() ?: 0.0
        val pct = if (maxS > 0) ((sharp[paths[index]] ?: 0.0) / maxS * 100).toInt() else 0
        val star = if (index == bestIndex) " ★" else ""
        label.text = getString(R.string.compare_pane_label, name, pct) + star
    }

    private fun updateInfo() {
        binding.compareInfo.text = getString(R.string.compare_marked, adapter?.marked?.size ?: 0)
    }

    private fun setupSync() {
        binding.compareLeft.controller.addOnStateChangeListener(object : GestureController.OnStateChangeListener {
            override fun onStateChanged(state: State) = mirror(binding.compareRight, state)
            override fun onStateReset(oldState: State?, newState: State?) {}
        })
        binding.compareRight.controller.addOnStateChangeListener(object : GestureController.OnStateChangeListener {
            override fun onStateChanged(state: State) = mirror(binding.compareLeft, state)
            override fun onStateReset(oldState: State?, newState: State?) {}
        })
    }

    private fun mirror(to: GestureImageView, state: State) {
        if (syncing) return
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
