package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.setupToolbar
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.adapters.PersonPhotosAdapter
import org.fossify.gallery.databinding.ActivityPersonBinding
import java.io.File

class PersonActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPersonBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val paths = intent.getStringArrayListExtra(PeopleActivity.PERSON_PHOTO_PATHS) ?: arrayListOf()
        binding.personGrid.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.personGrid.adapter = PersonPhotosAdapter(this, paths) { path -> openPhoto(path) }
    }

    override fun onResume() {
        super.onResume()
        setupToolbar(binding.personToolbar, NavigationIcon.Arrow)
    }

    private fun openPhoto(path: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", File(path))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        private const val COLUMNS = 3
    }
}
