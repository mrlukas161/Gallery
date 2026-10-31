package org.fossify.gallery.activities

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.gallery.R
import org.fossify.gallery.adapters.CompareGroupsAdapter
import org.fossify.gallery.databinding.ActivityCompareListBinding
import org.fossify.gallery.helpers.CompareGroup
import org.fossify.gallery.helpers.CompareGroups
import org.fossify.gallery.helpers.CompareHiddenStore
import org.fossify.gallery.helpers.CompareRepository
import org.fossify.gallery.helpers.HiddenGroups
import org.fossify.gallery.helpers.PathTransfer

// Zoznam skupín „Podobné a duplikáty“: záložky Série / Presné kópie / Podobné (s počtom skupín), zoradenie,
// skryté skupiny, výberový režim a „Ďalšia skupina ›“ po návrate z porovnávača.
//
// Skupiny počíta CompareRepository naraz pre všetky záložky (bez zamknutých a vylúčených priečinkov) a drží
// ich v pamäti, kým sa knižnica nezmení — prepnutie záložky nič nenačítava (žiadny súbeh dvoch načítaní)
// a návrat z porovnávača len overí, či sa niečo zmenilo. Výsledok staršieho načítania sa zahodí (generácia).
// Otočenie telefónu obrazovku neruší (configChanges v manifeste); záložka, poradie a pozícia sa aj tak
// ukladajú do onSaveInstanceState pre ostatné prípady (zmena témy, systém uvoľnil appku z pamäte).
@Suppress("TooManyFunctions") // jedna obrazovka: záložky, menu, výber, návrat z porovnávača
class CompareListActivity : SimpleActivity(), CompareGroupsAdapter.Listener {
    private val binding by viewBinding(ActivityCompareListBinding::inflate)

    private var tab = CompareGroups.TAB_BURSTS
    private var sort = CompareGroups.Sort.NEWEST
    private var showHidden = false
    private var snapshot: CompareRepository.Snapshot? = null
    private var hidden = HiddenGroups(emptyList())
    private var shown: List<CompareGroup> = emptyList()
    private var loadGeneration = 0
    private var pendingScrollPosition = RecyclerView.NO_POSITION

    // kľúče skupín, ktoré prídu po práve otvorenej (poradie zo zoznamu alebo z výberu)
    private var nextQueue: ArrayList<String> = ArrayList()
    private var returningFromCompare = false
    private var lastOpenedPaths: List<String> = emptyList()
    private var openNextImmediately = false

    private val adapter: CompareGroupsAdapter?
        get() = binding.compareListGrid.adapter as? CompareGroupsAdapter

    private val compareLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            returningFromCompare = true
            openNextImmediately = result.data?.getBooleanExtra(RESULT_OPEN_NEXT, false) == true
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sort = CompareGroups.Sort.entries.getOrNull(prefs.getInt(PREF_SORT, 0)) ?: CompareGroups.Sort.NEWEST
        tab = when {
            savedInstanceState != null -> savedInstanceState.getInt(STATE_TAB, tab)
            intent.hasExtra(EXTRA_TAB) -> intent.getIntExtra(EXTRA_TAB, tab)
            else -> prefs.getInt(PREF_TAB, tab)
        }.coerceIn(0, CompareGroups.TAB_COUNT - 1)
        if (savedInstanceState != null) {
            showHidden = savedInstanceState.getBoolean(STATE_SHOW_HIDDEN)
            nextQueue = savedInstanceState.getStringArrayList(STATE_NEXT_QUEUE) ?: ArrayList()
            pendingScrollPosition = savedInstanceState.getInt(STATE_POSITION, RecyclerView.NO_POSITION)
        }

        updateSpanCount()
        // jednotne s ostatnými obrazovkami: obsah nezasahuje pod stavový/navigačný pruh, lišta sa pri scrolle prefarbí
        setupEdgeToEdge(
            padTopSystem = listOf(binding.compareListAppbar),
            padBottomSystem = listOf(binding.compareListGrid, binding.compareListBottom),
        )
        setupMaterialScrollListener(binding.compareListGrid, binding.compareListAppbar)
        setupTabs()
        binding.compareListNext.setOnClickListener { openNextGroup() }
    }

    override fun onResume() {
        super.onResume()
        setupMenu()
        // až po naplnení menu — setupTopAppBar farbí ikony, ktoré v lište práve sú
        setupTopAppBar(binding.compareListAppbar, NavigationIcon.Arrow)
        // záložky pod lištou: pozadie preberajú od lišty (tá sa prefarbuje podľa témy), farby textu a
        // indikátora dopočítať z témy — nie pevná zelená/biela z XML
        val textColor = getProperTextColor()
        val unselected = ColorUtils.setAlphaComponent(textColor, TAB_UNSELECTED_ALPHA)
        binding.compareListTabs.setTabTextColors(unselected, textColor)
        binding.compareListTabs.setSelectedTabIndicatorColor(getProperPrimaryColor())
        // len placeholder — dlaždice skupín majú vlastný biely text na tmavom prekrytí
        binding.compareListPlaceholder.setTextColor(textColor)
        binding.compareListProgress.setIndicatorColor(getProperPrimaryColor())
        binding.compareListNext.setBackgroundColor(getProperPrimaryColor())
        binding.compareListNext.setTextColor(getProperPrimaryColor().getContrastColor())
        load()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // otočenie bez znovuvytvorenia obrazovky (manifest): len prepočítať stĺpce, pozícia ostáva
        updateSpanCount()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
        outState.putBoolean(STATE_SHOW_HIDDEN, showHidden)
        // kľúče sú cesty — do Bundle len rozumne veľa (TransactionTooLargeException)
        if (nextQueue.sumOf { it.length } < MAX_SAVED_QUEUE_CHARS) {
            outState.putStringArrayList(STATE_NEXT_QUEUE, nextQueue)
        }
        (binding.compareListGrid.layoutManager as? GridLayoutManager)?.let {
            outState.putInt(STATE_POSITION, it.findFirstVisibleItemPosition())
        }
    }

    private fun updateSpanCount() {
        val widthDp = resources.configuration.screenWidthDp
        val columns = (widthDp / TILE_MIN_WIDTH_DP).coerceAtLeast(MIN_COLUMNS)
        (binding.compareListGrid.layoutManager as? GridLayoutManager)?.spanCount = columns
    }

    private fun setupTabs() {
        val tabs = binding.compareListTabs
        TAB_TITLES.forEach { tabs.addTab(tabs.newTab().setText(it), false) }
        tabs.getTabAt(tab)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(selected: TabLayout.Tab) {
                tab = selected.position
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(PREF_TAB, tab).apply()
                onListChanged(scrollToTop = true)
            }

            override fun onTabUnselected(unselected: TabLayout.Tab) = Unit
            override fun onTabReselected(reselected: TabLayout.Tab) = Unit
        })
    }

    private fun setupMenu() {
        val toolbar = binding.compareListToolbar
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.menu_compare_list)
        updateMenu()
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.compare_sort -> showSortDialog()
                R.id.compare_show_hidden -> {
                    showHidden = !showHidden
                    updateMenu()
                    onListChanged(scrollToTop = false)
                }

                R.id.compare_unhide_all -> confirmUnhideAll()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    private fun updateMenu() {
        val menu = binding.compareListToolbar.menu
        menu.findItem(R.id.compare_show_hidden)?.isChecked = showHidden
        menu.findItem(R.id.compare_unhide_all)?.isVisible = !hidden.isEmpty
    }

    // Načíta skupiny na pozadí. Z pamäte (CompareRepository) je to rýchle; indikátor sa ukáže len vtedy,
    // keď ešte nie je čo zobraziť. Platí len výsledok POSLEDNÉHO načítania.
    private fun load() {
        val generation = ++loadGeneration
        if (snapshot == null) {
            binding.compareListPlaceholder.beGone()
            binding.compareListProgress.show()
        }
        // po porovnaní: zmazaná fotka (Kôš) sa v MediaStore nemusí prejaviť hneď — overiť otvorenú skupinu
        val opened = if (returningFromCompare) lastOpenedPaths else emptyList()
        ensureBackgroundThread {
            val result = try {
                CompareRepository.invalidateIfMissing(opened)
                CompareRepository.load(this) to CompareHiddenStore.get(this)
            } catch (ignored: Throwable) {
                null
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != loadGeneration) return@runOnUiThread
                binding.compareListProgress.hide()
                if (result == null) {
                    if (snapshot == null) showPlaceholder(getString(R.string.pb_load_failed))
                    return@runOnUiThread
                }
                snapshot = result.first
                hidden = result.second
                updateMenu()
                render()
                handleReturnFromCompare()
            }
        }
    }

    // zmena záložky, poradia alebo zobrazenia skrytých — len prefiltrovanie toho, čo je v pamäti
    private fun onListChanged(scrollToTop: Boolean) {
        adapter?.finishActMode()
        hideNextButton()
        nextQueue.clear()
        render()
        if (scrollToTop) binding.compareListGrid.scrollToPosition(0)
    }

    private fun render() {
        val snap = snapshot ?: return
        val visible = snap.groups.filter { !hidden.isHidden(it.paths) }
        updateTabTitles(visible)
        val pool = if (showHidden) snap.groups else visible
        shown = CompareGroups.sort(pool.filter { CompareGroups.tabOf(it.kind) == tab }, sort)

        if (shown.isEmpty()) showPlaceholder(getString(PLACEHOLDERS[tab])) else binding.compareListPlaceholder.beGone()
        val current = adapter
        if (current == null) {
            binding.compareListGrid.adapter = CompareGroupsAdapter(this, shown, hidden, binding.compareListGrid, this)
        } else {
            current.updateItems(shown, hidden)
        }
        if (pendingScrollPosition != RecyclerView.NO_POSITION) {
            if (pendingScrollPosition < shown.size) binding.compareListGrid.scrollToPosition(pendingScrollPosition)
            pendingScrollPosition = RecyclerView.NO_POSITION
        }
    }

    private fun showPlaceholder(text: String) {
        binding.compareListPlaceholder.text = text
        binding.compareListPlaceholder.beVisible()
    }

    private fun updateTabTitles(visible: List<CompareGroup>) {
        val counts = IntArray(CompareGroups.TAB_COUNT)
        visible.forEach { counts[CompareGroups.tabOf(it.kind)]++ }
        for (i in 0 until CompareGroups.TAB_COUNT) {
            val title = getString(R.string.pb_tab_count, getString(TAB_TITLES[i]), counts[i])
            binding.compareListTabs.getTabAt(i)?.text = title
        }
    }

    private fun showSortDialog() {
        val items = arrayListOf(
            RadioItem(CompareGroups.Sort.NEWEST.ordinal, getString(R.string.pb_sort_newest)),
            RadioItem(CompareGroups.Sort.SPACE.ordinal, getString(R.string.pb_sort_space)),
            RadioItem(CompareGroups.Sort.LARGEST.ordinal, getString(R.string.pb_sort_largest)),
        )
        RadioGroupDialog(this, items, sort.ordinal, R.string.pb_sort) { chosen ->
            sort = CompareGroups.Sort.entries.getOrNull(chosen as Int) ?: CompareGroups.Sort.NEWEST
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(PREF_SORT, sort.ordinal).apply()
            onListChanged(scrollToTop = true)
        }
    }

    private fun confirmUnhideAll() {
        ConfirmationDialog(
            activity = this,
            message = getString(R.string.pb_unhide_all_confirm),
            positive = org.fossify.commons.R.string.yes,
            negative = org.fossify.commons.R.string.no,
        ) {
            hidden = CompareHiddenStore.clear(this)
            updateMenu()
            onListChanged(scrollToTop = false)
        }
    }

    // --- otvorenie porovnávača a „Ďalšia skupina ›“ ---

    override fun onOpen(group: CompareGroup) {
        val index = shown.indexOfFirst { it.key == group.key }
        openGroup(group, shown.drop(index + 1).take(MAX_QUEUE).map { it.key })
    }

    override fun onOpenSelected(groups: List<CompareGroup>) {
        val first = groups.firstOrNull() ?: return
        openGroup(first, groups.drop(1).map { it.key })
    }

    private fun openNextGroup() {
        val next = CompareGroups.nextGroup(nextQueue, shown)
        hideNextButton()
        if (next != null) {
            openGroup(next, nextQueue.dropWhile { it != next.key }.drop(1))
        }
    }

    // Porovnanie skupiny. Extra pre porovnávač: pozícia skupiny v zozname a či je ďalšia — porovnávač môže
    // ponúknuť „Ďalšia skupina ›“ a vrátiť RESULT_OPEN_NEXT, zoznam ju potom otvorí hneď.
    private fun openGroup(group: CompareGroup, queue: List<String>) {
        nextQueue = ArrayList(queue)
        lastOpenedPaths = group.paths
        PathTransfer.forCompare = group.paths
        val position = shown.indexOfFirst { it.key == group.key }
        val intent = Intent(this, ComparatorActivity::class.java)
            .putExtra(EXTRA_GROUP_POSITION, position + 1)
            .putExtra(EXTRA_GROUP_COUNT, shown.size)
            .putExtra(EXTRA_HAS_NEXT_GROUP, CompareGroups.nextGroup(queue, shown) != null)
        compareLauncher.launch(intent)
    }

    private fun handleReturnFromCompare() {
        if (!returningFromCompare) return
        returningFromCompare = false
        val openNow = openNextImmediately
        openNextImmediately = false
        if (CompareGroups.nextGroup(nextQueue, shown) == null) {
            hideNextButton()
        } else if (openNow) {
            openNextGroup()
        } else {
            binding.compareListNext.beVisible()
        }
    }

    private fun hideNextButton() {
        binding.compareListNext.beGone()
    }

    // --- výberový režim ---

    override fun onHide(groups: List<CompareGroup>) {
        if (groups.isEmpty()) return
        hidden = CompareHiddenStore.hide(this, groups.map { it.paths })
        toast(resources.getQuantityString(R.plurals.pb_hidden_done, groups.size, groups.size))
        updateMenu()
        onListChanged(scrollToTop = false)
    }

    override fun onUnhide(groups: List<CompareGroup>) {
        if (groups.isEmpty()) return
        hidden = CompareHiddenStore.unhide(this, groups.map { it.paths })
        toast(R.string.pb_unhidden_done)
        updateMenu()
        onListChanged(scrollToTop = false)
    }

    override fun hideActionTitle(): Int =
        if (tab == CompareGroups.TAB_BURSTS) R.string.pb_action_keep_all else R.string.pb_action_not_duplicates

    companion object {
        // vstup: záložka, ktorá sa otvorí (CompareGroups.TAB_*), napr. z karty „Na upratanie“
        const val EXTRA_TAB = "compare_tab"

        // pre porovnávač (ComparatorActivity): poradie skupiny (od 1), počet skupín v záložke, či je ďalšia
        const val EXTRA_GROUP_POSITION = "compare_group_position"
        const val EXTRA_GROUP_COUNT = "compare_group_count"
        const val EXTRA_HAS_NEXT_GROUP = "compare_has_next_group"

        // výsledok porovnávača: true = otvor rovno ďalšiu skupinu
        const val RESULT_OPEN_NEXT = "compare_open_next"

        fun intent(context: Context, tab: Int): Intent =
            Intent(context, CompareListActivity::class.java).putExtra(EXTRA_TAB, tab)

        private const val PREFS = "compare_list"
        private const val PREF_SORT = "sort"
        private const val PREF_TAB = "tab"
        private const val STATE_TAB = "tab"
        private const val STATE_SHOW_HIDDEN = "show_hidden"
        private const val STATE_NEXT_QUEUE = "next_queue"
        private const val STATE_POSITION = "position"

        private const val MIN_COLUMNS = 3
        private const val TILE_MIN_WIDTH_DP = 120
        private const val TAB_UNSELECTED_ALPHA = 179 // ~70 %
        private const val MAX_QUEUE = 200
        private const val MAX_SAVED_QUEUE_CHARS = 50_000

        private val TAB_TITLES = intArrayOf(R.string.pb_tab_bursts, R.string.pb_tab_exact, R.string.pb_tab_similar)
        private val PLACEHOLDERS = intArrayOf(R.string.pb_none_bursts, R.string.pb_none_exact, R.string.pb_none_similar)
    }
}
