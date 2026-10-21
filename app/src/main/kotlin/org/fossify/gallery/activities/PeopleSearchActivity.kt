package org.fossify.gallery.activities

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getDatePickerDialogTheme
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PhotoPathsAdapter
import org.fossify.gallery.databinding.ActivityPeopleSearchBinding
import org.fossify.gallery.faces.FaceMediaMeta
import org.fossify.gallery.faces.OcrDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PeopleIndex
import org.fossify.gallery.faces.PersonEntity
import org.fossify.gallery.faces.QrDatabase
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
    private var qrOnly = false
    private var searchAdapter: PhotoPathsAdapter? = null

    // poradové číslo hľadania (len UI vlákno) — výsledok staršieho, pomalšieho hľadania
    // nesmie prepísať výsledky novšieho (napr. pri rýchlom písaní)
    private var searchGeneration = 0

    // fotky zmazané z výsledkov (kôš) — OCR/osobný index o nich ešte vie, no do výsledkov sa vrátiť nesmú
    private val deletedPaths = HashSet<String>()
    private var indexLoaded = false
    private var resumedOnce = false
    private val prefs by lazy { getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val lm = GridLayoutManager(this, prefs.getInt("search_columns", COLUMNS))
        binding.searchGrid.layoutManager = lm
        GridZoom.setup(binding.searchGrid, lm, prefs, "search_columns")
        setupEdgeToEdge(
            padTopSystem = listOf(binding.searchAppbar),
            padBottomImeAndSystem = listOf(binding.searchGrid),
        )
        setupMaterialScrollListener(binding.searchGrid, binding.searchAppbar)
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
        binding.searchToolbar.menu.clear()
        binding.searchToolbar.inflateMenu(R.menu.menu_people_search)
        binding.searchToolbar.menu.findItem(R.id.toggle_qr_filter)?.isChecked = qrOnly
        binding.searchToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.show_on_map -> {
                    openMap()
                    true
                }
                R.id.toggle_qr_filter -> {
                    qrOnly = !qrOnly
                    item.isChecked = qrOnly
                    runSearch()
                    true
                }
                else -> false
            }
        }
        // až po naplnení menu — setupTopAppBar farbí ikony, ktoré v lište práve sú
        setupTopAppBar(binding.searchAppbar, NavigationIcon.Arrow)
        // panel pod lištou (pole hľadania, prepínače, dátumy) a placeholder vo farbách témy
        updateTextColors(binding.searchAppbar)
        binding.searchTimeClear.applyColorFilter(getProperTextColor())
        binding.searchPlaceholder.setTextColor(getProperTextColor())
        // návrat z prehliadača / značenia tvárí: osoby priradené medzitým sa musia prejaviť vo výsledkoch
        // (prvé zobrazenie index načítal onCreate)
        if (resumedOnce) loadIndex() else resumedOnce = true
    }

    override fun onDestroy() {
        super.onDestroy()
        // po zavretí hľadania sa staré slovo nesmie zvýrazňovať na nesúvisiacich fotkách
        org.fossify.gallery.helpers.SmartSearch.lastQuery = ""
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
            getDatePickerDialogTheme(),
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
            val peopleDao = PeopleDatabase.getInstance(this).PeopleDao()
            val pers = peopleDao.getPersons()
            // C9: fotky osoby z jedného zdroja pravdy (ručne aj automaticky zaradené, rovnako ako v Ľuďoch)
            val pp = PeopleIndex.get(this).photoPersons
            val m = FaceMediaMeta.load(this, pp.keys)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                // výsledky sa prepočítajú len pri prvom načítaní alebo keď sa index naozaj zmenil
                val changed = !indexLoaded || pers != persons || pp != photoPersons
                indexLoaded = true
                persons = pers
                photoPersons = pp
                meta = m
                if (changed) runSearch()
            }
        }
    }

    private fun runSearch() {
        val generation = ++searchGeneration
        val rawQuery = binding.searchInput.text?.toString().orEmpty().trim()
        // zapamätaj dopyt aj z tohto hľadania — TextSelectActivity ním predznačí nájdené
        // slová priamo na fotke (rovnako ako hlavná lupa); prázdny dopyt zvýraznenie zruší
        org.fossify.gallery.helpers.SmartSearch.lastQuery = rawQuery
        val tokens = rawQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        val onlyQr = qrOnly
        if (tokens.isEmpty() && !onlyQr) {
            lastResults = emptyList()
            showResults(emptyList(), hint = true)
            return
        }
        val textMode = binding.searchScopeSwitch.isChecked
        val and = binding.searchAndSwitch.isChecked
        // stav obrazovky sa číta na UI vlákne — pozadie pracuje so snímkou z času spustenia
        val from = timeFrom
        val to = timeTo
        val metaSnapshot = meta
        ensureBackgroundThread {
            val qrSet: HashSet<String>? = if (onlyQr) {
                try {
                    QrDatabase.getInstance(this).QrDao().getPathsWithQr().toHashSet()
                } catch (e: Throwable) {
                    HashSet()
                }
            } else {
                null
            }
            var rawPaths = try {
                when {
                    tokens.isEmpty() -> qrSet?.toList() ?: emptyList()
                    textMode -> searchTextPaths(tokens, and)
                    else -> searchPeoplePaths(tokens, and)
                }
            } catch (e: Throwable) {
                emptyList()
            }
            if (qrSet != null && tokens.isNotEmpty()) {
                rawPaths = rawPaths.filter { qrSet.contains(it) }
            }
            val missing = rawPaths.filter { !metaSnapshot.containsKey(it) }
            val extra = if (missing.isNotEmpty()) FaceMediaMeta.load(this, missing) else emptyMap()
            fun taken(p: String) = metaSnapshot[p]?.taken ?: extra[p]?.taken ?: 0L
            val filtered = rawPaths
                .filter { val t = taken(it); t in from..to }
                .sortedByDescending { taken(it) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (generation != searchGeneration) return@runOnUiThread // medzitým beží novšie hľadanie
                // fotky zmazané v tejto obrazovke sa do výsledkov nevrátia, kým o nich vie starý index
                val shown = filtered.filter { !deletedPaths.contains(it) }
                lastResults = shown
                showResults(shown, hint = false)
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
        updateResultsHeader(paths.size, hint)
        val existing = searchAdapter
        if (existing == null) {
            val adapter = PhotoPathsAdapter(
                this, ArrayList(paths), binding.searchGrid,
                onClick = { path -> openPhoto(path) },
                onDeleted = { deleted -> onResultsDeleted(deleted) },
                onChanged = { renamed -> onResultsRenamed(renamed) },
            )
            searchAdapter = adapter
            binding.searchGrid.adapter = adapter
        } else if (existing.paths != paths) {
            // adaptér vzniká len RAZ — pri písaní do hľadania sa mení len obsah (updateItems), mriežka
            // neskáče na začiatok; rovnaké výsledky sa nedotknú vôbec (ostane scroll aj prebiehajúci výber)
            existing.updateItems(paths)
        }
    }

    private fun updateResultsHeader(count: Int, hint: Boolean) {
        if (count == 0) {
            binding.searchPlaceholder.text =
                getString(if (hint) R.string.search_hint_people else R.string.search_no_results)
            binding.searchPlaceholder.beVisible()
        } else {
            binding.searchPlaceholder.beGone()
        }
        binding.searchCount.text = getString(R.string.search_count, count)
    }

    // fotky zmazané vo výberovom režime: adaptér si mriežku upravil sám, tu zosúladíme dáta obrazovky
    // (mapa a prehliadač berú lastResults, počet výsledkov) a zapamätáme si ich, nech ich nevzkriesi ďalšie hľadanie
    private fun onResultsDeleted(deleted: List<String>) {
        val gone = deleted.toHashSet()
        deletedPaths.addAll(gone)
        lastResults = lastResults.filter { !gone.contains(it) }
        updateResultsHeader(lastResults.size, false)
    }

    // premenované / odkryté fotky (stará cesta -> nová): mapa a prehliadač dostanú novú cestu; index
    // (osoby, text, QR) ju pozná pod starou, preto si starú zapamätáme ako zmiznutú — ďalšie hľadanie ju nevzkriesi
    private fun onResultsRenamed(renamed: Map<String, String>) {
        if (renamed.isEmpty()) return
        deletedPaths.addAll(renamed.keys)
        lastResults = lastResults.map { renamed[it] ?: it }
    }

    private fun openPhoto(path: String) {
        org.fossify.gallery.helpers.PathTransfer.forViewer = lastResults
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
