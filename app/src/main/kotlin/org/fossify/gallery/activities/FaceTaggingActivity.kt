package org.fossify.gallery.activities

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getBottomNavigationBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.commons.views.MyEditText
import org.fossify.gallery.R
import org.fossify.gallery.adapters.FaceTagAdapter
import org.fossify.gallery.databinding.ActivityFaceTaggingBinding
import org.fossify.gallery.dialogs.ChangeSortingDialog
import org.fossify.gallery.extensions.config
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FaceEntity
import org.fossify.gallery.faces.FaceFilter
import org.fossify.gallery.faces.FaceMediaMeta
import org.fossify.gallery.faces.IgnoredFaceEntity
import org.fossify.gallery.faces.FaceSorter
import org.fossify.gallery.faces.FaceVisibility
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PersonGrouper
import org.fossify.gallery.family.FamilyRecorder
import org.fossify.gallery.helpers.DragSelectListener
import org.fossify.gallery.helpers.GridZoom

// UNLABELED   = všetky NEoznačené tváre, multi-výber -> priradiť k osobe (triedenie jednotným dialógom)
// SUGGESTIONS = kandidáti blízki centroidu osoby, posuvník podobnosti, multi-výber -> "Toto je X" / "Toto nie je";
//               voliteľne CHRONOLOGICKY (podľa dátumu) — užitočné pri deťoch.
class FaceTaggingActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityFaceTaggingBinding::inflate)
    private var mode = MODE_UNLABELED
    private var personId = -1L
    private var personName: String? = null
    private var adapter: FaceTagAdapter? = null
    private var dragListener: DragSelectListener? = null
    private var allCandidates: List<Pair<FaceEntity, Float>> = emptyList()
    private var unlabeledFaces: List<FaceEntity> = emptyList()
    private var meta: Map<String, FaceMediaMeta.Meta> = emptyMap()
    private var chronological = false
    private var showingIgnored = false
    private var showContext = false
    private var mediaPathFilter: String? = null
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // tváre presunutých/zmazaných fotiek a fotiek mimo rozsahu (zamknuté/vylúčené priečinky) sa
    // na označovanie ani v návrhoch neponúkajú — len v UI, DB ostáva bez zmeny; cache na dobu otvorenia obrazovky
    private val faceVisibility by lazy { FaceVisibility(this) }

    // posuvník podobnosti volá onProgressChanged na každý pixel ťahu — mriežka sa prekresľuje najviac raz za
    // THRESHOLD_THROTTLE_MS (živý náhľad ostane, ale bez trhania); po pustení posuvníka sa prekreslí hneď
    private val uiHandler = Handler(Looper.getMainLooper())
    private var thresholdPending = false
    private val thresholdRunnable = Runnable {
        thresholdPending = false
        applyThreshold()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        mode = intent.getIntExtra(MODE, MODE_UNLABELED)
        personId = intent.getLongExtra(PERSON_ID, -1L)
        personName = intent.getStringExtra(PERSON_NAME)
        // „Označiť osoby" z prehliadača: len tváre práve zobrazenej fotky (bez extra = všetky neoznačené)
        mediaPathFilter = intent.getStringExtra(MEDIA_PATH)
        val lm = GridLayoutManager(this, prefs.getInt("tagging_columns", COLUMNS))
        binding.taggingGrid.layoutManager = lm
        GridZoom.setup(binding.taggingGrid, lm, prefs, "tagging_columns")
        val dl = DragSelectListener { from, to -> adapter?.selectRange(from, to) }
        dragListener = dl
        binding.taggingGrid.addOnItemTouchListener(dl)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.taggingAppbar),
            // spodná lišta akcií aj koniec mriežky (ktorý lišta zakrýva) odsadené o systémovú navigáciu
            padBottomSystem = listOf(binding.taggingGrid, binding.taggingBottomBar),
        )
        setupMaterialScrollListener(binding.taggingGrid, binding.taggingAppbar)
        setupBottomBar()
        setupThresholdBar()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(thresholdRunnable)
    }

    override fun onResume() {
        super.onResume()
        binding.taggingToolbar.title = if (mode == MODE_SUGGESTIONS) {
            getString(R.string.suggestions_for, personName ?: "")
        } else {
            getString(R.string.tag_faces_title)
        }
        binding.taggingToolbar.menu.clear()
        binding.taggingToolbar.inflateMenu(R.menu.menu_tagging)
        binding.taggingToolbar.menu.findItem(R.id.sort_order)?.isVisible = mode != MODE_SUGGESTIONS
        binding.taggingToolbar.menu.findItem(R.id.chronological)?.apply {
            isVisible = mode == MODE_SUGGESTIONS
            isChecked = chronological
        }
        binding.taggingToolbar.menu.findItem(R.id.show_context)?.apply {
            isChecked = showContext
        }
        binding.taggingToolbar.menu.findItem(R.id.show_ignored)?.apply {
            isVisible = mode != MODE_SUGGESTIONS
            isChecked = showingIgnored
        }
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

                R.id.show_context -> {
                    showContext = !showContext
                    item.isChecked = showContext
                    adapter?.showContext = showContext
                    true
                }

                R.id.sort_order -> {
                    ChangeSortingDialog(this, false, true, FACES_SORT_PATH) { reSortUnlabeled() }
                    true
                }

                R.id.chronological -> {
                    chronological = !chronological
                    item.isChecked = chronological
                    applyThreshold()
                    true
                }

                R.id.show_ignored -> {
                    showingIgnored = !showingIgnored
                    item.isChecked = showingIgnored
                    setupBottomBar()
                    load()
                    true
                }

                else -> false
            }
        }
        // až po naplnení menu — setupTopAppBar farbí ikony, ktoré v lište práve sú
        setupTopAppBar(binding.taggingAppbar, NavigationIcon.Arrow)
        // panely pod lištou a spodná lišta vo farbách témy (nie natvrdo zelené / biele)
        updateTextColors(binding.taggingAppbar)
        updateTextColors(binding.taggingBottomBar)
        binding.taggingBottomBar.setBackgroundColor(getBottomNavigationBackgroundColor())
        binding.taggingPlaceholder.setTextColor(getProperTextColor())
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
            binding.taggingBtnSecondary.visibility = View.VISIBLE
            binding.taggingBtnSecondary.text =
                getString(if (showingIgnored) R.string.restore else R.string.ignore_faces)
            binding.taggingBtnPrimary.setOnClickListener { assignSelectedToPicked() }
            binding.taggingBtnSecondary.setOnClickListener {
                if (showingIgnored) unignoreSelected() else ignoreSelected()
            }
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
                if (!fromUser) {
                    applyThreshold()
                } else if (!thresholdPending) {
                    thresholdPending = true
                    uiHandler.postDelayed(thresholdRunnable, THRESHOLD_THROTTLE_MS)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt(KEY_THRESHOLD, binding.taggingThresholdSeek.progress).apply()
                // po pustení posuvníka hneď presný stav
                uiHandler.removeCallbacks(thresholdRunnable)
                thresholdPending = false
                applyThreshold()
            }
        })
    }

    private fun updateCount(n: Int) {
        binding.taggingCount.text = getString(R.string.selected_count, n)
        binding.taggingBtnPrimary.isEnabled = n > 0
        binding.taggingBtnSecondary.isEnabled = n > 0
        // farba textu tlačidiel je jednotná (téma), vypnutý stav preto vyjadruje priehľadnosť
        val alpha = if (n > 0) 1f else 0.4f
        binding.taggingBtnPrimary.alpha = alpha
        binding.taggingBtnSecondary.alpha = alpha
    }

    private fun load() {
        ensureBackgroundThread {
            if (mode == MODE_SUGGESTIONS) {
                val cands = try {
                    computeCandidates()
                } catch (e: Throwable) {
                    emptyList()
                }
                val m = FaceMediaMeta.load(this, cands.map { it.first.mediaFullPath }.distinct())
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    allCandidates = cands
                    meta = m
                    applyThreshold()
                }
            } else {
                val faces = try {
                    loadUnlabeled()
                } catch (e: Throwable) {
                    emptyList()
                }
                val m = FaceMediaMeta.load(this, faces.map { it.mediaFullPath }.distinct())
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    unlabeledFaces = faces
                    meta = m
                    reSortUnlabeled()
                }
            }
        }
    }

    private fun reSortUnlabeled() {
        val sorted = FaceSorter.sortFaces(unlabeledFaces, meta, config.getFolderSorting(FACES_SORT_PATH))
        showList(sorted)
        if (sorted.isEmpty()) showPlaceholder(R.string.no_unlabeled_faces) else hidePlaceholder()
    }

    private fun showList(faces: List<FaceEntity>) {
        val existing = adapter
        if (existing != null) {
            // adaptér vzniká len RAZ — ďalšie zmeny (posuvník, triedenie, obnova návrhov) idú cez updateItems,
            // nech sa nestratí scroll ani výber (výber ostane len pre tváre, ktoré sú stále vidno)
            existing.updateItems(faces)
        } else {
            val created = FaceTagAdapter(
                this, faces.toMutableList(),
                onSelectionChanged = { n -> updateCount(n) },
                onLongPress = { pos -> startDrag(pos) },
            )
            created.showContext = showContext
            adapter = created
            binding.taggingGrid.adapter = created
        }
        updateCount(adapter?.selectedCount() ?: 0)
    }

    private fun startDrag(pos: Int) {
        adapter?.selectRange(pos, pos)
        dragListener?.startDrag(pos)
    }

    private fun applyThreshold() {
        val th = binding.taggingThresholdSeek.progress / 100f
        var visible = allCandidates.filter { it.second >= th }.take(SUGGEST_MAX).map { it.first }
        if (chronological) {
            visible = visible.sortedBy { meta[it.mediaFullPath]?.taken ?: 0L }
        }
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
        val ignored = ExtrasDatabase.getInstance(this).ExtrasDao().getIgnoredIds().toHashSet()
        val path = mediaPathFilter
        val all = facesDao.getAllFaces()
            .filter { FaceFilter.isGood(it) && (path == null || it.mediaFullPath == path) }
        val wanted = if (showingIgnored) {
            all.filter { val id = it.id; id != null && ignored.contains(id) }
        } else {
            all.filter { val id = it.id; id != null && !assigned.contains(id) && !ignored.contains(id) }
        }
        // viditeľnosť (disk) sa zisťuje až po zoradení a len kým sa nenaplní limit
        return wanted.sortedByDescending { it.score }
            .asSequence()
            .filter { faceVisibility.isVisible(it.mediaFullPath) }
            .take(UNLABELED_CAP)
            .toList()
    }

    private fun computeCandidates(): List<Pair<FaceEntity, Float>> {
        val facesDao = FacesDatabase.getInstance(this).FaceDao()
        val peopleDao = PeopleDatabase.getInstance(this).PeopleDao()
        val allFaces = facesDao.getAllFaces()
        val pool = allFaces.filter { FaceFilter.isGood(it) }
        val assignments = peopleDao.getAssignments()
        val assigned = assignments.map { it.faceId }.toHashSet()
        val mineIds = assignments.filter { it.personId == personId }.map { it.faceId }.toHashSet()
        // ťažisko z RUČNE potvrdených tvárí — BEZ filtra skóre (používateľ ich potvrdil, musia rátať do učenia)
        val confirmedEmb = allFaces.filter { it.id != null && mineIds.contains(it.id) }
            .mapNotNull { it.embedding?.let { b -> FaceEmbedder.toFloats(b) } }
        val anchorEmb = peopleDao.getAnchorEmbeddings(personId).map { FaceEmbedder.toFloats(it) }
        // viac ťažísk (okuliare/starnutie/deti) → porovnaj s najbližším
        val centroids = PersonGrouper.subCentroids(confirmedEmb + anchorEmb)
        if (centroids.isEmpty()) return emptyList()
        val cannot = peopleDao.getCannotLinks().filter { it.personId == personId }.map { it.faceId }.toHashSet()
        val ignored = ExtrasDatabase.getInstance(this).ExtrasDao().getIgnoredIds().toHashSet()
        // applyThreshold ukáže najviac SUGGEST_MAX najpodobnejších nad prahom = vždy začiatok tohto
        // zoradeného zoznamu, preto stačí zistiť viditeľnosť (disk) len pre prvých SUGGEST_MAX viditeľných
        return pool
            .filter { val id = it.id; id != null && !assigned.contains(id) && !cannot.contains(id) && !ignored.contains(id) }
            .mapNotNull { f -> f.embedding?.let { b -> f to PersonGrouper.maxCosine(FaceEmbedder.toFloats(b), centroids) } }
            .sortedByDescending { it.second }
            .asSequence()
            .filter { faceVisibility.isVisible(it.first.mediaFullPath) }
            .take(SUGGEST_MAX)
            .toList()
    }

    private fun confirmSelected() {
        val ids = adapter?.selectedIds().orEmpty()
        if (ids.isEmpty() || personId < 0) return
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            // všetky potvrdené tváre naraz v jednej transakcii
            dao.assignFaces(ids, personId, System.currentTimeMillis())
            FamilyRecorder.facesAssigned(this, ids, personId)
            runOnUiThread {
                toast(R.string.person_saved)
                load() // prepočítaj návrhy z nového vzoru = real-time učenie
            }
        }
    }

    // „toto nie je [osoba]" je nevratné (tvár sa už danej osobe nebude navrhovať) — preto s potvrdením
    private fun rejectSelected() {
        val ids = adapter?.selectedIds().orEmpty()
        if (ids.isEmpty() || personId < 0) return
        ConfirmationDialog(
            this,
            message = resources.getQuantityString(R.plurals.c2_reject_suggestions_confirm, ids.size, ids.size, personName ?: ""),
            positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel,
            dialogTitle = getString(R.string.this_is_not),
        ) {
            ensureBackgroundThread {
                val dao = PeopleDatabase.getInstance(this).PeopleDao()
                dao.rejectFacesForPerson(ids, personId)
                FamilyRecorder.facesRejected(this, ids, personId)
                runOnUiThread {
                    load() // prepočítaj (zohľadní cannot-link aj nové potvrdenia)
                }
            }
        }
    }

    private fun assignSelectedToPicked() {
        val ids = adapter?.selectedIds().orEmpty()
        if (ids.isEmpty()) return
        ensureBackgroundThread {
            val persons = PeopleDatabase.getInstance(this).PeopleDao().getPersons()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val items = ArrayList<RadioItem>()
                persons.forEachIndexed { index, person -> items.add(RadioItem(index, person.name ?: "#${person.id}")) }
                items.add(RadioItem(persons.size, getString(R.string.new_person)))
                RadioGroupDialog(this, items, titleId = R.string.assign_to_person) {
                    val which = it as Int
                    if (which == persons.size) {
                        promptName { name -> doAssign(ids, null, name) }
                    } else {
                        doAssign(ids, persons[which].id, null)
                    }
                }
            }
        }
    }

    // A7: chyba DB (napr. poškodený súbor, plný disk) nesmie zhodiť appku na vlákne na pozadí —
    // priradenie je jedna transakcia, takže sa buď zapíše celé, alebo vôbec
    @Suppress("TooGenericExceptionCaught")
    private fun doAssign(ids: List<Long>, existingId: Long?, newName: String?) {
        ensureBackgroundThread {
            try {
                val dao = PeopleDatabase.getInstance(this).PeopleDao()
                val extras = ExtrasDatabase.getInstance(this).ExtrasDao()
                val now = System.currentTimeMillis()
                // priradenie (aj založenie novej osoby) naraz v jednej transakcii
                if (existingId != null) {
                    dao.assignFaces(ids, existingId, now)
                    FamilyRecorder.facesAssigned(this, ids, existingId)
                } else {
                    val newId = dao.createPersonWithFaces(newName.orEmpty(), ids, now)
                    FamilyRecorder.personCreated(this, newId, ids)
                }
                ids.forEach {
                    extras.deleteIgnored(it) // priradenie zruší prípadné ignorovanie
                }
                runOnUiThread {
                    toast(R.string.person_saved)
                    adapter?.removeSelected()
                }
            } catch (e: Exception) {
                android.util.Log.e("FaceTagging", "priradenie tvárí zlyhalo", e)
                runOnUiThread { toast(R.string.ta1_assign_failed) }
            }
        }
    }

    private fun ignoreSelected() {
        val ids = adapter?.selectedIds().orEmpty()
        if (ids.isEmpty()) return
        ensureBackgroundThread {
            val dao = ExtrasDatabase.getInstance(this).ExtrasDao()
            ids.forEach { dao.insertIgnored(IgnoredFaceEntity(it)) }
            runOnUiThread { adapter?.removeSelected() }
        }
    }

    private fun unignoreSelected() {
        val ids = adapter?.selectedIds().orEmpty()
        if (ids.isEmpty()) return
        ensureBackgroundThread {
            val dao = ExtrasDatabase.getInstance(this).ExtrasDao()
            ids.forEach { dao.deleteIgnored(it) }
            runOnUiThread { adapter?.removeSelected() }
        }
    }

    private fun promptName(onName: (String) -> Unit) {
        val input = MyEditText(this).apply { setSingleLine() }
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
        const val MODE = "mode"
        const val PERSON_ID = "person_id"
        const val PERSON_NAME = "person_name"

        // rovnaký kľúč ako ViewPagerActivity.EXTRA_TAG_MEDIA_PATH (kontrakt s prehliadačom)
        const val MEDIA_PATH = "media_path"
        const val MODE_UNLABELED = 0
        const val MODE_SUGGESTIONS = 1
        private const val COLUMNS = 4
        private const val UNLABELED_CAP = 1500
        private const val SUGGEST_MAX = 300
        private const val PREFS = "galeria_faces"
        private const val KEY_THRESHOLD = "suggest_threshold_pct"
        private const val DEFAULT_THRESHOLD_PCT = 30
        private const val THRESHOLD_THROTTLE_MS = 90L
        private const val FACES_SORT_PATH = "faces_tagging"
    }
}
