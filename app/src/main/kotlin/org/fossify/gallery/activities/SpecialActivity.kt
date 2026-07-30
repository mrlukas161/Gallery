package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PersonPhotosAdapter
import org.fossify.gallery.databinding.ActivityDocsBinding
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.PathTransfer
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION
import org.fossify.gallery.helpers.SpecialPhoto

// Album „Špeciálne fotky": panorámy, pohyblivé fotky, HDR a RAW — rozpoznané z metadát.
class SpecialActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDocsBinding::inflate)
    private var found = HashMap<SpecialPhoto.Kind, MutableList<String>>()
    private var shown = ArrayList<String>()
    private var filter: SpecialPhoto.Kind? = null
    @Volatile
    private var scanning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.docsGrid.layoutManager = GridLayoutManager(this, 3)
        binding.docsFastscroller.updateColors(getProperPrimaryColor())
        binding.docsToolbar.title = getString(R.string.special_title)
        scan()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.docsAppbar, NavigationIcon.Arrow)
        binding.docsToolbar.menu.clear()
        binding.docsToolbar.inflateMenu(R.menu.menu_docs)
        binding.docsToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.docs_filter) {
                showFilterDialog()
                true
            } else {
                false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanning = false
    }

    private fun scan() {
        binding.docsPlaceholder.text = getString(R.string.special_scanning)
        binding.docsPlaceholder.visibility = android.view.View.VISIBLE
        ensureBackgroundThread {
            val paths = queryImages()
            var checked = 0
            for (p in paths) {
                if (!scanning || isDestroyed) return@ensureBackgroundThread
                val kinds = try {
                    SpecialPhoto.detect(p)
                } catch (e: Throwable) {
                    emptySet()
                }
                if (kinds.isNotEmpty()) {
                    synchronized(found) {
                        kinds.forEach { k -> found.getOrPut(k) { ArrayList() }.add(p) }
                    }
                }
                checked++
                if (checked % 60 == 0 || checked == paths.size) {
                    val c = checked
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        binding.docsToolbar.title = getString(R.string.special_progress, c, paths.size)
                        applyFilter()
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.docsToolbar.title = getString(R.string.special_title)
                applyFilter()
            }
        }
    }

    private fun queryImages(): List<String> {
        val list = ArrayList<String>()
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
            )?.use { c ->
                val d = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (c.moveToNext()) c.getString(d)?.let { list.add(it) }
            }
        } catch (ignored: Throwable) {
        }
        return list
    }

    private fun applyFilter() {
        val f = filter
        val paths = synchronized(found) {
            if (f == null) found.values.flatten().distinct() else (found[f]?.toList() ?: emptyList())
        }
        shown = ArrayList(paths)
        binding.docsGrid.adapter = PersonPhotosAdapter(this, shown, onClick = { p -> openPhoto(p) })
        binding.docsPlaceholder.visibility = if (shown.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        if (shown.isEmpty()) {
            binding.docsPlaceholder.text = getString(R.string.special_none)
        }
    }

    private fun showFilterDialog() {
        val kinds = listOf<SpecialPhoto.Kind?>(null) + SpecialPhoto.Kind.entries
        val labels = kinds.map { k ->
            val name = if (k == null) getString(R.string.docs_filter_all) else getString(SpecialPhoto.labelRes(k))
            val cnt = synchronized(found) {
                if (k == null) found.values.flatten().distinct().size else (found[k]?.size ?: 0)
            }
            "$name ($cnt)"
        }.toTypedArray()
        val current = kinds.indexOfFirst { it == filter }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.filter_media)
            .setSingleChoiceItems(labels, current) { dlg, which ->
                filter = kinds[which]
                applyFilter()
                dlg.dismiss()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun openPhoto(path: String) {
        PathTransfer.forViewer = shown
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, path)
            putExtra(SKIP_AUTHENTICATION, true)
            putExtra(SHOW_ALL, false)
            startActivity(this)
        }
    }
}
