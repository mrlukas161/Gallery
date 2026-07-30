package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PersonPhotosAdapter
import org.fossify.gallery.databinding.ActivityDocsBinding
import org.fossify.gallery.helpers.DocClassifier
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.PathTransfer
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION

// Album Dokumenty — fotky vyhodnotené ako doklad/dokument podľa rozpoznaného textu (OCR).
// Filter podľa druhu: všetko / doklady a faktúry / dokumenty / screenshoty / preukazy.
class DocsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDocsBinding::inflate)
    private var all: List<DocClassifier.Doc> = emptyList()
    private var shown = ArrayList<String>()
    private var filter: DocClassifier.Kind? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.docsGrid.layoutManager = GridLayoutManager(this, 3)
        binding.docsFastscroller.updateColors(getProperPrimaryColor())
        load()
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

    private fun load() {
        binding.docsPlaceholder.text = getString(R.string.docs_loading)
        ensureBackgroundThread {
            val docs = DocClassifier.loadAll(this)
                .filter { java.io.File(it.path).exists() }
                .sortedByDescending { java.io.File(it.path).lastModified() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                all = docs
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val f = filter
        shown = ArrayList(all.filter { f == null || it.kind == f }.map { it.path })
        binding.docsGrid.adapter = PersonPhotosAdapter(this, shown, onClick = { path -> openPhoto(path) })
        val title = if (f == null) getString(R.string.docs_title) else DocClassifier.label(this, f)
        binding.docsToolbar.title = "$title (${shown.size})"
        if (shown.isEmpty()) {
            binding.docsPlaceholder.text = getString(
                if (all.isEmpty()) R.string.docs_empty_run_ocr else R.string.docs_empty_filter
            )
            binding.docsPlaceholder.visibility = android.view.View.VISIBLE
        } else {
            binding.docsPlaceholder.visibility = android.view.View.GONE
        }
    }

    private fun showFilterDialog() {
        val kinds = listOf(
            null to getString(R.string.docs_filter_all),
            DocClassifier.Kind.RECEIPT to getString(R.string.doc_kind_receipt),
            DocClassifier.Kind.DOCUMENT to getString(R.string.doc_kind_document),
            DocClassifier.Kind.ID_CARD to getString(R.string.doc_kind_id),
            DocClassifier.Kind.SCREENSHOT to getString(R.string.doc_kind_screenshot),
        )
        val labels = kinds.map { (k, name) ->
            val cnt = if (k == null) all.size else all.count { it.kind == k }
            "$name ($cnt)"
        }.toTypedArray()
        val current = kinds.indexOfFirst { it.first == filter }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.filter_media)
            .setSingleChoiceItems(labels, current) { dlg, which ->
                filter = kinds[which].first
                applyFilter()
                dlg.dismiss()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun openPhoto(path: String) {
        PathTransfer.forViewer = shown // uzavretý set = len dokumenty
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, path)
            putExtra(SKIP_AUTHENTICATION, true)
            putExtra(SHOW_ALL, false)
            startActivity(this)
        }
    }
}
