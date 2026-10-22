package org.fossify.gallery.adapters

import android.view.Menu
import android.view.ViewGroup
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ItemPersonPhotoBinding
import org.fossify.gallery.faces.FaceCropLoader
import org.fossify.gallery.faces.FaceEntity

// Mriežka výrezov tvárí jednej osoby. Ťuk = otvor fotku, dlhé podržanie = VÝBEROVÝ REŽIM (rovnako ako
// v mriežkach fotiek): oprava tvárí (presun k inej osobe / „toto nie je táto osoba") je v menu výberu
// a robí sa nad celým výberom naraz. Samotné akcie vykonáva obrazovka (potvrdenie, databáza).
// C7: [badgeOf] označí tváre, ktoré nie sú ručne potvrdené (automaticky / neisté). [confirmMode] = sekcia
// „Potvrď“ na karte osoby: ťuk = áno (onClick), vo výbere „Áno, je to táto osoba“ a „Nie je táto osoba“.
class PersonFacesAdapter(
    activity: BaseSimpleActivity,
    val faces: MutableList<FaceEntity>,
    recyclerView: MyRecyclerView,
    // „toto nie je táto osoba" dáva zmysel len pri potvrdenej osobe (nie pri nepotvrdenej skupine)
    private val canReject: Boolean,
    val onClick: (FaceEntity) -> Unit,
    val onMove: (List<FaceEntity>) -> Unit,
    val onReject: (List<FaceEntity>) -> Unit,
    private val badgeOf: (FaceEntity) -> Int = { BADGE_NONE },
    private val confirmMode: Boolean = false,
    private val onConfirm: (List<FaceEntity>) -> Unit = {},
) : MyRecyclerViewAdapter(activity, recyclerView, { any -> onClick(any as FaceEntity) }) {

    init {
        setupDragListener(true)
    }

    // aktualizácia dát BEZ výmeny adaptéra — RecyclerView si zachová pozíciu scrollu a výber,
    // pokiaľ všetky vybrané tváre v novom zozname ešte sú (inak sa výber zruší)
    fun updateItems(newFaces: List<FaceEntity>) {
        faces.clear()
        faces.addAll(newFaces)
        val keys = HashSet<Int>()
        faces.forEach { f -> f.id?.let { keys.add(it.hashCode()) } }
        val staleSelection = selectedKeys.any { !keys.contains(it) }
        notifyDataSetChanged()
        if (staleSelection) {
            finishActMode()
        }
    }

    // okamžitá spätná väzba po oprave — vyhoď tváre z mriežky (autoritatívny prepočet príde po návrate späť);
    // zároveň ukončí výberový režim
    fun removeFaces(ids: Set<Long>) {
        val positions = faces.indices.filter { i -> faces[i].id?.let { ids.contains(it) } == true }.sortedDescending()
        positions.forEach { faces.removeAt(it) }
        removeSelectedItems(ArrayList(positions))
    }

    override fun getActionMenuId() = R.menu.cab_person_faces

    override fun prepareActionMode(menu: Menu) {
        menu.findItem(R.id.cab_not_this_person)?.isVisible = canReject
        menu.findItem(R.id.cab_confirm_person)?.isVisible = confirmMode
        menu.findItem(R.id.cab_move_to_person)?.isVisible = !confirmMode
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        val selected = getSelectedFaces()
        when (id) {
            R.id.cab_confirm_person -> if (confirmMode) onConfirm(selected)
            R.id.cab_move_to_person -> if (!confirmMode) onMove(selected)
            R.id.cab_not_this_person -> if (canReject) onReject(selected)
            R.id.cab_select_all -> selectAll()
        }
    }

    override fun getSelectableItemCount() = faces.size

    override fun getIsItemSelectable(position: Int) = faces.getOrNull(position)?.id != null

    override fun getItemSelectionKey(position: Int) = faces.getOrNull(position)?.id?.hashCode()

    override fun getItemKeyPosition(key: Int) = faces.indexOfFirst { it.id?.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPersonPhotoBinding.inflate(layoutInflater, parent, false)
        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val face = faces.getOrNull(position) ?: return
        holder.bindView(face, true, true) { itemView, _ ->
            val itemBinding = ItemPersonPhotoBinding.bind(itemView)
            FaceCropLoader.load(face, itemBinding.photoImage)

            val isSelected = face.id?.let { selectedKeys.contains(it.hashCode()) } == true
            itemBinding.photoCheck.beVisibleIf(isSelected)
            if (isSelected) {
                itemBinding.photoCheck.background?.applyColorFilter(properPrimaryColor)
                itemBinding.photoCheck.applyColorFilter(contrastColor)
            }
            bindBadge(itemBinding, badgeOf(face))
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = faces.size

    private fun bindBadge(itemBinding: ItemPersonPhotoBinding, badge: Int) {
        val badgeView = itemBinding.photoBadge
        badgeView.beVisibleIf(badge != BADGE_NONE)
        if (badge == BADGE_NONE) return
        val uncertain = badge == BADGE_UNCERTAIN
        badgeView.text = resources.getString(if (uncertain) R.string.tc2_badge_uncertain else R.string.tc2_badge_auto)
        badgeView.contentDescription =
            resources.getString(if (uncertain) R.string.tc2_badge_uncertain_desc else R.string.tc2_badge_auto_desc)
        badgeView.background?.applyColorFilter(properPrimaryColor)
        badgeView.setTextColor(contrastColor)
    }

    // vybrané tváre v poradí, v akom sú v mriežke
    private fun getSelectedFaces(): List<FaceEntity> =
        faces.filter { f -> f.id?.let { selectedKeys.contains(it.hashCode()) } == true }

    companion object {
        const val BADGE_NONE = 0
        const val BADGE_AUTO = 1
        const val BADGE_UNCERTAIN = 2
    }
}
