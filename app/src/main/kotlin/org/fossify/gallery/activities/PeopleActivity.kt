package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PeopleAdapter
import org.fossify.gallery.databinding.ActivityPeopleBinding
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceAssignmentEntity
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
                binding.peopleGrid.adapter = PeopleAdapter(
                    this, items,
                    onClick = { person -> openPerson(person) },
                    onLongClick = { person -> showPersonMenu(person) },
                )
            }
        }
    }

    private fun buildItems(): List<Person> {
        val facesDao = FacesDatabase.getInstance(this).FaceDao()
        val peopleDao = PeopleDatabase.getInstance(this).PeopleDao()
        val faces = facesDao.getAllFaces().filter { FaceFilter.isGood(it) }
        // osoba = LEN potvrdené tváre; žiadne auto-skupiny ani domiešavanie
        val all = PersonGrouper.confirmedPersons(faces, peopleDao.getPersons(), peopleDao.getAssignments())
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
            val options = arrayOf(
                getString(R.string.action_rename),
                getString(R.string.action_groups),
                getString(R.string.action_merge),
                getString(R.string.action_delete_person),
            )
            AlertDialog.Builder(this)
                .setTitle(person.name ?: getString(R.string.people))
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> renamePerson(person)
                        1 -> manageGroups(person)
                        2 -> mergePerson(person)
                        3 -> deletePerson(person)
                    }
                }
                .show()
        } else {
            // návrh → pomenovaním ho potvrdíš ako osobu
            promptName(null) { name -> confirmGroup(person, name) }
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
                val names = others.map { it.name ?: "#${it.id}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(R.string.action_merge)
                    .setItems(names) { _, which ->
                        val target = others[which]
                        ensureBackgroundThread {
                            dao.reassignPerson(id, target.id)
                            dao.reassignCannotLinks(id, target.id)
                            dao.deletePerson(id)
                            runOnUiThread { loadPeople() }
                        }
                    }
                    .show()
            }
        }
    }

    private fun deletePerson(person: Person) {
        val id = person.id ?: return
        AlertDialog.Builder(this)
            .setTitle(person.name ?: getString(R.string.people))
            .setMessage(R.string.delete_person_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ensureBackgroundThread {
                    val dao = PeopleDatabase.getInstance(this).PeopleDao()
                    dao.deleteAssignmentsForPerson(id)
                    dao.deleteCannotLinksForPerson(id)
                    dao.deleteAnchorsForPerson(id)
                    dao.deletePerson(id)
                    runOnUiThread { loadPeople() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                val names = groups.map { it.name }.toTypedArray()
                val checked = groups.map { memberOf.contains(it.id) }.toBooleanArray()
                AlertDialog.Builder(this)
                    .setTitle(R.string.action_groups)
                    .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
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
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
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
                val labels = ArrayList<String>()
                labels.add(getString(R.string.all_people))
                groups.forEach { labels.add(it.name) }
                AlertDialog.Builder(this)
                    .setTitle(R.string.filter_group)
                    .setItems(labels.toTypedArray()) { _, which ->
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
                    .show()
            }
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
        private const val COLUMNS = 3
    }
}
