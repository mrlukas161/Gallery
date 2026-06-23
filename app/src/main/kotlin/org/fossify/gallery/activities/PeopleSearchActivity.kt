package org.fossify.gallery.activities

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
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PersonEntity
import org.fossify.gallery.helpers.TextNormalizer

// Hľadanie osôb na fotkách. Napíšeš mená/skratky (Luk Ver Šim), prepínač A/ALEBO.
// Funguje nad POTVRDENÝMI priradeniami (fotka -> osoby). Fuzzy + bez diakritiky.
class PeopleSearchActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPeopleSearchBinding::inflate)
    private var persons: List<PersonEntity> = emptyList()
    private var photoPersons: Map<String, Set<Long>> = emptyMap()
    private var meta: Map<String, FaceMediaMeta.Meta> = emptyMap()
    private val ignoreDiacritics = true // default zapnuté (nastaviteľné neskôr)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.searchGrid.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = runSearch()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.searchAndSwitch.setOnCheckedChangeListener { _, _ ->
            updateModeLabel()
            runSearch()
        }
        updateModeLabel()
        loadIndex()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.searchAppbar, NavigationIcon.Arrow)
    }

    private fun updateModeLabel() {
        binding.searchModeLabel.text = getString(
            if (binding.searchAndSwitch.isChecked) R.string.search_all_together else R.string.search_anyone
        )
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
        val raw = binding.searchInput.text?.toString().orEmpty()
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || photoPersons.isEmpty()) {
            showResults(emptyList(), hint = tokens.isEmpty())
            return
        }
        val normTokens = tokens.map { TextNormalizer.normalize(it, ignoreDiacritics) }.filter { it.isNotEmpty() }
        val tokenSets = normTokens.map { t ->
            persons.filter { TextNormalizer.normalize(it.name ?: "", ignoreDiacritics).contains(t) }
                .map { it.id }
                .toSet()
        }
        val and = binding.searchAndSwitch.isChecked
        val matched = photoPersons.entries.filter { (_, pids) ->
            if (and) {
                tokenSets.all { set -> set.any { pids.contains(it) } }
            } else {
                tokenSets.any { set -> set.any { pids.contains(it) } }
            }
        }.map { it.key }
        val sorted = matched.sortedByDescending { meta[it]?.taken ?: 0L }
        showResults(sorted, hint = false)
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
        binding.searchGrid.adapter = PersonPhotosAdapter(this, list) { path -> openPager(list, path) }
    }

    private fun openPager(paths: ArrayList<String>, path: String) {
        val intent = Intent(this, PersonPhotoPagerActivity::class.java)
        intent.putStringArrayListExtra(PersonPhotoPagerActivity.PATHS, paths)
        intent.putExtra(PersonPhotoPagerActivity.START_INDEX, paths.indexOf(path).coerceAtLeast(0))
        startActivity(intent)
    }

    companion object {
        private const val COLUMNS = 3
    }
}
