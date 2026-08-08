package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PhotoPathsAdapter
import org.fossify.gallery.databinding.ActivityDocsBinding
import org.fossify.gallery.helpers.GridZoom
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
    private val prefs by lazy { getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE) }
    @Volatile
    private var scanning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        // pinch-zoom mení počet stĺpcov a pamätá si ho — rovnaké ovládanie ako v ostatných mriežkach
        val lm = GridLayoutManager(this, prefs.getInt("special_columns", 3))
        binding.docsGrid.layoutManager = lm
        GridZoom.setup(binding.docsGrid, lm, prefs, "special_columns")
        binding.docsFastscroller.updateColors(getProperPrimaryColor())
        binding.docsToolbar.title = getString(R.string.special_title)
        scan()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.docsAppbar, NavigationIcon.Arrow)
        // prefarbenie podľa témy Fossify — inak by boli texty „skenujem/nič sa nenašlo"
        // vo svetlej téme svetlosivé na bielom (téma z XML je vždy tmavá M3)
        updateTextColors(binding.docsCoordinator)
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
        // adaptér vzniká len RAZ — počas skenu sa applyFilter volá každých 60 fotiek a výmena
        // adaptéra by zakaždým zhodila scroll na začiatok; updateItems pozíciu drží
        val existing = binding.docsGrid.adapter as? PhotoPathsAdapter
        if (existing == null) {
            binding.docsGrid.adapter = PhotoPathsAdapter(
                this, shown, binding.docsGrid,
                onClick = { p -> openPhoto(p) },
                onDeleted = { deleted ->
                    synchronized(found) { found.values.forEach { it.removeAll(deleted.toSet()) } }
                    applyFilter()
                },
            )
        } else {
            existing.updateItems(shown)
        }
        binding.docsPlaceholder.visibility = if (shown.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        if (shown.isEmpty()) {
            binding.docsPlaceholder.text = getString(R.string.special_none)
        }
    }

    private fun showFilterDialog() {
        val kinds = listOf<SpecialPhoto.Kind?>(null) + SpecialPhoto.Kind.entries
        // Fossify dialóg (RadioGroupDialog) namiesto surového AlertDialog.Builder —
        // správne témovanie (pozadie, texty aj tlačidlá podľa zvolenej témy)
        val items = ArrayList<RadioItem>()
        kinds.forEachIndexed { i, k ->
            val name = if (k == null) getString(R.string.docs_filter_all) else getString(SpecialPhoto.labelRes(k))
            val cnt = synchronized(found) {
                if (k == null) found.values.flatten().distinct().size else (found[k]?.size ?: 0)
            }
            items.add(RadioItem(i, "$name ($cnt)"))
        }
        val current = kinds.indexOfFirst { it == filter }.coerceAtLeast(0)
        RadioGroupDialog(this, items, current, R.string.filter_media) {
            filter = kinds[it as Int]
            applyFilter()
        }
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
