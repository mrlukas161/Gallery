package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.commons.views.MyEditText
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PersonFacesAdapter
import org.fossify.gallery.adapters.PhotoPathsAdapter
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
        binding.personFastscroller.updateColors(getProperPrimaryColor())
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
        val faces = loadedFaces
        // triedenie tisícok tvárí (lowercase, lookup do meta) patrí na pozadie — UI len nastaví adaptér
        ensureBackgroundThread {
            val facesSorted = FaceSorter.sortFaces(faces, meta, sorting)
            val sortedPaths = ArrayList(
                FaceSorter.sortPaths(faces.map { it.mediaFullPath }.distinct(), meta, sorting).take(2000)
            )
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                photoPaths = sortedPaths
                if (showFullPhotos) {
                    // jednotná mriežka s výberovým režimom; „toto nie je osoba" je kontextová akcia
                    // vo výbere (predtým deštruktívny long-press v rozpore so zvyškom appky)
                    binding.personGrid.adapter = PhotoPathsAdapter(
                        this, photoPaths, binding.personGrid,
                        onClick = { path -> openPhoto(path) },
                        onDeleted = { deleted -> onPhotosDeleted(deleted) },
                        extraAction = if (personId >= 0) {
                            PhotoPathsAdapter.ExtraAction(R.string.action_not_this_person) { selection ->
                                confirmNotThisPersonPhotos(selection)
                            }
                        } else {
                            null
                        },
                    )
                } else {
                    val existing = facesAdapter
                    if (existing != null && binding.personGrid.adapter === existing) {
                        // nevymieňaj adaptér — aktualizácia dát zachová pozíciu scrollu
                        existing.updateItems(facesSorted)
                    } else {
                        facesAdapter = PersonFacesAdapter(
                            this, facesSorted.toMutableList(),
                            onClick = { face -> openPhoto(face.mediaFullPath) },
                            onLongClick = { face -> showFaceMenu(face) },
                        )
                        binding.personGrid.adapter = facesAdapter
                    }
                }
            }
        }
    }

    // po zmazaní fotiek vo výberovom režime — adaptér si mriežku upravil sám, tu len zosúladíme
    // lokálne dáta osoby, aby ďalší render()/prepnutie zobrazenia zmazané fotky nevzkriesilo
    private fun onPhotosDeleted(deleted: List<String>) {
        val gone = deleted.toHashSet()
        loadedFaces = loadedFaces.filter { !gone.contains(it.mediaFullPath) }
        photoPaths = ArrayList(photoPaths.filter { !gone.contains(it) })
    }

    private fun showFaceMenu(face: FaceEntity) {
        val items = ArrayList<RadioItem>()
        items.add(RadioItem(0, getString(R.string.action_move_to_person)))
        if (personId >= 0) items.add(RadioItem(1, getString(R.string.action_not_this_person)))
        RadioGroupDialog(this, items) {
            when (it as Int) {
                0 -> moveFace(face)
                1 -> notThisPerson(face)
            }
        }
    }

    private fun moveFace(face: FaceEntity) {
        val fid = face.id ?: return
        ensureBackgroundThread {
            val persons = PeopleDatabase.getInstance(this).PeopleDao().getPersons().filter { it.id != personId }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val items = ArrayList<RadioItem>()
                persons.forEachIndexed { index, person -> items.add(RadioItem(index, person.name ?: "#${person.id}")) }
                items.add(RadioItem(persons.size, getString(R.string.new_person)))
                RadioGroupDialog(this, items, titleId = R.string.action_move_to_person) {
                    val which = it as Int
                    if (which == persons.size) {
                        promptName(null) { name -> assignToNewPerson(fid, name, face) }
                    } else {
                        assignToPerson(fid, persons[which].id, face)
                    }
                }
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
            if (manualIds.contains(fid)) dao.deleteAssignment(fid)
            dao.insertCannotLink(CannotLinkEntity(fid, personId)) // aby sa už nenavrhla
            runOnUiThread { facesAdapter?.removeFace(face) }
        }
    }

    // hromadné „toto nie je [osoba]" z výberového režimu — JEDNO potvrdenie pre celý výber
    private fun confirmNotThisPersonPhotos(selection: ArrayList<String>) {
        if (personId < 0 || selection.isEmpty()) return
        ConfirmationDialog(
            this,
            message = getString(R.string.not_this_person_confirm, personName ?: ""),
            positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel,
            dialogTitle = getString(R.string.action_not_this_person),
        ) {
            notThisPersonForPaths(selection)
        }
    }

    // odoberie osobe VŠETKY tváre na vybraných fotkách — spracovanie na pozadí, potom re-render
    private fun notThisPersonForPaths(selection: List<String>) {
        if (personId < 0) return
        val pathSet = selection.toHashSet()
        val faces = loadedFaces.filter { pathSet.contains(it.mediaFullPath) }
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            faces.forEach { f ->
                f.id?.let { fid ->
                    dao.deleteAssignment(fid)
                    dao.insertCannotLink(CannotLinkEntity(fid, personId))
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loadedFaces = loadedFaces.filter { !pathSet.contains(it.mediaFullPath) }
                toast(R.string.person_saved)
                render()
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

    private fun openPhoto(path: String) {
        // uzavretý set = len fotky tejto osoby (swipe neuteká do celého priečinka)
        org.fossify.gallery.helpers.PathTransfer.forViewer = photoPaths
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, path)
            putExtra(SKIP_AUTHENTICATION, true)
            putExtra(SHOW_ALL, false)
            startActivity(this)
        }
    }

    private fun promptName(initial: String?, onName: (String) -> Unit) {
        val input = MyEditText(this).apply {
            setSingleLine()
            setText(initial ?: "")
        }
        val margin = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        val wrapper = LinearLayout(this).apply {
            setPadding(margin, margin / 2, margin, 0)
            addView(
                input,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val name = input.value
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                setupDialogStuff(wrapper, this, R.string.enter_name)
            }
    }

    companion object {
        const val PERSON_ID = "person_id"
        const val PERSON_NAME = "person_name"
        const val FACE_IDS = "face_ids"
        const val MANUAL_IDS = "manual_ids"
        private const val COLUMNS = 3
    }
}
