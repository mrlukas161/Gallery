package org.fossify.gallery.adapters

import android.view.Menu
import android.view.ViewGroup
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ItemPeoplePersonBinding
import org.fossify.gallery.faces.FaceCropLoader
import org.fossify.gallery.faces.Person

// Mriežka osôb v obrazovke Ľudia. Ťuk = otvor osobu, dlhé podržanie = VÝBEROVÝ REŽIM (rovnako ako
// v mriežkach fotiek): akcie nad osobou (premenovať, skupiny, zlúčiť, odstrániť) sú v menu výberu a dajú
// sa robiť aj nad viacerými osobami naraz. Samotné akcie vykonáva obrazovka (potvrdenia, databáza).
class PeopleAdapter(
    activity: BaseSimpleActivity,
    private var people: List<Person>,
    recyclerView: MyRecyclerView,
    val onClick: (Person) -> Unit,
    val onRename: (Person) -> Unit,
    val onGroups: (Person) -> Unit,
    val onMerge: (List<Person>) -> Unit,
    val onDelete: (List<Person>) -> Unit,
) : MyRecyclerViewAdapter(activity, recyclerView, { any -> onClick(any as Person) }) {

    init {
        setupDragListener(true)
    }

    // aktualizácia dát BEZ výmeny adaptéra — RecyclerView si zachová pozíciu scrollu a výber,
    // pokiaľ všetky vybrané osoby v novom zozname ešte sú (inak sa výber zruší)
    fun updateItems(newPeople: List<Person>) {
        people = newPeople
        val keys = HashSet<Int>()
        newPeople.forEach { keys.add(keyOf(it)) }
        val staleSelection = selectedKeys.any { !keys.contains(it) }
        notifyDataSetChanged()
        if (staleSelection) {
            finishActMode()
        }
    }

    override fun getActionMenuId() = R.menu.cab_people

    override fun prepareActionMode(menu: Menu) {
        val selected = getSelectedPeople()
        val single = selected.size == 1
        val allConfirmed = selected.isNotEmpty() && selected.all { it.isConfirmed }
        menu.findItem(R.id.cab_rename)?.apply {
            // jedna potvrdená osoba = premenovať; jeden nepotvrdený návrh = pomenovať (potvrdí ho ako osobu)
            isVisible = single
            setTitle(if (selected.firstOrNull()?.isConfirmed == false) R.string.c2_action_name_person else R.string.action_rename)
        }
        menu.findItem(R.id.cab_groups)?.isVisible = single && allConfirmed
        menu.findItem(R.id.cab_merge)?.apply {
            isVisible = allConfirmed
            setTitle(if (selected.size > 1) R.string.c2_action_merge_selected else R.string.action_merge)
        }
        menu.findItem(R.id.cab_delete)?.isVisible = allConfirmed
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        val selected = getSelectedPeople()
        when (id) {
            R.id.cab_rename -> selected.singleOrNull()?.let { onRename(it) }
            R.id.cab_groups -> selected.singleOrNull()?.takeIf { it.isConfirmed }?.let { onGroups(it) }
            R.id.cab_merge -> onMerge(selected.filter { it.isConfirmed })
            R.id.cab_delete -> onDelete(selected.filter { it.isConfirmed })
            R.id.cab_select_all -> selectAll()
        }
    }

    override fun getSelectableItemCount() = people.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = people.getOrNull(position)?.let { keyOf(it) }

    override fun getItemKeyPosition(key: Int) = people.indexOfFirst { keyOf(it) == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPeoplePersonBinding.inflate(layoutInflater, parent, false)
        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val person = people.getOrNull(position) ?: return
        holder.bindView(person, true, true) { itemView, _ ->
            val itemBinding = ItemPeoplePersonBinding.bind(itemView)
            val cover = person.cover
            if (cover != null) {
                FaceCropLoader.load(cover, itemBinding.personImage)
            } else {
                itemBinding.personImage.setImageBitmap(null)
            }
            // potvrdená osoba ukáže meno, návrh ukáže počet tvárí
            itemBinding.personCount.text = person.name ?: person.faceCount.toString()

            val isSelected = selectedKeys.contains(keyOf(person))
            itemBinding.personCheck.beVisibleIf(isSelected)
            if (isSelected) {
                itemBinding.personCheck.background?.applyColorFilter(properPrimaryColor)
                itemBinding.personCheck.applyColorFilter(contrastColor)
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = people.size

    // vybrané osoby v poradí, v akom sú v mriežke
    private fun getSelectedPeople(): List<Person> = people.filter { selectedKeys.contains(keyOf(it)) }

    // kľúč výberu: potvrdená osoba podľa id; nepotvrdený návrh (bez id) podľa id jeho titulnej tváre
    // (záporné číslo, aby sa nikdy nezrazil s id osoby)
    private fun keyOf(person: Person): Int {
        val id = person.id
        return if (id != null) id.hashCode() else -1 - (person.cover?.id ?: 0L).hashCode()
    }
}
