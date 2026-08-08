package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
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
import org.fossify.gallery.helpers.DocClassifier
import org.fossify.gallery.helpers.GridZoom
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
    private var query = ""
    private val prefs by lazy { getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        // pinch-zoom mení počet stĺpcov a pamätá si ho — rovnaké ovládanie ako v ostatných mriežkach
        val lm = GridLayoutManager(this, prefs.getInt("docs_columns", 3))
        binding.docsGrid.layoutManager = lm
        GridZoom.setup(binding.docsGrid, lm, prefs, "docs_columns")
        binding.docsFastscroller.updateColors(getProperPrimaryColor())
        // vyhľadávanie v texte dokumentov (OCR) priamo v albume
        binding.docsSearch.visibility = android.view.View.VISIBLE
        binding.docsSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString()?.trim().orEmpty()
                org.fossify.gallery.helpers.SmartSearch.lastQuery = query
                applyFilter()
            }
        })
        load()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.docsAppbar, NavigationIcon.Arrow)
        // prefarbenie podľa témy Fossify — inak by text/hint hľadania aj placeholder boli
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

    private fun load() {
        binding.docsPlaceholder.text = getString(R.string.docs_loading)
        ensureBackgroundThread {
            // lastModified čítať z disku IBA RAZ na súbor (0 = súbor neexistuje, nahrádza exists()),
            // nie v komparátore — ten selektor volá pri každom porovnaní (~2·n·log n syscallov)
            val docs = DocClassifier.loadAll(this)
                .map { it to java.io.File(it.path).lastModified() }
                .filter { it.second > 0L }
                .sortedByDescending { it.second }
                .map { it.first }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                all = docs
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val f = filter
        val qTokens = query.split(Regex("""\s+"""))
            .map { org.fossify.gallery.helpers.TextNormalizer.normalize(it, true) }
            .filter { it.length >= 2 }
        shown = ArrayList(
            all.filter { d ->
                (f == null || d.kind == f) && (qTokens.isEmpty() || qTokens.all { d.norm.contains(it) })
            }.map { it.path }
        )
        // adaptér vzniká len RAZ — ďalšie zmeny (písanie do hľadania, filter, mazanie) idú
        // cez updateItems, nech mriežka nestráca pozíciu scrollu výmenou adaptéra
        val existing = binding.docsGrid.adapter as? PhotoPathsAdapter
        if (existing == null) {
            binding.docsGrid.adapter = PhotoPathsAdapter(
                this, shown, binding.docsGrid,
                onClick = { path -> openPhoto(path) },
                onDeleted = { deleted -> all = all.filter { it.path !in deleted }; applyFilter() },
            )
        } else {
            existing.updateItems(shown)
        }
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
        // Fossify dialóg (RadioGroupDialog) namiesto surového AlertDialog.Builder —
        // správne témovanie (pozadie, texty aj tlačidlá podľa zvolenej témy)
        val items = ArrayList<RadioItem>()
        kinds.forEachIndexed { i, (k, name) ->
            val cnt = if (k == null) all.size else all.count { it.kind == k }
            items.add(RadioItem(i, "$name ($cnt)"))
        }
        val current = kinds.indexOfFirst { it.first == filter }.coerceAtLeast(0)
        RadioGroupDialog(this, items, current, R.string.filter_media) {
            filter = kinds[it as Int].first
            applyFilter()
        }
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
