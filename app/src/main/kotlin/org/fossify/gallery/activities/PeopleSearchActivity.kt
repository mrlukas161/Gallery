package org.fossify.gallery.activities

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PersonPhotosAdapter
import org.fossify.gallery.databinding.ActivityPeopleSearchBinding
import org.fossify.gallery.faces.FaceMediaMeta
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.OcrDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PersonEntity
import org.fossify.gallery.helpers.GridZoom
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION
import org.fossify.gallery.helpers.TextNormalizer
import java.util.Calendar

// Kombinované hľadanie: OSOBY (mená/skratky, A/ALEBO) alebo TEXT (OCR) + voliteľný ČASOVÝ rozsah,
// a tlačidlo MAPA = výsledky podľa MIESTA. (miesto + osoby + čas)
class PeopleSearchActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPeopleSearchBinding::inflate)
    private var persons: List<PersonEntity> = emptyList()
    private var photoPersons: Map<String, Set<Long>> = emptyMap()
    private var meta: Map<String, FaceMediaMeta.Meta> = emptyMap()
    private val ignoreDiacritics = true
    private var timeFrom = 0L
    private var timeTo = Long.MAX_VALUE
    private var lastResults: List<String> = emptyList()
    private val prefs by lazy { getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val lm = GridLayoutManager(this, prefs.getInt("search_columns", COLUMNS))
        binding.searchGrid.layoutManager = lm
        GridZoom.setup(binding.searchGrid, lm, prefs, "search_columns")
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = runSearch()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.searchAndSwitch.setOnCheckedChangeListener { _, _ ->
            updateModeLabel()
            runSearch()
        }
        binding.searchScopeSwitch.setOnCheckedChangeListener { _, _ ->
            updateScopeLabel()
            runSearch()
        }
        binding.searchTimeFrom.setOnClickListener { pickDate(true) }
        binding.searchTimeTo.setOnClickListener { pickDate(false) }
        binding.searchTimeClear.setOnClickListener { clearTime() }
        updateModeLabel()
        updateScopeLabel()
        updateTimeLabels()
        loadIndex()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.searchAppbar, NavigationIcon.Arrow)
        binding.searchToolbar.menu.clear()
        binding.searchToolbar.inflateMenu(R.menu.menu_people_search)
        binding.searchToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.show_on_map) {
                openMap()
                true
            } else {
                false
            }
        }
    }

    private fun openMap() {
        org.fossify.gallery.helpers.PathTransfer.forMap = lastResults
        startActivity(Intent(this, MapActivity::class.java))
    }

    private fun updateModeLabel() {
        binding.searchModeLabel.text = getString(
            if (binding.searchAndSwitch.isChecked) R.string.search_all_together else R.string.search_anyone
        )
    }

    private fun updateScopeLabel() {
        binding.searchScopeLabel.text = getString(
            if (binding.searchScopeSwitch.isChecked) R.string.search_scope_text else R.string.search_scope_people
        )
    }

    private fun pickDate(isFrom: Boolean) {
        val cal = Calendar.getInstance()
        val cur = if (isFrom) timeFrom else timeTo
        if (cur in 1 until Long.MAX_VALUE) cal.timeInMillis = cur
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val c = Calendar.getInstance()
                if (isFrom) {
                    c.set(y, m, d, 0, 0, 0)
                    c.set(Calendar.MILLISECOND, 0)
                    timeFrom = c.timeInMillis
                } else {
                    c.set(y, m, d, 23, 59, 59)
                    c.set(Calendar.MILLISECOND, 999)
                    timeTo = c.timeInMillis
                }
                updateTimeLabels()
                runSearch()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun clearTime() {
        timeFrom = 0L
        timeTo = Long.MAX_VALUE
        updateTimeLabels()
        runSearch()
    }

    private fun updateTimeLabels() {
        binding.searchTimeFrom.text =
            if (timeFrom > 0L) getString(R.string.search_time_from_x, fmt(timeFrom)) else getString(R.string.search_time_from)
        binding.searchTimeTo.text =
            if (timeTo < Long.MAX_VALUE) getString(R.string.search_time_to_x, fmt(timeTo)) else getString(R.string.search_time_to)
    }

    private fun fmt(millis: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        return "${c.get(Calendar.DAY_OF_MONTH)}.${c.get(Calendar.MONTH) + 1}.${c.get(Calendar.YEAR)}"
    }

    private fun loadIndex() {
        ensureBackgroundThread {
            val facesDao = FacesDatabase.getInstance(this).FaceDao()
            val peopleDao = PeopleDatabase.getInstance(this).PeopleDao()
            val pers = peopleDao.getPersons()
            val pidByFace = peopleDao.getAssignments().associate { it.faceId to it.personId }
            val pp = HashMap<String, MutableSet<Long>>()
            for (f in facesDao.getAllFaces()) {
                val id = f.id ?: continue
                val pid = pidByFace[id] ?: continue
                pp.getOrPut(f.mediaFullPath) { HashSet() }.add(pid)
            }
            val m = FaceMediaMeta.load(this, pp.keys)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                persons = pers
                photoPersons = pp
                meta = m
                runSearch()
            }
        }
    }

    private fun runSearch() {
        val tokens = binding.searchInput.text?.toString().orEmpty().trim()
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            showResults(emptyList(), hint = true)
            return
        }
        val textMode = binding.searchScopeSwitch.isChecked
        val and = binding.searchAndSwitch.isChecked
        ensureBackgroundThread {
            val rawPaths = try {
                if (textMode) searchTextPaths(tokens, and) else searchPeoplePaths(tokens, and)
            } catch (e: Throwable) {
                emptyList()
            }
            val missing = rawPaths.filter { !meta.containsKey(it) }
            val extra = if (missing.isNotEmpty()) FaceMediaMeta.load(this, missing) else emptyMap()
            fun taken(p: String) = meta[p]?.taken ?: extra[p]?.taken ?: 0L
            val filtered = rawPaths
                .filter { val t = taken(it); t in timeFrom..timeTo }
                .sortedByDescending { taken(it) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                lastResults = filtered
                showResults(filtered, hint = false)
            }
        }
    }

    private fun searchPeoplePaths(tokens: List<String>, and: Boolean): List<String> {
        if (photoPersons.isEmpty()) return emptyList()
        val normTokens = tokens.map { TextNormalizer.normalize(it, ignoreDiacritics) }.filter { it.isNotEmpty() }
        val tokenSets = normTokens.map { t ->
            persons.filter { TextNormalizer.normalize(it.name ?: "", ignoreDiacritics).contains(t) }
                .map { it.id }
                .toSet()
        }
        return photoPersons.entries.filter { (_, pids) ->
            if (and) tokenSets.all { set -> set.any { pids.contains(it) } }
            else tokenSets.any { set -> set.any { pids.contains(it) } }
        }.map { it.key }
    }

    private fun searchTextPaths(tokens: List<String>, and: Boolean): List<String> {
        val dao = OcrDatabase.getInstance(this).OcrDao()
        val sets = tokens
            .map { TextNormalizer.normalize(it, ignoreDiacritics) }
            .filter { it.isNotEmpty() }
            .map { dao.search(it).toHashSet() }
        if (sets.isEmpty()) return emptyList()
        return if (and) {
            sets.reduce { acc, s -> acc.intersect(s).toHashSet() }.toList()
        } else {
            val u = HashSet<String>()
            sets.forEach { u.addAll(it) }
            u.toList()
        }
    }

    private fun showResults(paths: List<String>, hint: Boolean) {
        if (paths.isEmpty()) {
            binding.searchPlaceholder.text =
                getString(if (hint) R.string.search_hint_people else R.string.search_no_results)
            binding.searchPlaceholder.beVisible()
        } else {
            binding.searchPlaceholder.beGone()
        }
        binding.searchCount.text = getString(R.string.search_count, paths.size)
        val list = ArrayList(paths)
        binding.searchGrid.adapter = PersonPhotosAdapter(this, list) { path -> openPhoto(path) }
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
        private const val COLUMNS = 3
    }
}
