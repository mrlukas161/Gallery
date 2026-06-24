package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PersonFacesAdapter
import org.fossify.gallery.adapters.PersonPhotosAdapter
import org.fossify.gallery.databinding.ActivityPersonBinding
import org.fossify.gallery.dialogs.ChangeSortingDialog
import org.fossify.gallery.extensions.config
import org.fossify.gallery.faces.CannotLinkEntity
import org.fossify.gallery.faces.FaceAssignmentEntity
import org.fossify.gallery.faces.FaceEntity
import org.fossify.gallery.faces.FaceMediaMeta
import org.fossify.gallery.faces.FaceSorter
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PersonEntity
import org.fossify.gallery.helpers.GridZoom
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION

class PersonActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPersonBinding::inflate)
    private var personId: Long = -1L
    private var personName: String? = null
    private var manualIds: Set<Long> = emptySet()
    private var facesAdapter: PersonFacesAdapter? = null
    private var loadedFaces: List<FaceEntity> = emptyList()
    private var photoPaths: ArrayList<String> = arrayListOf()
    private var meta: Map<String, FaceMediaMeta.Meta> = emptyMap()
    private var showFullPhotos = false
    private val prefs by lazy { getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE) }

    private fun sortPath() = "person_$personId"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        personId = intent.getLongExtra(PERSON_ID, -1L)
        personName = intent.getStringExtra(PERSON_NAME)
        manualIds = intent.getLongArrayExtra(MANUAL_IDS)?.toSet() ?: emptySet()
        val faceIds = intent.getLongArrayExtra(FACE_IDS)?.toList() ?: emptyList()
        val lm = GridLayoutManager(this, prefs.getInt("person_columns", COLUMNS))
        binding.personGrid.layoutManager = lm
        GridZoom.setup(binding.personGrid, lm, prefs, "person_columns")
        loadFaces(faceIds)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.personAppbar, NavigationIcon.Arrow)
        binding.personToolbar.title = personName ?: getString(R.string.person_suggested)
        binding.personToolbar.menu.clear()
        binding.personToolbar.inflateMenu(R.menu.menu_person)
        updateMenuTitles()
        binding.personToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.suggestions -> {
                    if (personId >= 0) openSuggestions()
                    true
                }

                R.id.toggle_view -> {
                    showFullPhotos = !showFullPhotos
                    updateMenuTitles()
                    render()
                    true
                }

                R.id.sort_order -> {
                    ChangeSortingDialog(this, false, true, sortPath()) { render() }
                    true
                }

                else -> false
            }
        }
    }

    private fun updateMenuTitles() {
        binding.personToolbar.menu.findItem(R.id.toggle_view)?.title =
            getString(if (showFullPhotos) R.string.show_faces else R.string.show_photos)
    }

    private fun loadFaces(faceIds: List<Long>) {
        ensureBackgroundThread {
            val faces = try {
                if (faceIds.isEmpty()) emptyList()
                else FacesDatabase.getInstance(this).FaceDao().getFacesByIds(faceIds)
            } catch (e: Throwable) {
                emptyList()
            }
            val loadedMeta = FaceMediaMeta.load(this, faces.map { it.mediaFullPath }.distinct())
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loadedFaces = faces
                meta = loadedMeta
                render()
            }
        }
    }

    private fun render() {
        val sorting = config.getFolderSorting(sortPath())
        val facesSorted = FaceSorter.sortFaces(loadedFaces, meta, sorting)
        photoPaths = ArrayList(
            FaceSorter.sortPaths(loadedFaces.map { it.mediaFullPath }.distinct(), meta, sorting).take(2000)
        )
        if (showFullPhotos) {
            binding.personGrid.adapter = PersonPhotosAdapter(this, photoPaths) { path -> openPhoto(path) }
        } else {
            facesAdapter = PersonFacesAdapter(
                this, facesSorted.toMutableList(),
                onClick = { face -> openPhoto(face.mediaFullPath) },
                onLongClick = { face -> showFaceMenu(face) },
            )
            binding.personGrid.adapter = facesAdapter
        }
    }

    private fun showFaceMenu(face: FaceEntity) {
        val options = arrayListOf(getString(R.string.action_move_to_person))
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
                facesAdapter?.removeFace(face)
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
                facesAdapter?.removeFace(face)
            }
        }
    }

    private fun notThisPerson(face: FaceEntity) {
        val fid = face.id ?: return
        if (personId < 0) return
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            if (manualIds.contains(fid)) {
                dao.deleteAssignment(fid)
            } else {
                dao.insertCannotLink(CannotLinkEntity(fid, personId))
            }
            runOnUiThread { facesAdapter?.removeFace(face) }
        }
    }

    private fun openSuggestions() {
        val intent = Intent(this, FaceTaggingActivity::class.java)
        intent.putExtra(FaceTaggingActivity.MODE, FaceTaggingActivity.MODE_SUGGESTIONS)
        intent.putExtra(FaceTaggingActivity.PERSON_ID, personId)
        intent.putExtra(FaceTaggingActivity.PERSON_NAME, personName)
        startActivity(intent)
    }

    private fun openPhoto(path: String) {
        // otvor v ŠTANDARDNOM prehliadači Fossify (názov, vlastnosti, kopírovať/presunúť, mapa, tlač…)
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, path)
            putExtra(SKIP_AUTHENTICATION, true)
            putExtra(SHOW_ALL, false)
            startActivity(this)
        }
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
