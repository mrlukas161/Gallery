package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PersonPhotosAdapter
import org.fossify.gallery.databinding.ActivityPhotoGridBinding
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION

// Jednoduchá mriežka fotiek (napr. z jedného zhluku na mape). Ťuk → štandardný prehliadač.
class PhotoGridActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPhotoGridBinding::inflate)
    private var paths = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        paths = intent.getStringArrayListExtra(PATHS) ?: arrayListOf()
        binding.photoGrid.layoutManager = GridLayoutManager(this, 3)
        binding.photoGrid.adapter = PersonPhotosAdapter(this, paths) { path -> openPhoto(path) }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.photoGridAppbar, NavigationIcon.Arrow)
        binding.photoGridToolbar.title = getString(R.string.search_count, paths.size)
    }

    private fun openPhoto(path: String) {
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
