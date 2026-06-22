package org.fossify.gallery.activities

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.FaceTagAdapter
import org.fossify.gallery.databinding.ActivityFaceTaggingBinding
import org.fossify.gallery.faces.CannotLinkEntity
import org.fossify.gallery.faces.FaceAssignmentEntity
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FaceEntity
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PersonEntity
import org.fossify.gallery.faces.PersonGrouper

// Dva režimy:
//  UNLABELED   = mriežka všetkých NEoznačených tvárí, multi-výber -> priradiť k osobe
//  SUGGESTIONS = kandidáti blízki centroidu osoby (z potvrdených + Picasa anchorov),
//                multi-výber -> "Toto je X" (potvrď) / "Toto nie je" (cannot-link = učenie)
class FaceTaggingActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityFaceTaggingBinding::inflate)
    private var mode = MODE_UNLABELED
    private var personId = -1L
    private var personName: String? = null
    private var adapter: FaceTagAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        mode = intent.getIntExtra(MODE, MODE_UNLABELED)
        personId = intent.getLongExtra(PERSON_ID, -1L)
        personName = intent.getStringExtra(PERSON_NAME)
        binding.taggingGrid.layoutManager = GridLayoutManager(this, COLUMNS)
        setupBottomBar()
        load()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.taggingAppbar, NavigationIcon.Arrow)
        binding.taggingToolbar.title = if (mode == MODE_SUGGESTIONS) {
            getString(R.string.suggestions_for, personName ?: "")
        } else {
            getString(R.string.tag_faces_title)
        }
    }

    private fun setupBottomBar() {
        if (mode == MODE_SUGGESTIONS) {
            binding.taggingBtnPrimary.text = getString(R.string.this_is_person, personName ?: "")
            binding.taggingBtnSecondary.text = getString(R.string.this_is_not)
            binding.taggingBtnSecondary.visibility = View.VISIBLE
            binding.taggingBtnPrimary.setOnClickListener { confirmSelected() }
            binding.taggingBtnSecondary.setOnClickListener { rejectSelected() }
        } else {
            binding.taggingBtnPrimary.text = getString(R.string.assign_to_person)
            binding.taggingBtnSecondary.visibility = View.GONE
            binding.taggingBtnPrimary.setOnClickListener { assignSelectedToPicked() }
        }
        updateCount(0)
    }

    private fun updateCount(n: Int) {
        binding.taggingCount.text = getString(R.string.selected_count, n)
        binding.taggingBtnPrimary.isEnabled = n > 0
        binding.taggingBtnSecondary.isEnabled = n > 0
    }

    private fun load() {
        ensureBackgroundThread {
            val faces = try {
                if (mode == MODE_SUGGESTIONS) loadSuggestions() else loadUnlabeled()
            } catch (e: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (faces.isEmpty()) {
                    binding.taggingPlaceholder.visibility = View.VISIBLE
                    binding.taggingPlaceholder.text = getString(
                        if (mode == MODE_SUGGESTIONS) R.string.no_suggestions else R.string.no_unlabeled_faces
                    )
                } else {
                    binding.taggingPlaceholder.visibility = View.GONE
                }
                adapter = FaceTagAdapter(this, faces.toMutableList()) { n -> updateCount(n) }
                binding.taggingGrid.adapter = adapter
                updateCount(0)
            }
        }
    }

    private fun loadUnlabeled(): List<FaceEntity> {
        val facesDao = FacesDatabase.getInstance(this).FaceDao()
        val peopleDao = PeopleDatabase.getInstance(this).PeopleDao()
        val assigned = peopleDao.getAssignments().map { it.faceId }.toHashSet()
        return facesDao.getAllFaces()
            .filter { it.score >= MIN_FACE_SCORE && (it.bboxRight - it.bboxLeft) >= MIN_FACE_SIZE }
            .filter { val id = it.id; id != null && !assigned.contains(id) }
            .sortedByDescending { it.score }
            .take(UNLABELED_CAP)
    }

    private fun loadSuggestions(): List<FaceEntity> {
        val facesDao = FacesDatabase.getInstance(this).FaceDao()
        val peopleDao = PeopleDatabase.getInstance(this).PeopleDao()
        val all = facesDao.getAllFaces()
            .filter { it.score >= MIN_FACE_SCORE && (it.bboxRight - it.bboxLeft) >= MIN_FACE_SIZE }
        val assignments = peopleDao.getAssignments()
        val assigned = assignments.map { it.faceId }.toHashSet()
        val mineIds = assignments.filter { it.personId == personId }.map { it.faceId }.toHashSet()
        val confirmedEmb = all.filter { it.id != null && mineIds.contains(it.id) }
            .mapNotNull { it.embedding?.let { b -> FaceEmbedder.toFloats(b) } }
        val anchorEmb = peopleDao.getAnchorEmbeddings(personId).map { FaceEmbedder.toFloats(it) }
        val centroid = PersonGrouper.centroidOf(confirmedEmb + anchorEmb) ?: return emptyList()
        val cannot = peopleDao.getCannotLinks().filter { it.personId == personId }.map { it.faceId }.toHashSet()
        return all
            .filter { val id = it.id; id != null && !assigned.contains(id) && !cannot.contains(id) }
            .mapNotNull { f -> f.embedding?.let { b -> f to PersonGrouper.cosine(FaceEmbedder.toFloats(b), centroid) } }
            .filter { it.second >= SUGGEST_FLOOR }
            .sortedByDescending { it.second }
            .take(SUGGEST_K)
            .map { it.first }
    }

    private fun confirmSelected() {
        val ids = adapter?.selectedIds().orEmpty()
        if (ids.isEmpty() || personId < 0) return
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            val now = System.currentTimeMillis()
            ids.forEach { dao.upsertAssignment(FaceAssignmentEntity(it, personId, true, now)) }
            runOnUiThread {
                toast(R.string.person_saved)
                adapter?.removeSelected()
            }
        }
    }

    private fun rejectSelected() {
        val ids = adapter?.selectedIds().orEmpty()
        if (ids.isEmpty() || personId < 0) return
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            ids.forEach { dao.insertCannotLink(CannotLinkEntity(it, personId)) }
            runOnUiThread { adapter?.removeSelected() }
        }
    }

    private fun assignSelectedToPicked() {
        val ids = adapter?.selectedIds().orEmpty()
        if (ids.isEmpty()) return
        ensureBackgroundThread {
            val persons = PeopleDatabase.getInstance(this).PeopleDao().getPersons()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val labels = ArrayList<String>()
                persons.forEach { labels.add(it.name ?: "#${it.id}") }
                labels.add(getString(R.string.new_person))
                AlertDialog.Builder(this)
                    .setTitle(R.string.assign_to_person)
                    .setItems(labels.toTypedArray()) { _, which ->
                        if (which == persons.size) {
                            promptName { name -> doAssign(ids, null, name) }
                        } else {
                            doAssign(ids, persons[which].id, null)
                        }
                    }
                    .show()
            }
        }
    }

    private fun doAssign(ids: List<Long>, existingId: Long?, newName: String?) {
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            val now = System.currentTimeMillis()
            val targetId = existingId ?: dao.insertPerson(PersonEntity(name = newName, createdAt = now))
            ids.forEach { dao.upsertAssignment(FaceAssignmentEntity(it, targetId, true, now)) }
            runOnUiThread {
                toast(R.string.person_saved)
                adapter?.removeSelected()
            }
        }
    }

    private fun promptName(onName: (String) -> Unit) {
        val input = EditText(this)
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
        const val MODE = "mode"
        const val PERSON_ID = "person_id"
        const val PERSON_NAME = "person_name"
        const val MODE_UNLABELED = 0
        const val MODE_SUGGESTIONS = 1
        private const val COLUMNS = 4
        private const val UNLABELED_CAP = 1500
        private const val SUGGEST_K = 80
        private const val SUGGEST_FLOOR = 0.30f
        private const val MIN_FACE_SCORE = 0.8f
        private const val MIN_FACE_SIZE = 40
    }
}
