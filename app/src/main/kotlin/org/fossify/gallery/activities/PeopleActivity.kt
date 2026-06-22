package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.setupToolbar
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PeopleAdapter
import org.fossify.gallery.databinding.ActivityPeopleBinding
import org.fossify.gallery.faces.FaceClusterer
import org.fossify.gallery.faces.FacesDatabase

class PeopleActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPeopleBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        loadPeople()
    }

    override fun onResume() {
        super.onResume()
        setupToolbar(binding.peopleToolbar, NavigationIcon.Arrow)
    }

    private fun loadPeople() {
        ensureBackgroundThread {
            val faces = try {
                FacesDatabase.getInstance(this).FaceDao().getAllFaces()
            } catch (e: Throwable) {
                emptyList()
            }
            val people = FaceClusterer.cluster(faces)
                .filter { it.faceCount >= 2 }
                .sortedByDescending { it.faceCount }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (people.isEmpty()) {
                    binding.peoplePlaceholder.text = getString(R.string.people_empty)
                    binding.peoplePlaceholder.beVisible()
                } else {
                    binding.peoplePlaceholder.beGone()
                }
                binding.peopleGrid.layoutManager = GridLayoutManager(this, COLUMNS)
                binding.peopleGrid.adapter = PeopleAdapter(this, people) { person ->
                    val intent = Intent(this, PersonActivity::class.java)
                    intent.putStringArrayListExtra(PERSON_PHOTO_PATHS, ArrayList(person.photoPaths.take(MAX_PHOTOS)))
                    startActivity(intent)
                }
            }
        }
    }

    companion object {
        const val PERSON_PHOTO_PATHS = "person_photo_paths"
        private const val COLUMNS = 3
        private const val MAX_PHOTOS = 1000
    }
}
