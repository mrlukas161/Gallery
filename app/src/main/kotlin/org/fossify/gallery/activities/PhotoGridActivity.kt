package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
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
    private val prefs by lazy { getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        paths = ArrayList(org.fossify.gallery.helpers.PathTransfer.forGrid ?: emptyList())
        org.fossify.gallery.helpers.PathTransfer.forGrid = null
        // pinch-zoom mení počet stĺpcov a pamätá si ho — rovnaké ovládanie ako v ostatných mriežkach
        val lm = GridLayoutManager(this, prefs.getInt("grid_columns", 3))
        binding.photoGrid.layoutManager = lm
        GridZoom.setup(binding.photoGrid, lm, prefs, "grid_columns")
        binding.photoGrid.adapter = PhotoPathsAdapter(
            this, paths, binding.photoGrid,
            onClick = { path -> openPhoto(path) },
            onDeleted = {
                binding.photoGridToolbar.title = getString(R.string.search_count, paths.size)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.photoGridAppbar, NavigationIcon.Arrow)
        // prefarbenie podľa témy Fossify (dnes tu nie sú texty, ale drží obrazovku konzistentnú)
        updateTextColors(binding.photoGridCoordinator)
        binding.photoGridToolbar.title = getString(R.string.search_count, paths.size)
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
    }
}
