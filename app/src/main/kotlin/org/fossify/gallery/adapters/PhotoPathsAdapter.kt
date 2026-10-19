package org.fossify.gallery.adapters

import android.os.Looper
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
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.getFileSignature
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.handleDeletePasswordProtection
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.rescanPaths
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ItemPhotoPathBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.fixDateTaken
import org.fossify.gallery.extensions.getFavoritePaths
import org.fossify.gallery.extensions.handleMediaManagementPrompt
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.openEditor
import org.fossify.gallery.extensions.openPath
import org.fossify.gallery.extensions.rescanFolderMedia
import org.fossify.gallery.extensions.setAs
import org.fossify.gallery.extensions.shareMediaPaths
import org.fossify.gallery.extensions.tryCopyMoveFilesTo
import org.fossify.gallery.extensions.tryDeleteFileDirItem
import org.fossify.gallery.extensions.updateFavorite
import org.fossify.gallery.extensions.updateFavoritePaths
import java.io.File

// Jednotná mriežka fotiek s VÝBEROVÝM REŽIMOM pre všetky obrazovky mimo hlavného albumu
// (Posledné, Osoba, Dokumenty, Špeciálne, Hľadanie, mriežka z mapy...). Dlhé podržanie = výber
// viacerých (aj ťahaním), potom zdieľať / zmazať (kôš) / obľúbené (pridať aj odobrať) / kopírovať /
// presunúť / info / vybrať všetko (pri jednej fotke aj otvoriť v, upraviť, nastaviť ako) —
// ROVNAKÉ ovládanie ako v albume priečinka. Nahrádza starý PersonPhotosAdapter bez výberu.
class PhotoPathsAdapter(
    activity: BaseSimpleActivity,
    val paths: MutableList<String>,
    recyclerView: MyRecyclerView,
    val onClick: (String) -> Unit,
    // zavolá sa po zmazaní (alebo presune) fotiek, nech si obrazovka obnoví svoje dáta/cache
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

    // cesty obľúbených fotiek — podľa nich výber ponúka „Pridať do obľúbených" alebo „Odobrať z obľúbených"
    // (načíta sa na pozadí pri vzniku výberu; mení sa len na hlavnom vlákne)
    private val favoritePaths = HashSet<String>()

    init {
        setupDragListener(true)
    }

    // KONTRAKT pre obrazovky: nahradí celý obsah mriežky novým zoznamom ciest (obnova dát).
    // Keď sa zoznam reálne nezmenil, nerobí NIČ — nezruší výber ani nepreblikne mriežku
    // (rovnaká sémantika ako MediaAdapter.updateMedia). Smie sa volať z ľubovoľného vlákna.
    fun updateItems(newPaths: List<String>) {
        val snapshot = ArrayList(newPaths) // kópia — volajúci smie odovzdať aj zoznam, ktorý adaptér drží
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { updateItems(snapshot) }
            return
        }

        if (snapshot == paths) {
            return
        }

        paths.clear()
        paths.addAll(snapshot)
        lastModifiedCache.clear() // súbory sa mohli zmeniť — dátumy aj signatúry načítať nanovo
        notifyDataSetChanged()
        finishActMode()
    }

    // obrazovky, ktoré sa obnovujú priebežne (skenovanie), môžu počas výberu obnovu odložiť
    fun hasSelection() = selectedKeys.isNotEmpty()

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
        val selection = getSelectedPaths()
        val isOneImage = selection.size == 1 && !selection.first().isVideoFast()
        menu.apply {
            findItem(R.id.cab_extra_action)?.apply {
                isVisible = extraAction != null
                extraAction?.let { setTitle(it.titleRes) }
            }

            // ako v albume: ponúkne sa len to, čo má vo výbere zmysel
            findItem(R.id.cab_add_to_favorites)?.isVisible = selection.any { it !in favoritePaths }
            findItem(R.id.cab_remove_from_favorites)?.isVisible = selection.any { it in favoritePaths }
            findItem(R.id.cab_open_with)?.isVisible = selection.size == 1
            findItem(R.id.cab_edit)?.isVisible = isOneImage
            findItem(R.id.cab_set_as)?.isVisible = isOneImage
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_share -> activity.shareMediaPaths(getSelectedPaths())
            R.id.cab_add_to_favorites -> favoriteSelection(true)
            R.id.cab_remove_from_favorites -> favoriteSelection(false)
            R.id.cab_properties -> showProperties()
            R.id.cab_delete -> checkDeleteConfirmation()
            R.id.cab_select_all -> selectAll()
            R.id.cab_copy_to -> checkMediaManagementAndCopyMove(true)
            R.id.cab_move_to -> activity.handleDeletePasswordProtection {
                checkMediaManagementAndCopyMove(false)
            }

            R.id.cab_open_with -> getSelectedPaths().firstOrNull()?.let { activity.openPath(it, true) }
            R.id.cab_edit -> getSelectedPaths().firstOrNull()?.let { activity.openEditor(it) }
            R.id.cab_set_as -> getSelectedPaths().firstOrNull()?.let { activity.setAs(it) }
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

    override fun onActionModeCreated() {
        loadFavorites()
    }

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

    // obľúbené sa čítajú z databázy, preto na pozadí; po načítaní sa menu výberu prekreslí
    private fun loadFavorites() {
        ensureBackgroundThread {
            val loaded = try {
                activity.getFavoritePaths()
            } catch (e: Throwable) {
                ArrayList<String>()
            }

            activity.runOnUiThread {
                if (activity.isDestroyed) {
                    return@runOnUiThread
                }

                favoritePaths.clear()
                favoritePaths.addAll(loaded)
                actMode?.invalidate()
            }
        }
    }

    private fun favoriteSelection(add: Boolean) {
        val selection = getSelectedPaths()
        ensureBackgroundThread {
            try {
                selection.forEach { activity.updateFavorite(it, add) }
            } catch (e: Throwable) {
                activity.runOnUiThread { activity.toast(org.fossify.commons.R.string.unknown_error_occurred) }
                return@ensureBackgroundThread
            }

            activity.runOnUiThread {
                if (add) {
                    favoritePaths.addAll(selection)
                } else {
                    favoritePaths.removeAll(selection.toSet())
                }

                activity.toast(if (add) R.string.added_to_favorites_toast else R.string.c4_removed_from_favorites_toast)
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

    // kopírovanie a presun — rovnaký postup ako v albume (MediaAdapter.copyMoveTo)
    private fun checkMediaManagementAndCopyMove(isCopyOperation: Boolean) {
        activity.handleMediaManagementPrompt {
            copyMoveTo(isCopyOperation)
        }
    }

    private fun copyMoveTo(isCopyOperation: Boolean) {
        val selection = getSelectedPaths()
        val recycleBinPath = activity.recycleBinPath
        val fileDirItems = ArrayList(
            selection
                .filter { isCopyOperation || !it.startsWith(recycleBinPath) }
                .map { FileDirItem(it, it.getFilenameFromPath()) }
        )

        if (!isCopyOperation && selection.any { it.startsWith(recycleBinPath) }) {
            activity.toast(org.fossify.commons.R.string.moving_recycle_bin_items_disabled)
        }

        if (fileDirItems.isEmpty()) {
            return
        }

        activity.tryCopyMoveFilesTo(fileDirItems, isCopyOperation) { destinationPath ->
            activity.config.tempFolderPath = ""
            activity.applicationContext.rescanFolderMedia(destinationPath)
            activity.applicationContext.rescanFolderMedia(fileDirItems.first().getParentPath())

            val newPaths = ArrayList(fileDirItems.map { "$destinationPath/${it.name}" })
            activity.rescanPaths(newPaths) {
                activity.fixDateTaken(newPaths, false)
            }

            activity.runOnUiThread {
                if (isCopyOperation) {
                    finishActMode()
                } else {
                    activity.updateFavoritePaths(fileDirItems, destinationPath)
                    // presunuté fotky už na pôvodných cestách nie sú — z mriežky odídu rovnako ako zmazané
                    removeFromList(fileDirItems.map { it.path })
                }
            }
        }
    }

    private fun checkDeleteConfirmation() {
        if (activity.config.isDeletePasswordProtectionOn) {
            activity.handleDeletePasswordProtection {
                confirmDelete()
            }
        } else {
            confirmDelete()
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
            // POZOR: callback z movePathsInRecycleBin beží na vlákne na pozadí — s mriežkou a výberovým
            // režimom (UI) sa smie pracovať až cez runOnUiThread. Kôš je len KÓPIA súboru, originály
            // sa mažú zvlášť (rovnako ako v albume a v prehliadači).
            activity.movePathsInRecycleBin(selection) { success ->
                if (!success) {
                    activity.runOnUiThread {
                        activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                        finishActMode()
                    }
                    return@movePathsInRecycleBin
                }

                val items = ArrayList(selection.map { FileDirItem(it, it.getFilenameFromPath()) })
                activity.deleteFiles(items) { deleted ->
                    val gone = selection.filter { !File(it).exists() }
                    activity.runOnUiThread {
                        if (gone.isNotEmpty()) {
                            removeFromList(gone)
                        }

                        if (!deleted) {
                            activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                            finishActMode()
                        }
                    }
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

    // odoberie cesty z mriežky; volať len na hlavnom vlákne
    private fun removeFromList(deleted: List<String>) {
        val deletedSet = deleted.toHashSet()
        val positions = ArrayList<Int>()
        paths.forEachIndexed { index, path ->
            if (path in deletedSet) {
                positions.add(index)
            }
        }

        // od konca, nech sa pozície pri odoberaní neposúvajú
        val descending = ArrayList(positions.sortedDescending())
        descending.forEach { paths.removeAt(it) }
        removeSelectedItems(descending)
        onDeleted?.invoke(deleted)
    }
}
