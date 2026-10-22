package org.fossify.gallery.activities

import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.commons.views.MyAppCompatCheckbox
import org.fossify.commons.views.MyEditText
import org.fossify.commons.views.MyTextView
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PeopleAdapter
import org.fossify.gallery.databinding.ActivityPeopleBinding
import org.fossify.gallery.dialogs.DecideFacesDialog
import org.fossify.gallery.dialogs.FamilyProposalsDialog
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceVisibility
import org.fossify.gallery.faces.GroupMemberEntity
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PeopleIndex
import org.fossify.gallery.faces.Person
import org.fossify.gallery.faces.PersonEntity
import org.fossify.gallery.faces.PersonGroupEntity
import org.fossify.gallery.faces.PersonGrouper
import org.fossify.gallery.family.FamilyProposals
import org.fossify.gallery.family.FamilyRecorder
import org.fossify.gallery.helpers.FaceAccuracy
import org.fossify.gallery.helpers.GridZoom

class PeopleActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPeopleBinding::inflate)

    private val prefs by lazy { getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE) }

    // výzva na nový import vzorov z Picasy po prepnutí odtlačkov na v2
    private val picasaReimport = PicasaReimportBanner(this, { binding.peopleReimportBanner }) { loadPeople() }
    private var filterGroupId: Long = -1L
    private var filterGroupName: String? = null

    // tváre presunutých/zmazaných fotiek a fotiek mimo rozsahu (zamknuté/vylúčené priečinky) sa
    // v zozname nerátajú ani neukazujú — len v UI, DB ostáva bez zmeny; cache na dobu otvorenia obrazovky
    private val faceVisibility by lazy { FaceVisibility(this) }

    private val peopleAdapter: PeopleAdapter?
        get() = binding.peopleGrid.adapter as? PeopleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val lm = GridLayoutManager(this, prefs.getInt("people_columns", COLUMNS))
        binding.peopleGrid.layoutManager = lm
        GridZoom.setup(binding.peopleGrid, lm, prefs, "people_columns")
        setupEdgeToEdge(
            padTopSystem = listOf(binding.peopleAppbar),
            padBottomSystem = listOf(binding.peopleGrid),
        )
        setupMaterialScrollListener(binding.peopleGrid, binding.peopleAppbar)
    }

    override fun onResume() {
        super.onResume()
        binding.peoplePlaceholder.setTextColor(getProperTextColor())
        binding.peopleToolbar.title = filterGroupName ?: getString(R.string.people)
        binding.peopleToolbar.menu.clear()
        binding.peopleToolbar.inflateMenu(R.menu.menu_people)
        binding.peopleToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.tag_faces -> {
                    startActivity(Intent(this, FaceTaggingActivity::class.java))
                    true
                }

                R.id.search_people -> {
                    startActivity(Intent(this, PeopleSearchActivity::class.java))
                    true
                }

                R.id.groups_filter -> {
                    showGroupFilter()
                    true
                }

                R.id.manage_groups -> {
                    showGroupManager()
                    true
                }

                R.id.decide_faces -> {
                    DecideFacesDialog(this) { loadPeople() }
                    true
                }

                R.id.show_map -> {
                    startActivity(Intent(this, MapActivity::class.java))
                    true
                }

                else -> false
            }
        }
        // až po naplnení menu — setupTopAppBar farbí ikony, ktoré v lište práve sú
        setupTopAppBar(binding.peopleAppbar, NavigationIcon.Arrow)
        loadPeople()
        refreshFamilyBanner()
        picasaReimport.refresh()
        refreshConfusedBanner()
        // C10: pri nabíjaní sa starý odhad presnosti prepočíta (jedno krátke vlákno)
        FaceAccuracy.refreshWhileCharging(this) { runOnUiThread { refreshConfusedBanner() } }
    }

    // C10: „Ján ↔ Peter sa pletú – pomôž mi ich rozlíšiť“ → „Pomôž mi rozhodnúť“ (C11) pre túto dvojicu
    private fun refreshConfusedBanner() {
        ensureBackgroundThread {
            val candidate = FaceAccuracy.load(this)?.worstPair()
            // upozornenie len vtedy, keď je pre dvojicu naozaj o čom rozhodovať (fronta C11 nie je prázdna)
            val snapshot = try {
                if (candidate == null) null else PeopleIndex.get(this)
            } catch (ignored: SQLException) {
                null
            } catch (ignored: IllegalStateException) {
                null
            }
            val worst = candidate?.takeIf {
                snapshot != null && PersonGrouper.decisionQueue(
                    snapshot.contested, it.personId to it.otherId, limit = 1,
                ).isNotEmpty()
            }
            val names = snapshot?.persons?.associate { it.id to it.name }.orEmpty()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val banner = binding.peopleConfusedBanner
                val a = worst?.let { names[it.personId] }
                val b = worst?.let { names[it.otherId] }
                if (worst == null || a.isNullOrBlank() || b.isNullOrBlank()) {
                    banner.beGone()
                    return@runOnUiThread
                }
                banner.text = getString(R.string.tc2_confused_banner, a, b)
                banner.setTextColor(getProperPrimaryColor())
                banner.setOnClickListener {
                    DecideFacesDialog(this, worst.personId to worst.otherId) { loadPeople() }
                }
                banner.beVisible()
            }
        }
    }

    // Lišta „Máš N návrhov z telefónu …“ (rodinné prepojenie). reopen = po rozhodnutí rovno ukáž ďalšie návrhy.
    private fun refreshFamilyBanner(reopen: Boolean = false) {
        ensureBackgroundThread {
            val proposals = try {
                FamilyProposals(this).let { it.openItems() to it.personNames() }
            } catch (ignored: RuntimeException) {
                emptyList<FamilyProposals.Item>() to emptyMap<Long, String?>()
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                showFamilyBanner(proposals.first)
                if (reopen && proposals.first.isNotEmpty()) {
                    FamilyProposalsDialog(this, proposals.first, proposals.second) { afterFamilyDecision() }
                }
            }
        }
    }

    private fun showFamilyBanner(items: List<FamilyProposals.Item>) {
        val banner = binding.peopleFamilyBanner
        if (items.isEmpty()) {
            banner.beGone()
            return
        }
        val phones = items.map { it.fromDeviceId }.distinct()
        banner.text = if (phones.size == 1) {
            val phone = items.first().phoneName ?: getString(R.string.fp_unknown_phone)
            resources.getQuantityString(R.plurals.fp_banner, items.size, items.size, phone)
        } else {
            resources.getQuantityString(R.plurals.fp_banner_many_phones, items.size, items.size)
        }
        banner.setTextColor(getProperPrimaryColor())
        banner.setOnClickListener { refreshFamilyBanner(reopen = true) }
        banner.beVisible()
    }

    private fun afterFamilyDecision() {
        loadPeople()
        refreshFamilyBanner(reopen = true)
    }

    private fun loadPeople() {
        ensureBackgroundThread {
            val items = try {
                buildItems()
            } catch (e: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (items.isEmpty()) {
                    binding.peoplePlaceholder.text = getString(R.string.people_empty)
                    binding.peoplePlaceholder.beVisible()
                } else {
                    binding.peoplePlaceholder.beGone()
                }
                val existing = peopleAdapter
                if (existing != null) {
                    // nevymieňaj adaptér pri každom onResume — aktualizácia dát zachová pozíciu scrollu aj výber
                    existing.updateItems(items)
                } else {
                    binding.peopleGrid.adapter = PeopleAdapter(
                        this, items, binding.peopleGrid,
                        onClick = { person -> openPerson(person) },
                        onRename = { person -> renameOrConfirm(person) },
                        onGroups = { person -> manageGroups(person) },
                        onMerge = { persons -> mergePersons(persons) },
                        onDelete = { persons -> deletePersons(persons) },
                    )
                }
            }
        }
    }

    private fun buildItems(): List<Person> {
        val all = faceVisibility.filterPersons(loadPersons(this))
        if (filterGroupId < 0) return all
        val members = ExtrasDatabase.getInstance(this).ExtrasDao().getMembers(filterGroupId).toHashSet()
        return all.filter { p -> p.id != null && members.contains(p.id) }
    }

    private fun openPerson(person: Person) {
        val intent = Intent(this, PersonActivity::class.java)
        intent.putExtra(PersonActivity.PERSON_ID, person.id ?: -1L)
        intent.putExtra(PersonActivity.PERSON_NAME, person.name)
        intent.putExtra(PersonActivity.FACE_IDS, person.faces.mapNotNull { it.id }.toLongArray())
        intent.putExtra(PersonActivity.MANUAL_IDS, person.manualFaceIds.toLongArray())
        startActivity(intent)
    }

    // po dokončenej akcii nad výberom: ukonči výberový režim a obnov zoznam (v existujúcom adaptéri)
    private fun afterAction() {
        peopleAdapter?.finishActMode()
        loadPeople()
    }

    private fun personLabel(person: Person) = person.name ?: "#${person.id}"

    private fun personLabel(person: PersonEntity) = person.name ?: "#${person.id}"

    // Zoznam akcií s DYNAMICKÝM titulkom (meno osoby) — RadioGroupDialog vie len titleId,
    // preto vlastný zoznam MyTextView riadkov témovaný cez setupDialogStuff (Fossify vzor).
    private fun showOptionsDialog(title: String, options: List<String>, onPick: (Int) -> Unit) {
        val margin = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, margin / 2, 0, margin / 2)
        }
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        var dialog: AlertDialog? = null
        options.forEachIndexed { index, option ->
            holder.addView(
                MyTextView(this).apply {
                    text = option
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setPadding(margin, margin * 3 / 4, margin, margin * 3 / 4)
                    setBackgroundResource(ripple.resourceId)
                    setOnClickListener {
                        dialog?.dismiss()
                        onPick(index)
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        val view = ScrollView(this).apply { addView(holder) }
        getAlertDialogBuilder().apply {
            setupDialogStuff(view, this, titleText = title) { alertDialog -> dialog = alertDialog }
        }
    }

    // potvrdená osoba sa premenuje; nepotvrdený návrh sa pomenovaním potvrdí ako osoba
    private fun renameOrConfirm(person: Person) {
        if (person.isConfirmed) {
            renamePerson(person)
        } else {
            promptName(null) { name -> confirmGroup(person, name) }
        }
    }

    private fun renamePerson(person: Person) {
        val id = person.id ?: return
        promptName(person.name) { name ->
            ensureBackgroundThread {
                PeopleDatabase.getInstance(this).PeopleDao().renamePerson(id, name)
                FamilyRecorder.personRenamed(this, id, person.name, name)
                runOnUiThread { afterAction() }
            }
        }
    }

    private fun confirmGroup(person: Person, name: String) {
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            // nová osoba + priradenie všetkých tvárí v jednej transakcii
            val faceIds = person.faces.mapNotNull { it.id }
            val personId = dao.createPersonWithFaces(name, faceIds, System.currentTimeMillis())
            FamilyRecorder.personCreated(this, personId, faceIds)
            runOnUiThread {
                toast(R.string.person_saved)
                afterAction()
            }
        }
    }

    // Zlúčenie: vybrané osoby sa zlúčia do jednej. Pri jednej vybranej osobe sa cieľ volí spomedzi ostatných,
    // pri viacerých vybraných je cieľom jedna z vybraných. Vždy s potvrdením (nevratná akcia).
    private fun mergePersons(selected: List<Person>) {
        val sources = selected.filter { it.id != null }
        if (sources.isEmpty()) return
        val selectedIds = sources.mapNotNull { it.id }.toHashSet()
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            val all = dao.getPersons()
            val candidates = if (sources.size == 1) {
                all.filter { !selectedIds.contains(it.id) }
            } else {
                all.filter { selectedIds.contains(it.id) }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (candidates.isEmpty()) {
                    toast(R.string.no_other_person)
                    return@runOnUiThread
                }
                val items = ArrayList<RadioItem>()
                candidates.forEachIndexed { index, p -> items.add(RadioItem(index, personLabel(p))) }
                val titleId = if (sources.size == 1) R.string.action_merge else R.string.c2_merge_target_title
                RadioGroupDialog(this, items, titleId = titleId) {
                    val target = candidates[it as Int]
                    confirmMerge(sources.filter { s -> s.id != target.id }, target)
                }
            }
        }
    }

    private fun confirmMerge(from: List<Person>, target: PersonEntity) {
        if (from.isEmpty()) return
        val message = if (from.size == 1) {
            getString(R.string.c2_merge_confirm, personLabel(from.first()), personLabel(target))
        } else {
            getString(R.string.c2_merge_confirm_multi, from.size, personLabel(target))
        }
        ConfirmationDialog(
            this,
            message = message,
            positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel,
            dialogTitle = getString(R.string.c2_merge_title),
        ) {
            val fromIds = from.mapNotNull { it.id }
            ensureBackgroundThread {
                val ok = try {
                    // tváre, negatívne pravidlá aj vzory všetkých zlúčených osôb naraz v jednej transakcii
                    PeopleDatabase.getInstance(this).PeopleDao().mergeManyInto(fromIds, target.id)
                    true
                } catch (e: Throwable) {
                    false
                }
                if (ok) {
                    FamilyRecorder.personsMerged(this, fromIds, target.id)
                    try {
                        val extras = ExtrasDatabase.getInstance(this).ExtrasDao()
                        fromIds.forEach { extras.moveMemberships(it, target.id) }
                    } catch (e: Throwable) {
                        // skupiny sú vedľajšie — zlúčenie osôb už prebehlo
                    }
                }
                runOnUiThread {
                    if (ok) afterAction() else toast(R.string.c2_operation_failed)
                }
            }
        }
    }

    private fun deletePersons(persons: List<Person>) {
        val ids = persons.mapNotNull { it.id }
        if (ids.isEmpty()) return
        val dialogTitle: String
        val message: String
        if (persons.size == 1) {
            dialogTitle = persons.first().name ?: getString(R.string.people)
            message = getString(R.string.delete_person_confirm)
        } else {
            dialogTitle = getString(R.string.action_delete_person)
            message = resources.getQuantityString(R.plurals.c2_delete_persons_confirm, ids.size, ids.size)
        }
        ConfirmationDialog(
            this,
            message = message,
            positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel,
            dialogTitle = dialogTitle,
        ) {
            ensureBackgroundThread {
                val ok = try {
                    PeopleDatabase.getInstance(this).PeopleDao().deletePersonsCompletely(ids)
                    true
                } catch (e: Throwable) {
                    false
                }
                if (ok) {
                    FamilyRecorder.personsDeleted(this, persons.mapNotNull { p -> p.id?.let { it to p.name } }.toMap())
                    try {
                        val extras = ExtrasDatabase.getInstance(this).ExtrasDao()
                        ids.forEach { extras.removePersonFromGroups(it) }
                    } catch (e: Throwable) {
                        // skupiny sú vedľajšie — osoby už zanikli
                    }
                }
                runOnUiThread {
                    if (ok) afterAction() else toast(R.string.c2_operation_failed)
                }
            }
        }
    }

    private fun manageGroups(person: Person) {
        val pid = person.id ?: return
        ensureBackgroundThread {
            val dao = ExtrasDatabase.getInstance(this).ExtrasDao()
            val groups = dao.getGroups()
            val memberOf = dao.getGroupsForPerson(pid).toHashSet()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (groups.isEmpty()) {
                    promptName(null) { name -> createGroupWith(name, pid) }
                    return@runOnUiThread
                }
                val checked = groups.map { memberOf.contains(it.id) }.toBooleanArray()
                val margin = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
                val holder = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(margin, margin / 2, margin, margin / 2)
                }
                groups.forEachIndexed { i, g ->
                    holder.addView(
                        MyAppCompatCheckbox(this).apply {
                            text = g.name
                            isChecked = checked[i]
                            setPadding(0, margin / 4, 0, margin / 4)
                            setOnCheckedChangeListener { _, isCheckedNow -> checked[i] = isCheckedNow }
                        },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                    )
                }
                val view = ScrollView(this).apply { addView(holder) }
                getAlertDialogBuilder()
                    .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                        ensureBackgroundThread {
                            groups.forEachIndexed { i, g ->
                                val want = checked[i]
                                val was = memberOf.contains(g.id)
                                if (want && !was) addToGroup(g.id, pid)
                                else if (!want && was) dao.removeMember(g.id, pid)
                            }
                            // pri zapnutom filtri skupiny sa zoznam mohol zmeniť
                            runOnUiThread { afterAction() }
                        }
                    }
                    .setNeutralButton(R.string.new_group) { _, _ ->
                        promptName(null) { name -> createGroupWith(name, pid) }
                    }
                    .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                    .apply {
                        setupDialogStuff(view, this, R.string.action_groups)
                    }
            }
        }
    }

    // pridanie do skupiny sa zapíše aj pre prepojený telefón (odobratie sa tam neprenáša, členovia sa len pridávajú)
    private fun addToGroup(groupId: Long, personId: Long) {
        ExtrasDatabase.getInstance(this).ExtrasDao().addMember(GroupMemberEntity(groupId, personId))
        FamilyRecorder.groupChanged(this, groupId)
    }

    private fun createGroupWith(name: String, personId: Long) {
        ensureBackgroundThread {
            val dao = ExtrasDatabase.getInstance(this).ExtrasDao()
            val gid = dao.insertGroup(PersonGroupEntity(name = name, createdAt = System.currentTimeMillis()))
            dao.addMember(GroupMemberEntity(gid, personId))
            FamilyRecorder.groupChanged(this, gid)
            runOnUiThread {
                toast(R.string.person_saved)
                afterAction()
            }
        }
    }

    private fun showGroupFilter() {
        ensureBackgroundThread {
            val groups = ExtrasDatabase.getInstance(this).ExtrasDao().getGroups()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val items = ArrayList<RadioItem>()
                items.add(RadioItem(0, getString(R.string.all_people)))
                groups.forEachIndexed { index, g -> items.add(RadioItem(index + 1, g.name)) }
                // predznač aktuálny filter, nech vidno, čo je zvolené
                val checkedId = if (filterGroupId < 0) 0 else groups.indexOfFirst { it.id == filterGroupId } + 1
                RadioGroupDialog(this, items, checkedItemId = checkedId, titleId = R.string.filter_group) {
                    val which = it as Int
                    if (which == 0) {
                        filterGroupId = -1L
                        filterGroupName = null
                    } else {
                        filterGroupId = groups[which - 1].id
                        filterGroupName = groups[which - 1].name
                    }
                    binding.peopleToolbar.title = filterGroupName ?: getString(R.string.people)
                    loadPeople()
                }
            }
        }
    }

    // Správa skupín (premenovanie / zmazanie) — doteraz sa skupina dala len založiť a nikdy opraviť ani zrušiť.
    private fun showGroupManager() {
        ensureBackgroundThread {
            val groups = ExtrasDatabase.getInstance(this).ExtrasDao().getGroups()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (groups.isEmpty()) {
                    toast(R.string.c2_no_groups)
                    return@runOnUiThread
                }
                val items = ArrayList<RadioItem>()
                groups.forEachIndexed { index, g -> items.add(RadioItem(index, g.name)) }
                RadioGroupDialog(this, items, titleId = R.string.c2_manage_groups) {
                    val group = groups[it as Int]
                    val options = listOf(getString(R.string.c2_group_rename), getString(R.string.c2_group_delete))
                    showOptionsDialog(group.name, options) { which ->
                        when (which) {
                            0 -> renameGroup(group)
                            1 -> deleteGroup(group)
                        }
                    }
                }
            }
        }
    }

    private fun renameGroup(group: PersonGroupEntity) {
        promptName(group.name) { name ->
            ensureBackgroundThread {
                ExtrasDatabase.getInstance(this).ExtrasDao().renameGroup(group.id, name)
                FamilyRecorder.groupRenamed(this, group.name, name)
                runOnUiThread {
                    // premenovaná skupina je práve aktívny filter → titulok lišty musí ukázať nový názov
                    if (filterGroupId == group.id) {
                        filterGroupName = name
                        binding.peopleToolbar.title = name
                    }
                }
            }
        }
    }

    private fun deleteGroup(group: PersonGroupEntity) {
        ConfirmationDialog(
            this,
            message = getString(R.string.c2_group_delete_confirm, group.name),
            positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel,
            dialogTitle = group.name,
        ) {
            ensureBackgroundThread {
                ExtrasDatabase.getInstance(this).ExtrasDao().deleteGroupWithMembers(group.id)
                runOnUiThread {
                    // zmazaná skupina nesmie ostať aktívnym filtrom
                    if (filterGroupId == group.id) {
                        filterGroupId = -1L
                        filterGroupName = null
                        binding.peopleToolbar.title = getString(R.string.people)
                    }
                    loadPeople()
                }
            }
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
        private const val COLUMNS = 3

        // Zloženie osôb: ručne potvrdené tváre + (pri zapnutom automatickom zaraďovaní) tváre zaradené podľa
        // podobnosti. Volá sa z pozadia. Jeden zdroj pravdy (PeopleIndex, C9) zdieľajú Ľudia, karta osoby,
        // Návrhy, hľadanie, mapa aj prehliadač — všade sú tie isté tváre osoby.
        @WorkerThread
        fun loadPersons(context: Context): List<Person> = PeopleIndex.get(context).persons
    }
}
