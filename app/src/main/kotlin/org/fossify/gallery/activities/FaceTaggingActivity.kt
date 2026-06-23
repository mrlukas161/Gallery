package org.fossify.gallery.activities

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.FaceTagAdapter
import org.fossify.gallery.databinding.ActivityFaceTaggingBinding
import org.fossify.gallery.helpers.DragSelectListener
import org.fossify.gallery.faces.CannotLinkEntity
import org.fossify.gallery.faces.FaceAssignmentEntity
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FaceEntity
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PersonEntity
import org.fossify.gallery.faces.PersonGrouper

// UNLABELED   = mriežka všetkých NEoznačených tvárí, multi-výber -> priradiť k osobe
// SUGGESTIONS = kandidáti blízki centroidu osoby (z potvrdených + Picasa anchorov),
//               POSUVNÍK podobnosti, multi-výber -> "Toto je X" / "Toto nie je" (učenie)
class FaceTaggingActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityFaceTaggingBinding::inflate)
    private var mode = MODE_UNLABELED
    private var personId = -1L
    private var personName: String? = null
    private var adapter: FaceTagAdapter? = null
    private var dragListener: DragSelectListener? = null
    private var allCandidates: List<Pair<FaceEntity, Float>> = emptyList()
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        mode = intent.getIntExtra(MODE, MODE_UNLABELED)
        personId = intent.getLongExtra(PERSON_ID, -1L)
        personName = intent.getStringExtra(PERSON_NAME)
        binding.taggingGrid.layoutManager = GridLayoutManager(this, COLUMNS)
        val dl = DragSelectListener { from, to -> adapter?.selectRange(from, to) }
        dragListener = dl
        binding.taggingGrid.addOnItemTouchListener(dl)
        setupBottomBar()
        setupThresholdBar()
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
        binding.taggingToolbar.menu.clear()
        binding.taggingToolbar.inflateMenu(R.menu.menu_tagging)
        binding.taggingToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.select_all -> {
                    adapter?.selectAll()
                    true
                }

                R.id.clear_selection -> {
                    adapter?.clearSelection()
                    true
                }

                else -> false
            }
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

    private fun setupThresholdBar() {
        if (mode != MODE_SUGGESTIONS) {
            binding.taggingThresholdBar.visibility = View.GONE
            return
        }
        binding.taggingThresholdBar.visibility = View.VISIBLE
        binding.taggingThresholdSeek.max = 100
        binding.taggingThresholdSeek.progress = prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD_PCT)
        binding.taggingThresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                applyThreshold()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt(KEY_THRESHOLD, binding.taggingThresholdSeek.progress).apply()
            }
        })
    }

    private fun updateCount(n: Int) {
        binding.taggingCount.text = getString(R.string.selected_count, n)
        binding.taggingBtnPrimary.isEnabled = n > 0
        binding.taggingBtnSecondary.isEnabled = n > 0
    }

    private fun load() {
        ensureBackgroundThread {
            if (mode == MODE_SUGGESTIONS) {
                val cands = try {
                    computeCandidates()
                } catch (e: Throwable) {
                    emptyList()
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    allCandidates = cands
                    applyThreshold()
                }
            } else {
                val faces = try {
                    loadUnlabeled()
                } catch (e: Throwable) {
                    emptyList()
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    showList(faces)
                    if (faces.isEmpty()) showPlaceholder(R.string.no_unlabeled_faces) else hidePlaceholder()
                }
            }
        }
    }

    private fun showList(faces: List<FaceEntity>) {
        adapter = FaceTagAdapter(
            this, faces.toMutableList(),
            onSelectionChanged = { n -> updateCount(n) },
            onLongPress = { pos -> startDrag(pos) },
        )
        binding.taggingGrid.adapter = adapter
        updateCount(0)
    }

    private fun startDrag(pos: Int) {
        adapter?.selectRange(pos, pos)
        dragListener?.startDrag(pos)
    }

    private fun applyThreshold() {
        val th = binding.taggingThresholdSeek.progress / 100f
        val visible = allCandidates.filter { it.second >= th }.take(SUGGEST_MAX).map { it.first }
        binding.taggingThresholdLabel.text = getString(R.string.threshold_label, th, visible.size)
        showList(visible)
        if (visible.isEmpty()) showPlaceholder(R.string.no_suggestions) else hidePlaceholder()
    }

    private fun showPlaceholder(res: Int) {
        binding.taggingPlaceholder.visibility = View.VISIBLE
        binding.taggingPlaceholder.text = getString(res)
    }

    private fun hidePlaceholder() {
        binding.taggingPlaceholder.visibility = View.GONE
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

    // všetci nepriradení kandidáti s podobnosťou k centroidu osoby (zoradené), filter rieši posuvník
    private fun computeCandidates(): List<Pair<FaceEntity, Float>> {
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
            .sortedByDescending { it.second }
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
                pruneCandidates(ids)
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
            runOnUiThread {
                pruneCandidates(ids)
                adapter?.removeSelected()
            }
        }
    }

    private fun pruneCandidates(ids: List<Long>) {
        if (allCandidates.isEmpty()) return
        val s = ids.toHashSet()
        allCandidates = allCandidates.filter { it.first.id != null && !s.contains(it.first.id) }
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
        private const val SUGGEST_MAX = 300
        private const val PREFS = "galeria_faces"
        private const val KEY_THRESHOLD = "suggest_threshold_pct"
        private const val DEFAULT_THRESHOLD_PCT = 30
        private const val MIN_FACE_SCORE = 0.8f
        private const val MIN_FACE_SIZE = 40
    }
}
