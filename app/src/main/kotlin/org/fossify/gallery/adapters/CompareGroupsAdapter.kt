package org.fossify.gallery.adapters

import android.view.Menu
import android.view.ViewGroup
import com.bumptech.glide.Glide
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ItemCompareGroupBinding
import org.fossify.gallery.helpers.CompareGroup
import org.fossify.gallery.helpers.CompareKind
import org.fossify.gallery.helpers.HiddenGroups
import java.io.File

// Mriežka skupín „Podobné a duplikáty“. Ťuk = otvor porovnávač, dlhé podržanie = VÝBEROVÝ REŽIM
// (Otvoriť, Ponechať všetky / Nie sú to duplikáty = skryť, Vrátiť do zoznamu). Dlaždica: titulka = fotka,
// ktorá by ostala, typ skupiny, počet, dátum najnovšej fotky a koľko miesta uvoľní.
@Suppress("TooManyFunctions") // MyRecyclerViewAdapter vyžaduje 9 metód výberového režimu
class CompareGroupsAdapter(
    activity: BaseSimpleActivity,
    private var groups: List<CompareGroup>,
    private var hidden: HiddenGroups,
    recyclerView: MyRecyclerView,
    private val listener: Listener,
) : MyRecyclerViewAdapter(activity, recyclerView, { any -> listener.onOpen(any as CompareGroup) }) {

    interface Listener {
        fun onOpen(group: CompareGroup)
        fun onOpenSelected(groups: List<CompareGroup>)
        fun onHide(groups: List<CompareGroup>)
        fun onUnhide(groups: List<CompareGroup>)

        // „Ponechať všetky“ pri sériách, „Nie sú to duplikáty“ pri duplikátoch
        fun hideActionTitle(): Int
    }

    private val dateFormat = android.text.format.DateFormat.getDateFormat(activity)

    init {
        setupDragListener(true)
    }

    // aktualizácia BEZ výmeny adaptéra — RecyclerView si zachová pozíciu scrollu; výber ostane, len ak
    // všetky vybrané skupiny v novom zozname ešte sú
    fun updateItems(newGroups: List<CompareGroup>, newHidden: HiddenGroups) {
        groups = newGroups
        hidden = newHidden
        val keys = newGroups.mapTo(HashSet()) { keyOf(it) }
        val staleSelection = selectedKeys.any { it !in keys }
        notifyDataSetChanged()
        if (staleSelection) {
            finishActMode()
        }
    }

    override fun getActionMenuId() = R.menu.cab_compare_groups

    override fun prepareActionMode(menu: Menu) {
        val selected = getSelectedGroups()
        menu.findItem(R.id.cab_compare_hide)?.apply {
            isVisible = selected.any { !hidden.isHidden(it.paths) }
            setTitle(listener.hideActionTitle())
        }
        menu.findItem(R.id.cab_compare_unhide)?.isVisible = selected.any { hidden.isHidden(it.paths) }
    }

    override fun actionItemPressed(id: Int) {
        val selected = getSelectedGroups()
        if (selected.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_compare_open -> {
                finishActMode()
                listener.onOpenSelected(selected)
            }

            R.id.cab_compare_hide -> {
                finishActMode()
                listener.onHide(selected.filter { !hidden.isHidden(it.paths) })
            }

            R.id.cab_compare_unhide -> {
                finishActMode()
                listener.onUnhide(selected.filter { hidden.isHidden(it.paths) })
            }

            R.id.cab_select_all -> selectAll()
        }
    }

    override fun getSelectableItemCount() = groups.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = groups.getOrNull(position)?.let { keyOf(it) }

    override fun getItemKeyPosition(key: Int) = groups.indexOfFirst { keyOf(it) == key }

    override fun onActionModeCreated() = Unit

    override fun onActionModeDestroyed() = Unit

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCompareGroupBinding.inflate(layoutInflater, parent, false)
        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups.getOrNull(position) ?: return
        holder.bindView(group, true, true) { itemView, _ ->
            val b = ItemCompareGroupBinding.bind(itemView)
            Glide.with(activity).load(File(group.keeper)).centerCrop().into(b.compareGroupImage)
            val isHidden = hidden.isHidden(group.paths)
            val kind = activity.getString(kindLabel(group.kind))
            b.compareGroupKind.text = if (isHidden) activity.getString(R.string.pb_kind_hidden, kind) else kind
            b.compareGroupCount.text = group.paths.size.toString()
            b.compareGroupDate.text = if (group.newestMs > 0) dateFormat.format(java.util.Date(group.newestMs)) else ""
            b.compareGroupReclaim.beVisibleIf(group.reclaimableBytes > 0)
            val reclaim = group.reclaimableBytes.formatSize()
            b.compareGroupReclaim.text = activity.getString(R.string.pb_tile_reclaim, reclaim)
            b.compareGroupImage.alpha = if (isHidden) HIDDEN_ALPHA else 1f

            val isSelected = selectedKeys.contains(keyOf(group))
            b.compareGroupCheck.beVisibleIf(isSelected)
            if (isSelected) {
                b.compareGroupCheck.background?.applyColorFilter(properPrimaryColor)
                b.compareGroupCheck.applyColorFilter(contrastColor)
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = groups.size

    // vybrané skupiny v poradí, v akom sú v mriežke
    private fun getSelectedGroups(): List<CompareGroup> = groups.filter { selectedKeys.contains(keyOf(it)) }

    private fun keyOf(group: CompareGroup): Int = group.key.hashCode()

    private fun kindLabel(kind: CompareKind): Int = when (kind) {
        CompareKind.BURST -> R.string.pb_kind_burst
        CompareKind.EXACT -> R.string.pb_kind_exact
        CompareKind.SIMILAR -> R.string.pb_kind_similar
    }

    private companion object {
        // skrytá skupina (zobrazená cez „Ukázať skryté skupiny“) je stlmená
        const val HIDDEN_ALPHA = 0.4f
    }
}
