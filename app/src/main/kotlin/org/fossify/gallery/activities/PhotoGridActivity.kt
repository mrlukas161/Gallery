package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PhotoPathsAdapter
import org.fossify.gallery.databinding.ActivityPhotoGridBinding
import org.fossify.gallery.helpers.GridZoom
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION

// Jednoduchá mriežka fotiek (napr. z jedného zhluku na mape). Ťuk → štandardný prehliadač.
class PhotoGridActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPhotoGridBinding::inflate)
    private var paths = ArrayList<String>()

    // dočasný súbor so zoznamom pre obnovu (onSaveInstanceState)
    private var stateName = "photo_grid_${System.nanoTime()}.txt"
    private val prefs by lazy { getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.photoGridAppbar),
            padBottomImeAndSystem = listOf(binding.photoGrid)
        )
        setupMaterialScrollListener(binding.photoGrid, binding.photoGridAppbar)
        paths = restorePaths(savedInstanceState)
        // pinch-zoom mení počet stĺpcov a pamätá si ho — rovnaké ovládanie ako v ostatných mriežkach
        val lm = GridLayoutManager(this, prefs.getInt("grid_columns", 3))
        binding.photoGrid.layoutManager = lm
        GridZoom.setup(binding.photoGrid, lm, prefs, "grid_columns")
        binding.photoGridFastscroller.updateColors(getProperPrimaryColor())
        binding.photoGrid.adapter = PhotoPathsAdapter(
            this, paths, binding.photoGrid,
            onClick = { path -> openPhoto(path) },
            // adaptér odobral zmazané (alebo presunuté) fotky zo `paths` — premietni to do titulku aj prázdneho stavu
            onDeleted = { updateTitleAndEmptyState() },
        )
        updateTitleAndEmptyState()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.photoGridAppbar, NavigationIcon.Arrow)
        // prefarbenie podľa témy Fossify (prázdny stav by vo svetlej téme ostal svetlosivý)
        updateTextColors(binding.photoGridCoordinator)
        binding.photoGridFastscroller.updateColors(getProperPrimaryColor())
        updateTitleAndEmptyState()
    }

    // Zoznam prežije otočenie, zmenu témy aj zabitie procesu (revízia 11/2026): pri prvom otvorení prišiel
    // zo statického PathTransfer.forGrid, po obnove z dočasného súboru v cache (do 2000 ciest by Bundle
    // mohol prekročiť limit transakcie). V Bundle je len názov súboru.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val file = stateFile()
        try {
            file.writeText(paths.joinToString("\n"))
            outState.putString(STATE_FILE, file.name)
        } catch (ignored: java.io.IOException) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) stateFile().delete()
    }

    private fun restorePaths(state: Bundle?): ArrayList<String> {
        val fromTransfer = org.fossify.gallery.helpers.PathTransfer.forGrid
        org.fossify.gallery.helpers.PathTransfer.forGrid = null
        if (state == null) return ArrayList(fromTransfer ?: emptyList())
        stateName = state.getString(STATE_FILE) ?: stateName
        val saved = try {
            stateFile().takeIf { it.exists() }?.readText()
        } catch (ignored: java.io.IOException) {
            null
        }
        return ArrayList(saved?.split('\n')?.filter { it.isNotEmpty() } ?: fromTransfer ?: emptyList())
    }

    private fun stateFile() = java.io.File(cacheDir, stateName)

    private fun updateTitleAndEmptyState() {
        binding.photoGridToolbar.title = getString(R.string.search_count, paths.size)
        binding.photoGridPlaceholder.visibility = if (paths.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openPhoto(path: String) {
        org.fossify.gallery.helpers.PathTransfer.forViewer = paths // uzavretý set = tento zhluk
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, path)
            putExtra(SKIP_AUTHENTICATION, true)
            putExtra(SHOW_ALL, false)
            startActivity(this)
        }
    }

    companion object {
        const val PATHS = "paths"
        private const val STATE_FILE = "photo_grid_state_file"
    }
}
