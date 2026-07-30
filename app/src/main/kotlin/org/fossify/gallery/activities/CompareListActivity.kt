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
import org.fossify.gallery.R
import org.fossify.gallery.adapters.CompareGroupsAdapter
import org.fossify.gallery.databinding.ActivityCompareListBinding
import org.fossify.gallery.faces.PhashDatabase
import org.fossify.gallery.faces.PhashGrouper
import org.fossify.gallery.helpers.PathTransfer

// Zoznam skupín podobných/burst fotiek = fotky z toho istého priečinka nasnímané v rozpätí ~3 s.
class CompareListActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityCompareListBinding::inflate)
    private var duplicatesMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.compareListGrid.layoutManager = GridLayoutManager(this, COLUMNS)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.compareListAppbar, NavigationIcon.Arrow)
        binding.compareListToolbar.menu.clear()
        binding.compareListToolbar.inflateMenu(R.menu.menu_compare_list)
        binding.compareListToolbar.menu.findItem(R.id.toggle_duplicates)?.isChecked = duplicatesMode
        binding.compareListToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.toggle_duplicates) {
                duplicatesMode = !duplicatesMode
                item.isChecked = duplicatesMode
                updateTitle()
                loadGroups()
                true
            } else {
                false
            }
        }
        updateTitle()
        loadGroups()
    }

    private fun updateTitle() {
        binding.compareListToolbar.title =
            getString(if (duplicatesMode) R.string.compare_mode_duplicates else R.string.compare_title)
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
        return if (duplicatesMode) {
            val hashes = PhashDatabase.getInstance(this).PhashDao().getAllHashes()
            PhashGrouper.groupBySimilarity(hashes)
        } else {
            buildBurstGroups()
        }
    }

    private fun buildBurstGroups(): List<List<String>> {
        val paths = ArrayList<String>()
        val takens = ArrayList<Long>()
        val folders = ArrayList<String>()
        val groupIds = ArrayList<String>()
        val proj = ArrayList<String>().apply {
            add(MediaStore.Images.Media.DATA)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_MODIFIED)
            add(MediaStore.Images.Media.DISPLAY_NAME)
        }
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj.toTypedArray(), null, null, null,
        )?.use { c ->
            val dData = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val dTaken = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val dMod = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val dName = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val path = c.getString(dData) ?: continue
                val taken = if (dTaken >= 0) c.getLong(dTaken) else 0L
                val mod = if (dMod >= 0) c.getLong(dMod) * 1000L else 0L
                paths.add(path)
                takens.add(if (taken > 0) taken else mod)
                folders.add(path.substringBeforeLast('/'))
                // séria podľa názvu súboru: IMG_1234.BURST001 / IMG20240101_BURST2 / ..._COVER
                var gid = ""
                if (dName >= 0) {
                    val name = c.getString(dName).orEmpty()
                    val m = BURST_NAME.find(name)
                    if (m != null) gid = "burst:" + m.groupValues[1]
                }
                groupIds.add(gid)
            }
        }

        val groups = ArrayList<MutableList<String>>()
        val used = BooleanArray(paths.size)

        // A) presné série podľa názvu súboru (bez ohľadu na časové medzery)
        val byGroup = HashMap<String, MutableList<Int>>()
        for (i in paths.indices) {
            val g = groupIds[i]
            if (g.isNotEmpty()) byGroup.getOrPut(g) { ArrayList() }.add(i)
        }
        for ((_, idxs) in byGroup) {
            if (idxs.size < 2) continue
            val list = idxs.sortedBy { takens[it] }.map { paths[it] }.toMutableList()
            idxs.forEach { used[it] = true }
            groups.add(list)
        }

        // B) zvyšok: fotky z toho istého priečinka nasnímané do ~3 s
        val order = paths.indices.filter { !used[it] }.sortedBy { takens[it] }
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
        // najnovšie série hore
        return groups.filter { it.size >= 2 }.sortedByDescending { g -> g.maxOfOrNull { java.io.File(it).lastModified() } ?: 0L }
    }

    companion object {
        private const val COLUMNS = 3
        private const val GROUP_GAP_MS = 3000L

        // názvy typu IMG_1234.BURST001 / IMG_20240101_120000_BURST2 -> spoločný kľúč série
        private val BURST_NAME = Regex("""^(.*?)[._-]?BURST\d+""", RegexOption.IGNORE_CASE)
    }
}
