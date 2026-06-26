package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.adapters.CompareGroupsAdapter
import org.fossify.gallery.databinding.ActivityCompareListBinding
import org.fossify.gallery.helpers.PathTransfer

// Zoznam skupín podobných/burst fotiek = fotky z toho istého priečinka nasnímané v rozpätí ~3 s.
class CompareListActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityCompareListBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.compareListGrid.layoutManager = GridLayoutManager(this, COLUMNS)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.compareListAppbar, NavigationIcon.Arrow)
        loadGroups()
    }

    private fun loadGroups() {
        ensureBackgroundThread {
            val groups = try {
                buildGroups()
            } catch (e: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (groups.isEmpty()) binding.compareListPlaceholder.beVisible() else binding.compareListPlaceholder.beGone()
                binding.compareListGrid.adapter = CompareGroupsAdapter(this, groups) { group -> openComparator(group) }
            }
        }
    }

    private fun openComparator(group: List<String>) {
        PathTransfer.forCompare = group
        startActivity(Intent(this, ComparatorActivity::class.java))
    }

    private fun buildGroups(): List<List<String>> {
        val paths = ArrayList<String>()
        val takens = ArrayList<Long>()
        val folders = ArrayList<String>()
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED),
            null, null, null,
        )?.use { c ->
            val dData = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val dTaken = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val dMod = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val path = c.getString(dData) ?: continue
                val taken = if (dTaken >= 0) c.getLong(dTaken) else 0L
                val mod = if (dMod >= 0) c.getLong(dMod) * 1000L else 0L
                paths.add(path)
                takens.add(if (taken > 0) taken else mod)
                folders.add(path.substringBeforeLast('/'))
            }
        }
        // zoraď podľa času
        val order = paths.indices.sortedBy { takens[it] }
        val groups = ArrayList<MutableList<String>>()
        var cur: MutableList<String>? = null
        var prevTaken = 0L
        var prevFolder = ""
        for (idx in order) {
            val t = takens[idx]
            val f = folders[idx]
            if (cur != null && f == prevFolder && t > 0 && prevTaken > 0 && t - prevTaken <= GROUP_GAP_MS) {
                cur.add(paths[idx])
            } else {
                cur = mutableListOf(paths[idx])
                groups.add(cur)
            }
            prevTaken = t
            prevFolder = f
        }
        return groups.filter { it.size >= 2 }.reversed()
    }

    companion object {
        private const val COLUMNS = 3
        private const val GROUP_GAP_MS = 3000L
    }
}
