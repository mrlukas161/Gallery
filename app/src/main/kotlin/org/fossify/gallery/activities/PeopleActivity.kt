package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getAlertDialogBuilder
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
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceAssignmentEntity
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FaceFilter
import org.fossify.gallery.faces.GroupMemberEntity
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.Person
import org.fossify.gallery.faces.PersonEntity
import org.fossify.gallery.faces.PersonGroupEntity
import org.fossify.gallery.faces.PersonGrouper
import org.fossify.gallery.helpers.GridZoom

class PeopleActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPeopleBinding::inflate)

    private val prefs by lazy { getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE) }
    private var filterGroupId: Long = -1L
    private var filterGroupName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val lm = GridLayoutManager(this, prefs.getInt("people_columns", COLUMNS))
        binding.peopleGrid.layoutManager = lm
        GridZoom.setup(binding.peopleGrid, lm, prefs, "people_columns")
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.peopleAppbar, NavigationIcon.Arrow)
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

                R.id.show_map -> {
                    startActivity(Intent(this, MapActivity::class.java))
                    true
                }

                else -> false
            }
        }
        loadPeople()
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
                val existing = binding.peopleGrid.adapter as? PeopleAdapter
                if (existing != null) {
                    // nevymieňaj adaptér pri každom onResume — aktualizácia dát zachová pozíciu scrollu
                    existing.updateItems(items)
                } else {
                    binding.peopleGrid.adapter = PeopleAdapter(
                        this, items,
                        onClick = { person -> openPerson(person) },
                        onLongClick = { person -> showPersonMenu(person) },
                    )
                }
            }
        }
    }

    private fun buildItems(): List<Person> {
        val facesDao = FacesDatabase.getInstance(this).FaceDao()
        val peopleDao = PeopleDatabase.getInstance(this).PeopleDao()
        val faces = facesDao.getAllFaces().filter { FaceFilter.isGood(it) }
        // základ = ručne potvrdené tváre
        var all = PersonGrouper.confirmedPersons(faces, peopleDao.getPersons(), peopleDao.getAssignments())
        // + automatické zaradenie tvárí nad zvolenou istotou (0 = vypnuté)
        val threshold = org.fossify.gallery.helpers.FaceAuto.threshold(this)
        if (threshold > 0f) {
            val anchors = try {
                peopleDao.getAllAnchors().groupBy({ it.personId }, { FaceEmbedder.toFloats(it.embedding) })
            } catch (e: Throwable) {
                emptyMap()
            }
            val links = try {
                peopleDao.getCannotLinks()
            } catch (e: Throwable) {
                emptyList()
            }
            all = PersonGrouper.withAutoMatches(all, faces, anchors, links, threshold)
        }
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

    private fun showPersonMenu(person: Person) {
        if (person.isConfirmed) {
            val options = listOf(
                getString(R.string.action_rename),
                getString(R.string.action_groups),
                getString(R.string.action_merge),
                getString(R.string.action_delete_person),
            )
            showOptionsDialog(person.name ?: getString(R.string.people), options) { which ->
                when (which) {
                    0 -> renamePerson(person)
                    1 -> manageGroups(person)
                    2 -> mergePerson(person)
                    3 -> deletePerson(person)
                }
            }
        } else {
            // návrh → pomenovaním ho potvrdíš ako osobu
            promptName(null) { name -> confirmGroup(person, name) }
        }
    }

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

    private fun renamePerson(person: Person) {
        val id = person.id ?: return
        promptName(person.name) { name ->
            ensureBackgroundThread {
                PeopleDatabase.getInstance(this).PeopleDao().renamePerson(id, name)
                runOnUiThread { loadPeople() }
            }
        }
    }

    private fun confirmGroup(person: Person, name: String) {
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            val now = System.currentTimeMillis()
            val newId = dao.insertPerson(PersonEntity(name = name, createdAt = now))
            person.faces.forEach { f ->
                val fid = f.id ?: return@forEach
                dao.upsertAssignment(FaceAssignmentEntity(fid, newId, true, now))
            }
            runOnUiThread {
                toast(R.string.person_saved)
                loadPeople()
            }
        }
    }

    private fun mergePerson(person: Person) {
        val id = person.id ?: return
        ensureBackgroundThread {
            val dao = PeopleDatabase.getInstance(this).PeopleDao()
            val others = dao.getPersons().filter { it.id != id }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (others.isEmpty()) {
                    toast(R.string.no_other_person)
                    return@runOnUiThread
                }
                val items = ArrayList<RadioItem>()
                others.forEachIndexed { index, p -> items.add(RadioItem(index, p.name ?: "#${p.id}")) }
                RadioGroupDialog(this, items, titleId = R.string.action_merge) {
                    val target = others[it as Int]
                    ensureBackgroundThread {
                        dao.reassignPerson(id, target.id)
                        dao.reassignCannotLinks(id, target.id)
                        dao.deletePerson(id)
                        runOnUiThread { loadPeople() }
                    }
                }
            }
        }
    }

    private fun deletePerson(person: Person) {
        val id = person.id ?: return
        ConfirmationDialog(
            this,
            messageId = R.string.delete_person_confirm,
            positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel,
            dialogTitle = person.name ?: getString(R.string.people),
        ) {
            ensureBackgroundThread {
                val dao = PeopleDatabase.getInstance(this).PeopleDao()
                dao.deleteAssignmentsForPerson(id)
                dao.deleteCannotLinksForPerson(id)
                dao.deleteAnchorsForPerson(id)
                dao.deletePerson(id)
                runOnUiThread { loadPeople() }
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
                                if (want && !was) dao.addMember(GroupMemberEntity(g.id, pid))
                                else if (!want && was) dao.removeMember(g.id, pid)
                            }
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

    private fun createGroupWith(name: String, personId: Long) {
        ensureBackgroundThread {
            val dao = ExtrasDatabase.getInstance(this).ExtrasDao()
            val gid = dao.insertGroup(PersonGroupEntity(name = name, createdAt = System.currentTimeMillis()))
            dao.addMember(GroupMemberEntity(gid, personId))
            runOnUiThread { toast(R.string.person_saved) }
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
    }
}
