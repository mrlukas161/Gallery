package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PersonFacesAdapter
import org.fossify.gallery.databinding.ActivityPersonBinding
import org.fossify.gallery.faces.CannotLinkEntity
import org.fossify.gallery.faces.FaceAssignmentEntity
import org.fossify.gallery.faces.FaceEntity
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PersonEntity
import java.io.File

class PersonActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPersonBinding::inflate)
    private var personId: Long = -1L
    private var personName: String? = null
    private var manualIds: Set<Long> = emptySet()
    private var adapter: PersonFacesAdapter? = null
    private var photoPaths: ArrayList<String> = arrayListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        personId = intent.getLongExtra(PERSON_ID, -1L)
        personName = intent.getStringExtra(PERSON_NAME)
        manualIds = intent.getLongArrayExtra(MANUAL_IDS)?.toSet() ?: emptySet()
        val faceIds = intent.getLongArrayExtra(FACE_IDS)?.toList() ?: emptyList()
        binding.personGrid.layoutManager = GridLayoutManager(this, COLUMNS)
        loadFaces(faceIds)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.personAppbar, NavigationIcon.Arrow)
        binding.personToolbar.title = personName ?: getString(R.string.person_suggested)
        binding.personToolbar.menu.clear()
        if (personId >= 0) {
            binding.personToolbar.inflateMenu(R.menu.menu_person)
            binding.personToolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.suggestions) {
                    openSuggestions()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun openSuggestions() {
        val intent = Intent(this, FaceTaggingActivity::class.java)
        intent.putExtra(FaceTaggingActivity.MODE, FaceTaggingActivity.MODE_SUGGESTIONS)
        intent.putExtra(FaceTaggingActivity.PERSON_ID, personId)
        intent.putExtra(FaceTaggingActivity.PERSON_NAME, personName)
        startActivity(intent)
    }

    private fun loadFaces(faceIds: List<Long>) {
        ensureBackgroundThread {
            val faces = try {
                if (faceIds.isEmpty()) emptyList()
                else FacesDatabase.getInstance(this).FaceDao().getFacesByIds(faceIds)
            } catch (e: Throwable) {
                emptyList()
            }
            val sorted = faces.sortedByDescending { it.score }.toMutableList()
            val paths = ArrayList(sorted.map { it.mediaFullPath }.distinct().take(2000))
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                photoPaths = paths
                adapter = PersonFacesAdapter(
                    this, sorted,
                    onClick = { face -> openPhoto(face.mediaFullPath) },
                    onLongClick = { face -> showFaceMenu(face) },
                )
                binding.personGrid.adapter = adapter
            }
        }
    }

    private fun showFaceMenu(face: FaceEntity) {
        val options = arrayListOf(getString(R.string.action_move_to_person))
        // "toto nie je X" má zmysel len v potvrdenej osobe
        val canReject = personId >= 0
        if (canReject) options.add(getString(R.string.action_not_this_person))
        AlertDialog.Builder(this)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> moveFace(face)
                    1 -> if (canReject) notThisPerson(face)
                }
            }
            .show()
    }

    private fun moveFace(face: FaceEntity) {
        val fid = face.id ?: return
        ensureBackgroundThread {
            val persons = PeopleDatabase.getInstance(this).PeopleDao().getPersons().filter { it.id != personId }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val labels = ArrayList<String>()
                persons.forEach { labels.add(it.name ?: "#${it.id}") }
                labels.add(getString(R.string.new_person))
                AlertDialog.Builder(this)
                    .setTitle(R.string.action_move_to_person)
                    .setItems(labels.toTypedArray()) { _, which ->
                        if (which == persons.size) {
                            promptName(null) { name -> assignToNewPerson(fid, name, face) }
                        } else {
                            assignToPerson(fid, persons[which].id, face)
                        }
                    }
                    .show()
            }
        }
    }

    private fun assignToPerson(faceId: Long, targetId: Long, face: FaceEntity) {
        ensureBackgroundThread {
            PeopleDatabase.getInstance(this).PeopleDao()
                .upsertAssignment(FaceAssignmentEntity(faceId, targetId, true, System.currentTimeMillis()))
            runOnUiThread {
                toast(R.string.person_saved)
                adapter?.removeFace(face)
            }
        }
    }

    private fun assignToNewPerson(faceId: Long, name: String, face: FaceEntity) {
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            val newId = dao.insertPerson(PersonEntity(name = name, createdAt = System.currentTimeMillis()))
            dao.upsertAssignment(FaceAssignmentEntity(faceId, newId, true, System.currentTimeMillis()))
            runOnUiThread {
                toast(R.string.person_saved)
                adapter?.removeFace(face)
            }
        }
    }

    private fun notThisPerson(face: FaceEntity) {
        val fid = face.id ?: return
        if (personId < 0) return
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            if (manualIds.contains(fid)) {
                // bola ručne priradená → len zruš priradenie
                dao.deleteAssignment(fid)
            } else {
                // bola len navrhnutá → zapamätaj, že k tejto osobe NEpatrí (učenie)
                dao.insertCannotLink(CannotLinkEntity(fid, personId))
            }
            runOnUiThread { adapter?.removeFace(face) }
        }
    }

    private fun openPhoto(path: String) {
        val idx = photoPaths.indexOf(path).coerceAtLeast(0)
        val intent = Intent(this, PersonPhotoPagerActivity::class.java)
        intent.putStringArrayListExtra(PersonPhotoPagerActivity.PATHS, photoPaths)
        intent.putExtra(PersonPhotoPagerActivity.START_INDEX, idx)
        startActivity(intent)
    }

    private fun promptName(initial: String?, onName: (String) -> Unit) {
        val input = EditText(this)
        input.setText(initial ?: "")
        AlertDialog.Builder(this)
            .setTitle(R.string.enter_name)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val PERSON_ID = "person_id"
        const val PERSON_NAME = "person_name"
        const val FACE_IDS = "face_ids"
        const val MANUAL_IDS = "manual_ids"
        private const val COLUMNS = 3
    }
}
