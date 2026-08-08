package org.fossify.gallery.adapters

import android.view.Menu
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.PropertiesDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.getFileSignature
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ItemPhotoPathBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.shareMediaPaths
import org.fossify.gallery.extensions.tryDeleteFileDirItem
import org.fossify.gallery.extensions.updateFavorite
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.extensions.getFilenameFromPath
import java.io.File

// Jednotná mriežka fotiek s VÝBEROVÝM REŽIMOM pre všetky obrazovky mimo hlavného albumu
// (Posledné, Osoba, Dokumenty, Špeciálne, Hľadanie, mriežka z mapy...). Dlhé podržanie = výber
// viacerých (aj ťahaním), potom zdieľať / zmazať (kôš) / obľúbené / info / vybrať všetko —
// ROVNAKÉ ovládanie ako v albume priečinka. Nahrádza starý PersonPhotosAdapter bez výberu.
class PhotoPathsAdapter(
    activity: BaseSimpleActivity,
    val paths: MutableList<String>,
    recyclerView: MyRecyclerView,
    val onClick: (String) -> Unit,
    // zavolá sa po zmazaní fotiek, nech si obrazovka obnoví svoje dáta/cache
    val onDeleted: ((List<String>) -> Unit)? = null,
    // doplnková akcia obrazovky vo výberovom menu (napr. „Toto nie je on" pri osobe)
    val extraAction: ExtraAction? = null,
) : MyRecyclerViewAdapter(activity, recyclerView, { any -> onClick(any as String) }),
    RecyclerViewFastScroller.OnPopupTextUpdate {

    class ExtraAction(val titleRes: Int, val callback: (ArrayList<String>) -> Unit)

    // formát dátumu v bubline fast-scrollera — rovnaký ako v albume priečinka (MediaAdapter)
    private val dateFormat = activity.config.dateFormat
    private val timeFormat = activity.getTimeFormat()

    // cache lastModified per cesta, nech sa pri ťahaní posuvníka nechodí opakovane na disk
    private val lastModifiedCache = HashMap<String, Long>()

    init {
        setupDragListener(true)
    }

    // KONTRAKT pre obrazovky: nahradí celý obsah mriežky novým zoznamom ciest (obnova dát)
    fun updateItems(newPaths: List<String>) {
        paths.clear()
        paths.addAll(newPaths)
        lastModifiedCache.clear() // súbory sa mohli zmeniť — dátumy aj signatúry načítať nanovo
        notifyDataSetChanged()
        finishActMode()
    }

    // dátumová bublina fast-scrollera — pri rýchlom ťahaní vidno, v akom dátume sa používateľ nachádza
    override fun onChange(position: Int): String {
        val path = paths.getOrNull(position) ?: return ""
        val lastModified = lastModifiedCache.getOrPut(path) { File(path).lastModified() }
        return if (lastModified > 0) {
            lastModified.formatDate(activity, dateFormat, timeFormat)
        } else {
            ""
        }
    }

    override fun getActionMenuId() = R.menu.cab_photo_paths

    override fun prepareActionMode(menu: Menu) {
        menu.findItem(R.id.cab_extra_action)?.apply {
            isVisible = extraAction != null
            extraAction?.let { setTitle(it.titleRes) }
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_share -> activity.shareMediaPaths(getSelectedPaths())
            R.id.cab_add_to_favorites -> favoriteSelection()
            R.id.cab_properties -> showProperties()
            R.id.cab_delete -> confirmDelete()
            R.id.cab_select_all -> selectAll()
            R.id.cab_extra_action -> {
                val selection = getSelectedPaths()
                finishActMode()
                extraAction?.callback?.invoke(selection)
            }
        }
    }

    override fun getSelectableItemCount() = paths.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = paths.getOrNull(position)?.hashCode()

    override fun getItemKeyPosition(key: Int) = paths.indexOfFirst { it.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPhotoPathBinding.inflate(layoutInflater, parent, false)
        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = paths.getOrNull(position) ?: return
        holder.bindView(path, true, true) { itemView, _ ->
            val itemBinding = ItemPhotoPathBinding.bind(itemView)
            // signatúra podľa lastModified — po úprave fotky sa miniatúra obnoví, nie zo starej cache
            val lastModified = File(path).lastModified()
            lastModifiedCache[path] = lastModified
            Glide.with(activity)
                .load(path)
                .signature(path.getFileSignature(lastModified))
                .centerCrop()
                .into(itemBinding.photoImage)

            // ▶ indikátor pre videá — nech sa v mriežke dajú rozoznať od fotiek
            itemBinding.photoPlayOutline.beVisibleIf(path.isVideoFast())

            val isSelected = selectedKeys.contains(path.hashCode())
            itemBinding.photoCheck.beVisibleIf(isSelected)
            if (isSelected) {
                itemBinding.photoCheck.background?.applyColorFilter(properPrimaryColor)
                itemBinding.photoCheck.applyColorFilter(contrastColor)
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = paths.size

    // vybraté cesty v poradí, v akom sú v mriežke
    private fun getSelectedPaths(): ArrayList<String> =
        ArrayList(paths.filter { selectedKeys.contains(it.hashCode()) })

    private fun favoriteSelection() {
        val selection = getSelectedPaths()
        ensureBackgroundThread {
            selection.forEach { activity.updateFavorite(it, true) }
            activity.runOnUiThread {
                activity.toast(R.string.added_to_favorites_toast)
                finishActMode()
            }
        }
    }

    private fun showProperties() {
        val selection = getSelectedPaths()
        if (selection.size == 1) {
            PropertiesDialog(activity, selection.first(), activity.config.shouldShowHidden)
        } else {
            PropertiesDialog(activity, selection, activity.config.shouldShowHidden)
        }
    }

    private fun confirmDelete() {
        val selection = getSelectedPaths()
        val itemsText = if (selection.size == 1) {
            "\"${selection.first().getFilenameFromPath()}\""
        } else {
            resources.getQuantityString(
                org.fossify.commons.R.plurals.delete_items, selection.size, selection.size,
            )
        }
        val baseString = if (activity.config.useRecycleBin) {
            org.fossify.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            org.fossify.commons.R.string.deletion_confirmation
        }
        ConfirmationDialog(activity, String.format(resources.getString(baseString), itemsText)) {
            deleteSelection(selection)
        }
    }

    private fun deleteSelection(selection: ArrayList<String>) {
        if (activity.config.useRecycleBin) {
            activity.movePathsInRecycleBin(selection) { success ->
                if (success) {
                    removeFromList(selection)
                } else {
                    activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                    finishActMode()
                }
            }
        } else {
            ensureBackgroundThread {
                selection.forEach { path ->
                    activity.tryDeleteFileDirItem(
                        FileDirItem(path, path.getFilenameFromPath()),
                        allowDeleteFolder = false,
                        deleteFromDatabase = true,
                    )
                }
                activity.runOnUiThread { removeFromList(selection) }
            }
        }
    }

    private fun removeFromList(deleted: List<String>) {
        val positions = deleted
            .mapNotNull { path -> paths.indexOf(path).takeIf { it >= 0 } }
            .sortedDescending()
        positions.forEach { paths.removeAt(it) }
        removeSelectedItems(ArrayList(positions))
        onDeleted?.invoke(deleted)
    }
}
