package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.provider.MediaStore.Images
import android.provider.MediaStore.Video
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.dialogs.CreateNewFolderDialog
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkWhatsNew
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.ensureBasePadding
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFileCount
import org.fossify.commons.extensions.getFilePublicUri
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getLatestMediaByDateId
import org.fossify.commons.extensions.getLatestMediaId
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getBottomNavigationBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.getProperSize
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getStorageDirectories
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.handleHiddenFolderPasswordProtection
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.hasAllPermissions
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isExternalStorageManager
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isGone
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.isMediaFile
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.toast
import org.fossify.gallery.helpers.AppUpdater
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.updatePaddingWithBase
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.FAVORITES
import org.fossify.commons.helpers.PERMISSION_READ_STORAGE
import org.fossify.commons.helpers.SORT_BY_DATE_MODIFIED
import org.fossify.commons.helpers.SORT_BY_DATE_TAKEN
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.commons.helpers.SORT_USE_NUMERIC_VALUE
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.Release
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MyRecyclerView
import org.fossify.commons.views.MyTextView
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.adapters.DirectoryAdapter
import org.fossify.gallery.adapters.MainPagesAdapter
import org.fossify.gallery.adapters.PhotoPathsAdapter
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.databinding.ActivityMainBinding
import org.fossify.gallery.dialogs.ChangeSortingDialog
import org.fossify.gallery.dialogs.ChangeViewTypeDialog
import org.fossify.gallery.dialogs.FilterMediaDialog
import org.fossify.gallery.dialogs.GrantAllFilesDialog
import org.fossify.gallery.extensions.addTempFolderIfNeeded
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.createDirectoryFromMedia
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.getCachedDirectories
import org.fossify.gallery.extensions.getCachedMedia
import org.fossify.gallery.extensions.getDirectorySortingValue
import org.fossify.gallery.extensions.getDirsToShow
import org.fossify.gallery.extensions.getDistinctPath
import org.fossify.gallery.extensions.getFavoritePaths
import org.fossify.gallery.extensions.getNoMediaFoldersSync
import org.fossify.gallery.extensions.getOTGFolderChildrenNames
import org.fossify.gallery.extensions.getSortedDirectories
import org.fossify.gallery.extensions.handleExcludedFolderPasswordProtection
import org.fossify.gallery.extensions.handleMediaManagementPrompt
import org.fossify.gallery.extensions.isDownloadsFolder
import org.fossify.gallery.extensions.launchAbout
import org.fossify.gallery.extensions.launchCamera
import org.fossify.gallery.extensions.launchSettings
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.movePinnedDirectoriesToFront
import org.fossify.gallery.extensions.openRecycleBin
import org.fossify.gallery.extensions.removeInvalidDBDirectories
import org.fossify.gallery.extensions.storeDirectoryItems
import org.fossify.gallery.extensions.tryDeleteFileDirItem
import org.fossify.gallery.extensions.updateDBDirectory
import org.fossify.gallery.extensions.updateWidgets
import org.fossify.gallery.helpers.DIRECTORY
import org.fossify.gallery.helpers.FolderSort
import org.fossify.gallery.helpers.GET_ANY_INTENT
import org.fossify.gallery.helpers.GET_IMAGE_INTENT
import org.fossify.gallery.helpers.GET_VIDEO_INTENT
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_DAILY
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_MONTHLY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_DAILY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_MONTHLY
import org.fossify.gallery.helpers.GROUP_DESCENDING
import org.fossify.gallery.helpers.GridZoom
import org.fossify.gallery.helpers.HomeStats
import org.fossify.gallery.helpers.IndexStatus
import org.fossify.gallery.helpers.LOCATION_INTERNAL
import org.fossify.gallery.helpers.MAX_COLUMN_COUNT
import org.fossify.gallery.helpers.MONTH_MILLISECONDS
import org.fossify.gallery.helpers.MediaFetcher
import org.fossify.gallery.helpers.Memories
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.PICKED_PATHS
import org.fossify.gallery.helpers.PathTransfer
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.SET_WALLPAPER_INTENT
import org.fossify.gallery.helpers.SMART_ALBUM_PATHS
import org.fossify.gallery.helpers.getSmartAlbumName
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SHOW_TEMP_HIDDEN_DURATION
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION
import org.fossify.gallery.helpers.TYPE_GIFS
import org.fossify.gallery.helpers.TYPE_IMAGES
import org.fossify.gallery.helpers.TYPE_RAWS
import org.fossify.gallery.helpers.TYPE_SVGS
import org.fossify.gallery.helpers.TYPE_VIDEOS
import org.fossify.gallery.helpers.getDefaultFileFilter
import org.fossify.gallery.helpers.getPermissionToRequest
import org.fossify.gallery.helpers.getPermissionsToRequest
import org.fossify.gallery.interfaces.DirectoryOperationsListener
import org.fossify.gallery.jobs.NewPhotoFetcher
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.Medium
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

class MainActivity : SimpleActivity(), DirectoryOperationsListener {
    override var isSearchBarEnabled = true
    
    companion object {
        private const val PICK_MEDIA = 2
        private const val PICK_WALLPAPER = 3
        private const val LAST_MEDIA_CHECK_PERIOD = 3000L
        private const val REQ_PC_DELETE = 7021

        // koľko priečinkov ukázať na Domove (zvyšok je na stránke Priečinky)
        private const val HOME_FOLDERS_COUNT = 6

        // „Na upratanie" prechádza celý pHash index — neprepočítavame ho častejšie ako raz za pol minúty
        private const val HOME_CLEANUP_MIN_INTERVAL = 30_000L

        // Spomienky prechádzajú celý MediaStore — výsledok držíme 30 minút
        private const val HOME_MEMORIES_MIN_INTERVAL = 30 * 60_000L

        // cache stránky Posledné: po tomto čase mimo stránky sa pri návrate prenačíta
        private const val RECENT_CACHE_MAX_AGE = 2 * 60_000L

        // interval obnovy riadku stavu na Domove počas behu indexovania
        private const val HOME_STATUS_TICK = 2_000L

        // aby dialóg „označené z PC" neotravoval pri každom onResume, pýtame sa len keď sa počet zmení
        @Volatile
        private var lastPcMarkedPrompt = -1
    }

    private var pendingPcDelete: List<String> = emptyList()

    private var mIsPickImageIntent = false
    private var mIsPickVideoIntent = false
    private var mIsGetImageContentIntent = false
    private var mIsGetVideoContentIntent = false
    private var mIsGetAnyContentIntent = false
    private var mIsSetWallpaperIntent = false
    private var mAllowPickingMultiple = false
    private var mIsThirdPartyIntent = false

    // chyba pri nastavovaní novej navigácie — zobrazí sa používateľovi, nech vieme čo opraviť
    private var mStartupError: String? = null
    private var mIsGettingDirs = false
    private var mLoadedInitialPhotos = false
    private var mShouldStopFetching = false
    private var mWasDefaultFolderChecked = false
    private var mWasMediaManagementPromptShown = false
    private var mLatestMediaId = 0L
    private var mLatestMediaDateId = 0L

    // used at "Group direct subfolders" for navigation
    private var mCurrentPathPrefix = ""

    // used at "Group direct subfolders" for navigating Up with the back button
    private var mOpenedSubfolders = arrayListOf("")

    private var mDateFormat = ""
    private var mTimeFormat = ""
    private var mLastMediaHandler = Handler()
    private var mTempShowHiddenHandler = Handler()
    private var mZoomListener: MyRecyclerView.MyZoomListener? = null
    private var mLastMediaFetcher: MediaFetcher? = null
    private var mDirs = ArrayList<Directory>()
    private var mDirsIgnoringSearch = ArrayList<Directory>()

    // stránka Posledné — najnovšie fotky a videá knižnice (cache sa invaliduje pri zmene knižnice)
    private var mRecentPaths = ArrayList<String>()
    private var mRecentLoading = false
    private var mRecentLoadedAt = 0L

    // ticker živého stavu spracovania na Domove (beží len keď je aktivita resumed)
    private val mHomeStatusHandler = Handler()

    // počty na kartách Preskúmať sa neprepočítavajú častejšie ako HOME_CLEANUP_MIN_INTERVAL
    private var mExploreCountsAt = 0L

    // spodné odsadenie mriežok o výšku spodnej lišty sa nastavuje len raz
    private var mBottomNavPaddingApplied = false

    // stránka Domov — pamäť posledného (ťažkého) výpočtu „Na upratanie"
    private var mHomeCleanupComputedAt = 0L
    private var mHomeCleanupRunning = false

    // posledné vypočítané štatistiky „Na upratanie" — zdieľa ich karta Podobné na Preskúmať
    private var mLastHomeStats: HomeStats.Result? = null

    // stránka Domov — cache spomienok (Memories.build číta celý MediaStore, drží sa 30 minút);
    // počet spomienok z nej zdieľa aj karta Spomienky na Preskúmať
    private var mHomeMemories: List<Memories.Memory>? = null
    private var mHomeMemoriesComputedAt = 0L
    private var mHomeMemoriesRunning = false

    private var mStoredAnimateGifs = true
    private var mStoredCropThumbnails = true
    private var mStoredScrollHorizontally = true
    private var mStoredTextColor = 0
    private var mStoredPrimaryColor = 0
    private var mStoredStyleString = ""
    private val binding by viewBinding(ActivityMainBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)

        if (savedInstanceState == null) {
            config.temporarilyShowHidden = false
            config.temporarilyShowExcluded = false
            config.tempSkipDeleteConfirmation = false
            config.tempSkipRecycleBin = false
            removeTempFolder()
            checkRecycleBinItems()
            startNewPhotoFetcher()
        }

        mIsPickImageIntent = isPickImageIntent(intent)
        mIsPickVideoIntent = isPickVideoIntent(intent)
        mIsGetImageContentIntent = isGetImageContentIntent(intent)
        mIsGetVideoContentIntent = isGetVideoContentIntent(intent)
        mIsGetAnyContentIntent = isGetAnyContentIntent(intent)
        mIsSetWallpaperIntent = isSetWallpaperIntent(intent)
        mAllowPickingMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        mIsThirdPartyIntent = mIsPickImageIntent
                || mIsPickVideoIntent
                || mIsGetImageContentIntent
                || mIsGetVideoContentIntent
                || mIsGetAnyContentIntent
                || mIsSetWallpaperIntent

        // POISTKA: nová navigácia (stránky, Domov, Preskúmať) nesmie za žiadnych okolností zabrániť
        // spusteniu appky. Keď v nej niečo zlyhá, zapíšeme chybu a pokračujeme so samotnými
        // priečinkami — galéria musí zostať použiteľná.
        try {
            setupPages()
            setupExplorePage()
            setupHomePage()
        } catch (e: Throwable) {
            mStartupError = e.javaClass.simpleName + (e.message?.let { ": " + it.take(200) } ?: "")
            android.util.Log.e("GaleriaPlus", "zlyhalo nastavenie stránok", e)
            try {
                binding.mainBottomNav.beGone()
                binding.mainPager.setCurrentItem(MainPagesAdapter.PAGE_FOLDERS, false)
            } catch (ignored: Throwable) {
            }
        }

        setupOptionsMenu()
        refreshMenuItems()

        setupEdgeToEdge(
            padBottomImeAndSystem = listOf(binding.directoriesGrid, binding.pageRecent.recentGrid)
        )
        try {
            setupBottomNavPadding()
        } catch (e: Throwable) {
            android.util.Log.e("GaleriaPlus", "zlyhalo odsadenie pod lištou", e)
        }

        binding.directoriesRefreshLayout.setOnRefreshListener { getDirectories() }
        storeStateVariables()
        checkWhatsNewDialog()
        if (!mIsThirdPartyIntent) {
            checkForAppUpdate()
        }
        setupLatestMediaId()

        if (!config.wereFavoritesPinned) {
            config.addPinnedFolders(hashSetOf(FAVORITES))
            config.wereFavoritesPinned = true
        }

        if (!config.wasRecycleBinPinned) {
            config.addPinnedFolders(hashSetOf(RECYCLE_BIN))
            config.wasRecycleBinPinned = true
            config.saveFolderGrouping(SHOW_ALL, GROUP_BY_DATE_TAKEN_DAILY or GROUP_DESCENDING)
        }

        if (!config.wasSVGShowingHandled) {
            config.wasSVGShowingHandled = true
            if (config.filterMedia and TYPE_SVGS == 0) {
                config.filterMedia += TYPE_SVGS
            }
        }

        if (!config.wasSortingByNumericValueAdded) {
            config.wasSortingByNumericValueAdded = true
            config.sorting = config.sorting or SORT_USE_NUMERIC_VALUE
        }

        updateWidgets()
        registerFileUpdateListener()

        // automatické indexovanie tvárí + polohy na pozadí (raz za spustenie), ak je zapnuté
        if (getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE).getBoolean("auto_index", true)) {
            org.fossify.gallery.services.IndexingService.startAutoOnce(this)
            // jednorazovo vyčisti nezmyselný OCR „text" zo starších verzií (bez re-skenovania)
            org.fossify.gallery.faces.OcrCleanup.runIfNeeded(this)
        }

        // ak nová navigácia zlyhala, povedz to nahlas — nech vieme, čo presne opraviť
        mStartupError?.let { err ->
            mStartupError = null
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.startup_error_title)
                .setMessage(getString(R.string.startup_error_message, err))
                .setPositiveButton(org.fossify.commons.R.string.copy) { _, _ ->
                    try {
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("chyba", err))
                    } catch (ignored: Throwable) {
                    }
                }
                .setNegativeButton(org.fossify.commons.R.string.ok, null)
                .show()
        }

        binding.directoriesSwitchSearching.setOnClickListener {
            launchSearchActivity()
        }

        // potiahnutie nadol na Posledných = zneplatnenie cache a prenačítanie mriežky
        binding.pageRecent.recentRefreshLayout.setOnRefreshListener { invalidateRecent() }

        // just request the permission, tryLoadGallery will then trigger in onResume;
        // uvítanie sa ponúkne až PO udelení oprávnení — inak by ho prekryl systémový dialóg
        // a spustené indexovanie by bežalo bez prístupu k médiám
        handleMediaPermissions { maybeShowIntro() }
    }

    // stránkovač hlavnej obrazovky: Domov · Priečinky · Posledné · Preskúmať
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupPages() {
        val pages = listOf<android.view.View>(
            binding.pageHome.root,
            binding.directoriesHolder,
            binding.pageRecent.root,
            binding.pageExplore.root,
        )
        binding.mainPager.adapter = MainPagesAdapter(pages)
        binding.mainPager.offscreenPageLimit = 3

        binding.mainBottomNav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_home -> MainPagesAdapter.PAGE_HOME
                R.id.nav_recent -> MainPagesAdapter.PAGE_RECENT
                R.id.nav_explore -> MainPagesAdapter.PAGE_EXPLORE
                else -> MainPagesAdapter.PAGE_FOLDERS
            }
            binding.mainPager.setCurrentItem(target, true)
            true
        }

        // opätovné ťuknutie na už aktívnu záložku = návrat na začiatok stránky (štandard M3)
        binding.mainBottomNav.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.nav_recent -> binding.pageRecent.recentGrid.smoothScrollToPosition(0)
                R.id.nav_folders -> binding.directoriesGrid.smoothScrollToPosition(0)
                R.id.nav_home -> binding.pageHome.root.smoothScrollTo(0, 0)
                R.id.nav_explore -> binding.pageExplore.root.smoothScrollTo(0, 0)
            }
        }

        binding.mainPager.addOnPageChangeListener(object : androidx.viewpager.widget.ViewPager.OnPageChangeListener {
            override fun onPageScrolled(position: Int, offset: Float, offsetPx: Int) {}

            override fun onPageSelected(position: Int) {
                val id = when (position) {
                    MainPagesAdapter.PAGE_HOME -> R.id.nav_home
                    MainPagesAdapter.PAGE_RECENT -> R.id.nav_recent
                    MainPagesAdapter.PAGE_EXPLORE -> R.id.nav_explore
                    else -> R.id.nav_folders
                }
                if (binding.mainBottomNav.selectedItemId != id) {
                    binding.mainBottomNav.selectedItemId = id
                }

                // Posledné sa načítajú až pri prvom zobrazení stránky
                if (position == MainPagesAdapter.PAGE_RECENT) {
                    loadRecentPage()
                }

                // pri návrate na Domov premietneme aktuálny stav (výpočet má vlastnú brzdu)
                if (position == MainPagesAdapter.PAGE_HOME) {
                    refreshHome()
                }

                // na Preskúmať doplníme na karty aktuálne počty (lacné COUNT dopyty na pozadí)
                if (position == MainPagesAdapter.PAGE_EXPLORE) {
                    refreshExploreCounts()
                }
            }

            override fun onPageScrollStateChanged(state: Int) {}
        })

        // pri výbere súboru pre inú aplikáciu ostávajú len priečinky (bez lišty a prepínania)
        if (mIsThirdPartyIntent) {
            binding.mainBottomNav.beGone()
            binding.mainPager.setCurrentItem(MainPagesAdapter.PAGE_FOLDERS, false)
            binding.mainPager.setOnTouchListener { _, _ -> true } // žiadne swipovanie
        } else {
            // obrazovka po spustení podľa Nastavení (predvolene Priečinky = rovnaký pohľad ako doteraz)
            val start = getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE)
                .getInt("start_page", MainPagesAdapter.PAGE_FOLDERS)
                .coerceIn(0, MainPagesAdapter.PAGE_EXPLORE)
            binding.mainPager.setCurrentItem(start, false)
            binding.mainBottomNav.selectedItemId = when (start) {
                MainPagesAdapter.PAGE_HOME -> R.id.nav_home
                MainPagesAdapter.PAGE_RECENT -> R.id.nav_recent
                MainPagesAdapter.PAGE_EXPLORE -> R.id.nav_explore
                else -> R.id.nav_folders
            }
            // stránky sa načítavajú lenivo pri prepnutí — pri štarte na nich to treba spustiť ručne
            when (start) {
                MainPagesAdapter.PAGE_RECENT -> loadRecentPage()
                MainPagesAdapter.PAGE_HOME -> refreshHome(force = true)
                else -> {}
            }
        }
    }

    // Uvítanie pri prvom spustení: appka povie, čo vie, a ponúkne spustiť spracovanie knižnice.
    // Volá sa až tam, kde sú isto udelené oprávnenia, aby dialóg nevyskočil nad systémovým.
    private fun maybeShowIntro() {
        if (mIsThirdPartyIntent) return
        val prefs = getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("intro_shown", false)) return
        prefs.edit().putBoolean("intro_shown", true).apply()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.intro_title)
            .setMessage(R.string.intro_message)
            .setPositiveButton(R.string.intro_start) { _, _ ->
                org.fossify.gallery.services.IndexingService.start(
                    this, org.fossify.gallery.services.IndexingService.TASK_ALL,
                )
            }
            .setNegativeButton(R.string.intro_later, null)
            .show()
    }

    // Stránka Preskúmať: rozcestník na ostatné obrazovky (rovnaké karty ako v ExploreActivity).
    private fun setupExplorePage() {
        val page = binding.pageExplore
        page.exploreMemories.setOnClickListener {
            startActivity(Intent(this, MemoriesActivity::class.java))
        }
        page.explorePeople.setOnClickListener {
            startActivity(Intent(this, PeopleActivity::class.java))
        }
        page.explorePlaces.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        page.exploreDocs.setOnClickListener {
            startActivity(Intent(this, DocsActivity::class.java))
        }
        page.exploreSpecial.setOnClickListener {
            startActivity(Intent(this, SpecialActivity::class.java))
        }
        page.exploreSimilar.setOnClickListener {
            startActivity(Intent(this, CompareListActivity::class.java))
        }
        page.exploreSearch.setOnClickListener {
            // otvor hlavné hľadanie galérie
            startActivity(Intent(this, SearchActivity::class.java))
        }

        refreshExploreCounts()
    }

    // Počty na kartách Preskúmať (osoby / fotky s textom / fotky s polohou) — lacné COUNT
    // dopyty na pozadí; brzda cez mExploreCountsAt, nech sa nerátajú pri každom prepnutí.
    private fun refreshExploreCounts() {
        if (mIsThirdPartyIntent) {
            return
        }

        val now = System.currentTimeMillis()
        if (mExploreCountsAt > 0 && now - mExploreCountsAt < HOME_CLEANUP_MIN_INTERVAL) {
            return
        }

        mExploreCountsAt = now
        // hotové výsledky z Domova (počítajú sa inde) — čítame ich ešte na UI vlákne
        val stats = mLastHomeStats
        val memoriesCount = mHomeMemories?.size ?: -1
        ensureBackgroundThread {
            val people = try {
                org.fossify.gallery.faces.PeopleDatabase.getInstance(this).PeopleDao()
                    .getPersons().count { !it.name.isNullOrBlank() }
            } catch (ignored: Throwable) {
                0
            }
            // DocClassifier.loadAll je drahý — počet fotiek s textom z OCR databázy stačí
            val docs = try {
                org.fossify.gallery.faces.OcrDatabase.getInstance(this).OcrDao().countWithText()
            } catch (ignored: Throwable) {
                0
            }
            val places = try {
                org.fossify.gallery.faces.GeoDatabase.getInstance(this).GeoDao().countGeotagged()
            } catch (ignored: Throwable) {
                0
            }
            // počet špeciálnych fotiek zapisuje SpecialActivity po skene (-1 = ešte neznáme)
            val special = try {
                getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE)
                    .getInt("special_count", -1)
            } catch (ignored: Throwable) {
                -1
            }

            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }

                try {
                    if (people > 0) {
                        exploreCardSubtitle(binding.pageExplore.explorePeople)?.text =
                            resources.getQuantityString(R.plurals.a1_explore_people_count, people, people)
                    }
                    if (docs > 0) {
                        exploreCardSubtitle(binding.pageExplore.exploreDocs)?.text =
                            resources.getQuantityString(R.plurals.a1_explore_docs_count, docs, docs)
                    }
                    if (places > 0) {
                        exploreCardSubtitle(binding.pageExplore.explorePlaces)?.text =
                            resources.getQuantityString(R.plurals.a1_explore_places_count, places, places)
                    }
                    // Podobné/duplikáty — z posledného výpočtu „Na upratanie"; bez neho text nemeníme
                    if (stats != null && stats.hasPhashIndex) {
                        exploreCardSubtitle(binding.pageExplore.exploreSimilar)?.text = listOf(
                            resources.getQuantityString(
                                R.plurals.b1_explore_duplicates_count, stats.duplicates, stats.duplicates,
                            ),
                            resources.getQuantityString(
                                R.plurals.b1_explore_bursts_count, stats.bursts, stats.bursts,
                            ),
                        ).joinToString(" · ")
                    }
                    // Spomienky — počet z cache Domova; kým sa nespočítali, podtitulok ostáva
                    if (memoriesCount >= 0) {
                        exploreCardSubtitle(binding.pageExplore.exploreMemories)?.text =
                            resources.getQuantityString(
                                R.plurals.b1_explore_memories_count, memoriesCount, memoriesCount,
                            )
                    }
                    // Špeciálne fotky — počet uložený SpecialActivity po poslednom skene
                    if (special >= 0) {
                        exploreCardSubtitle(binding.pageExplore.exploreSpecial)?.text =
                            resources.getQuantityString(R.plurals.b1_explore_special_count, special, special)
                    }
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    // Podtitulky kariet Preskúmať nemajú v layoute vlastné id — nájdeme ich podľa štruktúry
    // karty (karta -> riadok -> stĺpec textov -> druhý text). Bezpečné casty, pri zmene
    // layoutu vráti null a počet sa jednoducho nezobrazí.
    private fun exploreCardSubtitle(card: ViewGroup): MyTextView? {
        val row = card.getChildAt(0) as? ViewGroup ?: return null
        val texts = row.getChildAt(1) as? ViewGroup ?: return null
        return texts.getChildAt(1) as? MyTextView
    }

    // Stránka Domov: hľadanie, karta „Na upratanie", štyri skratky, stav spracovania a priečinky.
    private fun setupHomePage() {
        if (mIsThirdPartyIntent) {
            return // pri výbere súboru pre inú appku sa Domov vôbec nezobrazuje
        }

        val page = binding.pageHome
        page.homeSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        page.homeTilePeople.setOnClickListener {
            startActivity(Intent(this, PeopleActivity::class.java))
        }
        page.homeTileDocs.setOnClickListener {
            startActivity(Intent(this, DocsActivity::class.java))
        }
        page.homeTilePlaces.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        page.homeTileMemories.setOnClickListener {
            startActivity(Intent(this, MemoriesActivity::class.java))
        }
        page.homeTileCamera.setOnClickListener {
            launchCamera()
        }
        page.homeTileFavorites.setOnClickListener {
            // Obľúbené sú virtuálny priečinok — otvoria sa ako bežný album
            itemClicked(FAVORITES)
        }
        page.homeStatus.setOnClickListener {
            org.fossify.gallery.services.IndexingService.start(
                this, org.fossify.gallery.services.IndexingService.TASK_ALL,
            )
            refreshHomeStatus()
        }
        page.homeFoldersHeader.setOnClickListener {
            FolderSort.showDialog(this) { refreshHomeFolders() }
        }

        // prvé naplnenie Domova rieši setupPages (štart na Domove) alebo onPageSelected —
        // druhé volanie tu by len duplicitne spúšťalo DB dopyty pri každom štarte
    }

    // Farby nových stránok (Domov, Preskúmať) a spodnej lišty podľa témy Fossify — MyTextView si
    // samy farbu nenastavia a vo svetlej téme by text kariet ostal svetlý/nečitateľný. Volá sa
    // z onResume, takže sa premietne aj zmena témy v Nastaveniach po návrate späť.
    private fun updatePageColors() {
        if (mIsThirdPartyIntent) {
            return
        }

        try {
            updateTextColors(binding.pageHome.root)
            updateTextColors(binding.pageExplore.root)

            val cardColor = getBottomNavigationBackgroundColor()
            listOf(
                binding.pageHome.homeCleanup,
                binding.pageHome.homeMemories,
                binding.pageExplore.exploreMemories,
                binding.pageExplore.explorePeople,
                binding.pageExplore.explorePlaces,
                binding.pageExplore.exploreDocs,
                binding.pageExplore.exploreSpecial,
                binding.pageExplore.exploreSimilar,
                binding.pageExplore.exploreSearch,
            ).forEach { it.setCardBackgroundColor(cardColor) }

            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            val itemColors = android.content.res.ColorStateList(
                states, intArrayOf(getProperPrimaryColor(), getProperTextColor()),
            )
            binding.mainBottomNav.setBackgroundColor(cardColor)
            binding.mainBottomNav.itemIconTintList = itemColors
            binding.mainBottomNav.itemTextColor = itemColors
            // aktívna „pilulka" M3 za zvolenou ikonou by inak ostala tmavá z Material3 témy —
            // priesvitný akcent (~40/255) sedí so zvyškom prefarbenia
            binding.mainBottomNav.itemActiveIndicatorColor =
                android.content.res.ColorStateList.valueOf(getProperPrimaryColor().adjustAlpha(0.16f))
        } catch (e: Throwable) {
            android.util.Log.e("GaleriaPlus", "zlyhalo prefarbenie stránok", e)
        }
    }

    // `force` obíde pamäť posledného výpočtu (pri prvom zobrazení Domova ju chceme naplniť hneď)
    private fun refreshHome(force: Boolean = false) {
        if (mIsThirdPartyIntent) {
            return
        }

        refreshHomeCleanup(force)
        refreshHomeMemories(force)
        refreshHomeStatus()
        refreshHomeFolders()
    }

    // „Na upratanie" — HomeStats.compute() prechádza celý pHash index a siaha na disk, preto beží
    // výhradne na pozadí a neopakuje sa častejšie ako HOME_CLEANUP_MIN_INTERVAL.
    private fun refreshHomeCleanup(force: Boolean) {
        if (mHomeCleanupRunning) {
            return
        }

        val now = System.currentTimeMillis()
        if (!force && mHomeCleanupComputedAt > 0 && now - mHomeCleanupComputedAt < HOME_CLEANUP_MIN_INTERVAL) {
            return
        }

        mHomeCleanupRunning = true
        ensureBackgroundThread {
            val stats = HomeStats.compute(this)
            runOnUiThread {
                mHomeCleanupRunning = false
                mHomeCleanupComputedAt = System.currentTimeMillis()
                // výsledok si odložíme aj pre kartu Podobné na stránke Preskúmať
                mLastHomeStats = stats
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }

                binding.pageHome.homeCleanupSummary.text = when {
                    !stats.hasPhashIndex -> getString(R.string.home_cleanup_no_index)
                    stats.duplicates == 0 -> getString(R.string.home_cleanup_none)
                    else -> getString(
                        R.string.home_cleanup_summary,
                        stats.bursts, stats.duplicates, stats.wastedBytes.formatSize(),
                    )
                }

                binding.pageHome.homeCleanup.setOnClickListener {
                    if (stats.hasPhashIndex) {
                        startActivity(Intent(this, CompareListActivity::class.java))
                    } else {
                        // bez indexu niet čo porovnávať — najprv ho dáme spočítať
                        org.fossify.gallery.services.IndexingService.start(
                            this, org.fossify.gallery.services.IndexingService.TASK_PHASH,
                        )
                    }
                }
            }
        }
    }

    // Karta „Spomienky" na Domove — Memories.build() prechádza celý MediaStore (a geo.db),
    // preto beží výhradne na pozadí a výsledok sa drží HOME_MEMORIES_MIN_INTERVAL (rovnaký
    // vzor ako mHomeCleanupComputedAt). Karta sa ukáže, len keď sa niečo našlo.
    private fun refreshHomeMemories(force: Boolean = false) {
        if (mHomeMemoriesRunning) {
            return
        }

        val now = System.currentTimeMillis()
        if (!force && mHomeMemoriesComputedAt > 0 && now - mHomeMemoriesComputedAt < HOME_MEMORIES_MIN_INTERVAL) {
            return
        }

        mHomeMemoriesRunning = true
        ensureBackgroundThread {
            val memories = try {
                Memories.build(this)
            } catch (ignored: Throwable) {
                emptyList()
            }

            runOnUiThread {
                mHomeMemoriesRunning = false
                mHomeMemoriesComputedAt = System.currentTimeMillis()
                mHomeMemories = memories
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }

                val page = binding.pageHome
                if (memories.isEmpty()) {
                    page.homeMemories.beGone()
                } else {
                    // prvá (najvyššie zoradená) spomienka — podtitulok už obsahuje počet fotiek
                    val first = memories.first()
                    page.homeMemoriesSummary.text =
                        getString(R.string.b1_home_memories_summary, first.title, first.subtitle)
                    page.homeMemories.setOnClickListener {
                        startActivity(Intent(this, MemoriesActivity::class.java))
                    }
                    page.homeMemories.beVisible()
                }
            }
        }
    }

    // rovnaký údaj ako prehľad spracovania v Nastaveniach, len v jednom riadku
    private fun refreshHomeStatus() {
        ensureBackgroundThread {
            val total = IndexStatus.photoCount(this)
            val items = IndexStatus.all(this, total)
            val percent = IndexStatus.overallPercent(items)
            val live = org.fossify.gallery.services.IndexingService.liveProgress
            val error = org.fossify.gallery.services.IndexingService.lastError
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }

                binding.pageHome.homeStatus.text = when {
                    live.isNotEmpty() -> live
                    error != null -> getString(R.string.index_overview_error, error)
                    percent >= 100 -> getString(R.string.index_overview_done)
                    else -> getString(R.string.index_overview_summary, percent)
                }
            }
        }
    }

    // krátky zoznam priečinkov; hlavička zároveň prepína zoradenie (abeceda / posledná zmena)
    private fun refreshHomeFolders() {
        if (mIsThirdPartyIntent || isDestroyed || isFinishing) {
            return
        }

        val page = binding.pageHome
        val mode = FolderSort.mode(this)
        page.homeFoldersHeader.text = getString(
            R.string.home_folders_header,
            getString(FolderSort.labelRes(mode)),
        )

        page.homeFoldersList.removeAllViews()
        val dirs = mDirs.toList()
        val sorted = if (mode == FolderSort.BY_NAME) {
            dirs.sortedBy { it.name.lowercase() }
        } else {
            dirs.sortedByDescending { it.modified }
        }

        sorted.take(HOME_FOLDERS_COUNT).forEach { dir ->
            val item = layoutInflater.inflate(
                R.layout.item_home_folder, page.homeFoldersList, false,
            ) as MyTextView
            item.text = "${dir.name}  ·  ${dir.mediaCnt}"
            // itemClicked(path) je existujúca metóda MainActivity, ktorá otvorí priečinok
            item.setOnClickListener { itemClicked(dir.path) }
            page.homeFoldersList.addView(item)
        }

        val all = layoutInflater.inflate(
            R.layout.item_home_folder, page.homeFoldersList, false,
        ) as MyTextView
        all.text = getString(R.string.home_folders_all)
        all.setOnClickListener {
            binding.mainPager.setCurrentItem(MainPagesAdapter.PAGE_FOLDERS, true)
        }
        page.homeFoldersList.addView(all)

        // riadky sa nafukujú dynamicky — musia dostať farbu témy hneď (nie až pri ďalšom onResume)
        updateTextColors(page.homeFoldersList)
    }

    // Spodná lišta leží nad stránkovačom a prekrývala by posledný riadok mriežok, preto im
    // doplníme spodné odsadenie o jej výšku. Odsadenie sa pripočíta do „základu" (commons od
    // neho pri zmene systémových okrajov prepočítava odsadenie), takže ostane platné aj po
    // zobrazení klávesnice alebo otočení obrazovky.
    private fun setupBottomNavPadding() {
        if (mIsThirdPartyIntent) {
            return // lišta je skrytá, mriežku nič neprekrýva
        }

        val nav = binding.mainBottomNav
        if (nav.isLaidOut && nav.height > 0) {
            applyBottomNavPadding(nav)
        } else {
            // výšku lišty poznáme až po jej rozmiestnení
            nav.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                ) {
                    if (v.height <= 0) {
                        return
                    }

                    v.removeOnLayoutChangeListener(this)
                    applyBottomNavPadding(nav)
                }
            })
        }
    }

    private fun applyBottomNavPadding(nav: View) {
        if (mBottomNavPaddingApplied || isDestroyed || isFinishing) {
            return
        }

        // výška lišty bez systémového pruhu (ten si lišta pridáva ako vlastné odsadenie)
        val extra = nav.height - nav.paddingBottom
        if (extra <= 0) {
            return
        }

        mBottomNavPaddingApplied = true
        val insetBottom = ViewCompat.getRootWindowInsets(nav)
            ?.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars())
            ?.bottom ?: 0

        // mriežky aj rolovacie stránky (Domov, Preskúmať) musia mať pod obsahom miesto na lištu
        listOf<ViewGroup>(
            binding.directoriesGrid,
            binding.pageRecent.recentGrid,
            binding.pageHome.root,
            binding.pageExplore.root,
        ).forEach { view ->
            view.clipToPadding = false
            val base = view.ensureBasePadding()
            base[3] = base[3] + extra
            view.updatePaddingWithBase(bottom = insetBottom)
        }
    }

    // Stránka Posledné: chronologická mriežka najnovších fotiek A VIDEÍ naprieč celou knižnicou,
    // zoradená podľa dátumu nasnímania (fallback dátum zmeny). Rešpektuje vylúčené, skryté
    // (.nomedia) aj heslom zamknuté priečinky — tie sa v mriežke vôbec neobjavia.
    private fun loadRecentPage() {
        if (mRecentPaths.isNotEmpty() || mRecentLoading) {
            binding.pageRecent.recentRefreshLayout.isRefreshing = false
            return
        }

        mRecentLoading = true
        setupRecentGrid()
        ensureBackgroundThread {
            val list = queryRecentPaths()

            runOnUiThread {
                mRecentLoading = false
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }

                binding.pageRecent.recentRefreshLayout.isRefreshing = false
                mRecentPaths = list
                mRecentLoadedAt = System.currentTimeMillis()
                binding.pageRecent.recentPlaceholder.beVisibleIf(list.isEmpty())
                binding.pageRecent.recentGrid.adapter = PhotoPathsAdapter(
                    activity = this@MainActivity,
                    paths = mRecentPaths,
                    recyclerView = binding.pageRecent.recentGrid,
                    onClick = { path -> openRecentPhoto(path) },
                    onDeleted = {
                        // po zmazaní sa cache zneplatní (kôš/DB mohli zmeniť aj ďalšie údaje);
                        // post() — nech sa adaptér nevymieňa uprostred odoberacej animácie
                        binding.pageRecent.recentGrid.post { invalidateRecent() }
                        refreshHome(force = true)
                    },
                )
            }
        }
    }

    // mriežka Posledných: počet stĺpcov z prefs + pinch-zoom (rovnaký vzor ako mriežky osôb);
    // app:spanCount=3 v XML ostáva ako poistka proti pádu fast-scrollera pri štarte
    private fun setupRecentGrid() {
        val prefs = getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE)
        val columns = prefs.getInt("recent_columns", 3).coerceIn(GridZoom.MIN, GridZoom.MAX)
        val grid = binding.pageRecent.recentGrid
        val lm = grid.layoutManager as? GridLayoutManager
            ?: GridLayoutManager(this, columns).also { grid.layoutManager = it }
        lm.spanCount = columns
        GridZoom.setup(grid, lm, prefs, "recent_columns")
        binding.pageRecent.recentFastscroller.updateColors(getProperPrimaryColor())
    }

    // MediaStore dopyt pre Posledné — fotky aj videá; zoradenie rátame v Kotline
    // (COALESCE v sort order MediaStore nemusí zvládnuť). Beží na pozadí.
    private fun queryRecentPaths(): ArrayList<String> {
        val rows = ArrayList<Pair<String, Long>>()
        try {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    Images.Media.DATE_TAKEN,
                    MediaStore.Files.FileColumns.DATE_MODIFIED,
                ),
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                ),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val takenCol = c.getColumnIndexOrThrow(Images.Media.DATE_TAKEN)
                val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val path = c.getString(dataCol) ?: continue
                    val taken = c.getLong(takenCol)
                    val modified = c.getLong(modifiedCol) * 1000
                    rows.add(path to if (taken > 0) taken else modified)
                }
            }
        } catch (ignored: Throwable) {
        }

        rows.sortByDescending { it.second }

        // vylúčené / skryté / zamknuté priečinky sa na Posledných nesmú objaviť
        val excluded = if (config.temporarilyShowExcluded) emptySet() else config.excludedFolders
        val showHidden = config.shouldShowHidden
        val noMediaFolders = if (showHidden) {
            emptyList<String>()
        } else {
            try {
                getNoMediaFoldersSync()
            } catch (ignored: Throwable) {
                emptyList<String>()
            }
        }

        // rozhodnutie per PRIEČINOK sa cachuje — v jednom priečinku bývajú stovky fotiek
        val allowedParents = HashMap<String, Boolean>()
        val list = ArrayList<String>()
        for ((path, _) in rows) {
            // pri veľkých knižniciach stačí najnovších 3000 položiek
            if (list.size >= 3000) {
                break
            }

            val parent = path.getParentPath()
            val allowed = allowedParents.getOrPut(parent) {
                excluded.none { parent.startsWith(it) }
                        && (showHidden || (!parent.contains("/.") && noMediaFolders.none { parent.startsWith(it) }))
                        && !config.isFolderProtected(parent)
            }
            if (allowed) {
                list.add(path)
            }
        }
        return list
    }

    // Zneplatnenie cache Posledných — volá sa pri zmene knižnice (nové/zmazané médiá).
    // Keď je stránka práve na obrazovke, mriežka sa hneď prenačíta.
    private fun invalidateRecent() {
        mRecentPaths = ArrayList()
        mRecentLoadedAt = 0L
        if (!isDestroyed && !isFinishing
            && binding.mainPager.currentItem == MainPagesAdapter.PAGE_RECENT
        ) {
            loadRecentPage()
        }
    }

    // listovanie v prehliadači ostane uzavreté v zozname Posledné
    private fun openRecentPhoto(path: String) {
        PathTransfer.forViewer = mRecentPaths
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, path)
            putExtra(SKIP_AUTHENTICATION, true)
            putExtra(SHOW_ALL, false)
            startActivity(this)
        }
    }

    private fun handleMediaPermissions(callback: (() -> Unit)? = null) {
        requestMediaPermissions(enableRationale = true) {
            callback?.invoke()
            if (isRPlus() && !mWasMediaManagementPromptShown) {
                mWasMediaManagementPromptShown = true
                handleMediaManagementPrompt { }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mTempShowHiddenHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        updateMenuColors()
        updatePageColors()
        checkPcMarkedDeletions()
        config.isThirdPartyIntent = false
        mDateFormat = config.dateFormat
        mTimeFormat = getTimeFormat()

        refreshMenuItems()

        if (mStoredAnimateGifs != config.animateGifs) {
            getRecyclerAdapter()?.updateAnimateGifs(config.animateGifs)
        }

        if (mStoredCropThumbnails != config.cropThumbnails) {
            getRecyclerAdapter()?.updateCropThumbnails(config.cropThumbnails)
        }

        if (mStoredScrollHorizontally != config.scrollHorizontally) {
            mLoadedInitialPhotos = false
            binding.directoriesGrid.adapter = null
            getDirectories()
        }

        if (mStoredTextColor != getProperTextColor()) {
            getRecyclerAdapter()?.updateTextColor(getProperTextColor())
        }

        val primaryColor = getProperPrimaryColor()
        if (mStoredPrimaryColor != primaryColor) {
            getRecyclerAdapter()?.updatePrimaryColor()
        }

        val styleString =
            "${config.folderStyle}${config.showFolderMediaCount}${config.limitFolderTitle}"
        if (mStoredStyleString != styleString) {
            setupAdapter(mDirs, forceRecreate = true)
        }

        binding.directoriesFastscroller.updateColors(primaryColor)
        binding.directoriesRefreshLayout.isEnabled = config.enablePullToRefresh
        binding.pageRecent.recentRefreshLayout.isEnabled = config.enablePullToRefresh
        getRecyclerAdapter()?.apply {
            dateFormat = config.dateFormat
            timeFormat = getTimeFormat()
        }

        binding.directoriesEmptyPlaceholder.setTextColor(getProperTextColor())
        // prázdny stav Posledných si farbu sám nenastaví — vo svetlej téme by bol nečitateľný
        binding.pageRecent.recentPlaceholder.setTextColor(getProperTextColor())
        binding.directoriesEmptyPlaceholder2.setTextColor(primaryColor)
        binding.directoriesSwitchSearching.setTextColor(primaryColor)
        binding.directoriesSwitchSearching.underlineText()
        binding.directoriesEmptyPlaceholder2.bringToFront()

        if (!binding.mainMenu.isSearchOpen) {
            refreshMenuItems()
            tryLoadGallery()
        }

        if (config.searchAllFilesByDefault) {
            binding.mainMenu.updateHintText(getString(org.fossify.commons.R.string.search_files))
        } else {
            binding.mainMenu.updateHintText(getString(org.fossify.commons.R.string.search_folders))
        }

        // čísla na Domove obnovíme len keď je Domov naozaj na obrazovke (napr. návrat z porovnávača)
        if (binding.mainPager.currentItem == MainPagesAdapter.PAGE_HOME) {
            refreshHome()
        }

        // Posledné: po ~2 minútach mimo appky sa cache zneplatní (nové fotky z fotoaparátu a pod.)
        if (binding.mainPager.currentItem == MainPagesAdapter.PAGE_RECENT
            && mRecentPaths.isNotEmpty()
            && System.currentTimeMillis() - mRecentLoadedAt > RECENT_CACHE_MAX_AGE
        ) {
            invalidateRecent()
        }

        // živý stav spracovania na Domove — ticker beží, kým je aktivita resumed
        scheduleHomeStatusTick()

        // automatický dosken nových fotiek do indexov, ak sú staré (implementácia v IndexingService)
        try {
            org.fossify.gallery.services.IndexingService.autoScanIfStale(this)
        } catch (e: Throwable) {
        }
    }

    // Kým IndexingService beží a je vidno Domov, riadok stavu sa obnovuje sám každé ~2 s.
    // Samotný tik bez behu indexovania je lacný (dve porovnania) — DB dopyty sa spúšťajú
    // len keď naozaj beží spracovanie. Ruší sa v onPause.
    private fun scheduleHomeStatusTick() {
        mHomeStatusHandler.removeCallbacksAndMessages(null)
        if (mIsThirdPartyIntent) {
            return
        }

        mHomeStatusHandler.postDelayed({
            if (isDestroyed || isFinishing) {
                return@postDelayed
            }

            if (binding.mainPager.currentItem == MainPagesAdapter.PAGE_HOME
                && org.fossify.gallery.services.IndexingService.liveProgress.isNotEmpty()
            ) {
                refreshHomeStatus()
            }
            scheduleHomeStatusTick()
        }, HOME_STATUS_TICK)
    }

    override fun onPause() {
        super.onPause()
        binding.directoriesRefreshLayout.isRefreshing = false
        binding.pageRecent.recentRefreshLayout.isRefreshing = false
        mIsGettingDirs = false
        storeStateVariables()
        mLastMediaHandler.removeCallbacksAndMessages(null)
        mHomeStatusHandler.removeCallbacksAndMessages(null)
    }

    override fun onStop() {
        super.onStop()

        if (config.temporarilyShowHidden || config.tempSkipDeleteConfirmation || config.temporarilyShowExcluded) {
            mTempShowHiddenHandler.postDelayed({
                config.temporarilyShowHidden = false
                config.temporarilyShowExcluded = false
                config.tempSkipDeleteConfirmation = false
                config.tempSkipRecycleBin = false
            }, SHOW_TEMP_HIDDEN_DURATION)
        } else {
            mTempShowHiddenHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            config.temporarilyShowHidden = false
            config.temporarilyShowExcluded = false
            config.tempSkipDeleteConfirmation = false
            config.tempSkipRecycleBin = false
            mTempShowHiddenHandler.removeCallbacksAndMessages(null)
            removeTempFolder()
            unregisterFileUpdateListener()

            if (!config.showAll) {
                mLastMediaFetcher?.shouldStop = true
                GalleryDatabase.destroyInstance()
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        // štandard spodnej navigácie: Späť na inej stránke sa najprv vráti na štartovú stránku,
        // až ďalšie Späť appku zavrie (namiesto okamžitého zavretia z Domova/Posledné/Preskúmať)
        if (!mIsThirdPartyIntent && !binding.mainMenu.isSearchOpen) {
            val start = getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE)
                .getInt("start_page", MainPagesAdapter.PAGE_FOLDERS)
                .coerceIn(0, MainPagesAdapter.PAGE_EXPLORE)
            if (binding.mainPager.currentItem != start) {
                binding.mainPager.setCurrentItem(start, true)
                return true
            }
        }

        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else if (config.groupDirectSubfolders) {
            if (mCurrentPathPrefix.isEmpty()) {
                appLockManager.lock()
                false
            } else {
                mOpenedSubfolders.removeAt(mOpenedSubfolders.lastIndex)
                mCurrentPathPrefix = mOpenedSubfolders.last()
                setupAdapter(mDirs)
                true
            }
        } else {
            appLockManager.lock()
            false
        }
    }

    // ---------- PC sync: fotky označené z webovej galérie na zmazanie ----------
    private fun checkPcMarkedDeletions() {
        ensureBackgroundThread {
            val prefs = org.fossify.gallery.sync.SyncStore.prefs(this)
            val all = org.fossify.gallery.sync.SyncStore.markedPaths(prefs)
            // cesty, ktoré už neexistujú, tíško uprac
            val gone = all.filter { !java.io.File(it).exists() }
            if (gone.isNotEmpty()) org.fossify.gallery.sync.SyncStore.removeMarked(prefs, gone)
            val marked = all - gone.toSet()
            if (marked.isEmpty() || marked.size == lastPcMarkedPrompt) return@ensureBackgroundThread
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                lastPcMarkedPrompt = marked.size
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.pc_marked_title)
                    .setMessage(getString(R.string.pc_marked_message, marked.size))
                    .setPositiveButton(R.string.pc_marked_delete) { _, _ -> deletePcMarked(marked.toList()) }
                    .setNegativeButton(R.string.pc_marked_later, null)
                    .setNeutralButton(R.string.pc_marked_clear) { _, _ ->
                        org.fossify.gallery.sync.SyncStore.clearMarked(prefs)
                        lastPcMarkedPrompt = -1
                    }
                    .show()
            }
        }
    }

    private fun deletePcMarked(paths: List<String>) {
        ensureBackgroundThread {
            val uris = paths.mapNotNull { pcContentUriForPath(it) }
            runOnUiThread {
                if (isDestroyed || isFinishing || uris.isEmpty()) return@runOnUiThread
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try {
                        val pi = MediaStore.createDeleteRequest(contentResolver, uris)
                        pendingPcDelete = paths
                        startIntentSenderForResult(pi.intentSender, REQ_PC_DELETE, null, 0, 0, 0)
                    } catch (e: Throwable) {
                        toast(org.fossify.commons.R.string.unknown_error_occurred)
                    }
                } else {
                    var deleted = 0
                    uris.forEach {
                        try {
                            if (contentResolver.delete(it, null, null) > 0) deleted++
                        } catch (ignored: Throwable) {
                        }
                    }
                    if (deleted > 0) {
                        org.fossify.gallery.sync.SyncStore.removeMarked(org.fossify.gallery.sync.SyncStore.prefs(this), paths)
                        toast(getString(R.string.pc_marked_deleted, deleted))
                        lastPcMarkedPrompt = -1
                        mLoadedInitialPhotos = false
                        getDirectories()
                    }
                }
            }
        }
    }

    private fun pcContentUriForPath(path: String): Uri? {
        for (base in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            try {
                contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null)?.use { c ->
                    if (c.moveToFirst()) {
                        return android.content.ContentUris.withAppendedId(base, c.getLong(0))
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == REQ_PC_DELETE) {
            if (resultCode == RESULT_OK && pendingPcDelete.isNotEmpty()) {
                val prefs = org.fossify.gallery.sync.SyncStore.prefs(this)
                org.fossify.gallery.sync.SyncStore.removeMarked(prefs, pendingPcDelete)
                toast(getString(R.string.pc_marked_deleted, pendingPcDelete.size))
                lastPcMarkedPrompt = -1
                mLoadedInitialPhotos = false
                getDirectories()
            }
            pendingPcDelete = emptyList()
            super.onActivityResult(requestCode, resultCode, resultData)
            return
        }
        if (resultCode == RESULT_OK) {
            if (requestCode == PICK_MEDIA && resultData != null) {
                val resultIntent = Intent()
                var resultUri: Uri? = null
                if (mIsThirdPartyIntent) {
                    when {
                        intent.extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true
                                && intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 -> {
                            resultUri = fillExtraOutput(resultData)
                        }

                        resultData.extras?.containsKey(PICKED_PATHS) == true -> {
                            fillPickedPaths(resultData, resultIntent)
                        }

                        else -> fillIntentPath(resultData, resultIntent)
                    }
                }

                if (resultUri != null) {
                    resultIntent.data = resultUri
                    resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                setResult(RESULT_OK, resultIntent)
                finish()
            } else if (requestCode == PICK_WALLPAPER) {
                setResult(RESULT_OK)
                finish()
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    private fun refreshMenuItems() {
        if (!mIsThirdPartyIntent) {
            binding.mainMenu.requireToolbar().menu.apply {
                findItem(R.id.column_count).isVisible = config.viewTypeFolders == VIEW_TYPE_GRID
                findItem(R.id.set_as_default_folder).isVisible = !config.defaultFolder.isEmpty()
                findItem(R.id.open_recycle_bin).isVisible =
                    config.useRecycleBin && !config.showRecycleBinAtFolders
                findItem(R.id.more_apps_from_us).isVisible =
                    !resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)
            }
        }

        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.temporarily_show_hidden).isVisible = !config.shouldShowHidden
            findItem(R.id.stop_showing_hidden).isVisible =
                (!isRPlus() || isExternalStorageManager()) && config.temporarilyShowHidden

            findItem(R.id.temporarily_show_excluded).isVisible = !config.temporarilyShowExcluded
            findItem(R.id.stop_showing_excluded).isVisible = config.temporarilyShowExcluded
        }
    }

    private fun setupOptionsMenu() {
        val menuId = if (mIsThirdPartyIntent) {
            R.menu.menu_main_intent
        } else {
            R.menu.menu_main
        }

        binding.mainMenu.requireToolbar().inflateMenu(menuId)
        binding.mainMenu.toggleHideOnScroll(!config.scrollHorizontally)
        binding.mainMenu.setupMenu()

        binding.mainMenu.onSearchOpenListener = {
            // horné hľadanie filtruje mriežku Priečinkov — prepni na ňu, nech používateľ
            // vidí, čo vlastne filtruje (na Domove/Posledných hľadanie „nič nerobilo")
            if (!mIsThirdPartyIntent
                && binding.mainPager.currentItem != MainPagesAdapter.PAGE_FOLDERS
            ) {
                binding.mainPager.setCurrentItem(MainPagesAdapter.PAGE_FOLDERS, false)
            }
            if (config.searchAllFilesByDefault) {
                launchSearchActivity()
            }
        }

        binding.mainMenu.onSearchTextChangedListener = { text ->
            setupAdapter(mDirsIgnoringSearch, text)
            binding.directoriesRefreshLayout.isEnabled =
                text.isEmpty() && config.enablePullToRefresh
            binding.directoriesSwitchSearching.beVisibleIf(text.isNotEmpty())
        }

        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.sort -> showSortingDialog()
                R.id.filter -> showFilterMediaDialog()
                R.id.open_camera -> launchCamera()
                R.id.show_all -> showAllMedia()
                R.id.change_view_type -> changeViewType()
                R.id.temporarily_show_hidden -> tryToggleTemporarilyShowHidden()
                R.id.stop_showing_hidden -> tryToggleTemporarilyShowHidden()
                R.id.temporarily_show_excluded -> tryToggleTemporarilyShowExcluded()
                R.id.stop_showing_excluded -> tryToggleTemporarilyShowExcluded()
                R.id.create_new_folder -> createNewFolder()
                R.id.open_recycle_bin -> openRecycleBin()
                R.id.column_count -> changeColumnCount()
                R.id.set_as_default_folder -> setAsDefaultFolder()
                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun getRecyclerAdapter() = binding.directoriesGrid.adapter as? DirectoryAdapter

    private fun storeStateVariables() {
        mStoredTextColor = getProperTextColor()
        mStoredPrimaryColor = getProperPrimaryColor()
        config.apply {
            mStoredAnimateGifs = animateGifs
            mStoredCropThumbnails = cropThumbnails
            mStoredScrollHorizontally = scrollHorizontally
            mStoredStyleString = "$folderStyle$showFolderMediaCount$limitFolderTitle"
        }
    }

    private fun startNewPhotoFetcher() {
        val photoFetcher = NewPhotoFetcher()
        if (!photoFetcher.isScheduled(applicationContext)) {
            photoFetcher.scheduleJob(applicationContext)
        }
    }

    private fun removeTempFolder() {
        if (config.tempFolderPath.isNotEmpty()) {
            val newFolder = File(config.tempFolderPath)
            if (getDoesFilePathExist(newFolder.absolutePath) && newFolder.isDirectory) {
                if (
                    newFolder.getProperSize(true) == 0L
                    && newFolder.getFileCount(true) == 0
                    && newFolder.list()?.isEmpty() == true
                ) {
                    toast(
                        String.format(
                            getString(org.fossify.commons.R.string.deleting_folder),
                            config.tempFolderPath
                        ), Toast.LENGTH_LONG
                    )
                    tryDeleteFileDirItem(newFolder.toFileDirItem(applicationContext), true, true)
                }
            }
            config.tempFolderPath = ""
        }
    }

    private fun checkOTGPath() {
        ensureBackgroundThread {
            if (!config.wasOTGHandled && hasPermission(getPermissionToRequest()) && hasOTGConnected() && config.OTGPath.isEmpty()) {
                getStorageDirectories().firstOrNull {
                    it.trimEnd('/') != internalStoragePath
                            && it.trimEnd('/') != sdCardPath
                }?.apply {
                    config.wasOTGHandled = true
                    val otgPath = trimEnd('/')
                    config.OTGPath = otgPath
                    config.addIncludedFolder(otgPath)
                }
            }
        }
    }

    private fun checkDefaultSpamFolders() {
        if (!config.spamFoldersChecked) {
            val spamFolders = arrayListOf(
                "/storage/emulated/0/Android/data/com.facebook.orca/files/stickers"
            )

            val OTGPath = config.OTGPath
            spamFolders.forEach {
                if (getDoesFilePathExist(it, OTGPath)) {
                    config.addExcludedFolder(it)
                }
            }
            config.spamFoldersChecked = true
        }
    }

    private fun tryLoadGallery() {
        // avoid calling anything right after granting the permission, it will be called from onResume()
        val wasMissingPermission =
            config.appRunCount == 1 && !hasAllPermissions(getPermissionsToRequest())
        handleMediaPermissions {
            if (wasMissingPermission) {
                return@handleMediaPermissions
            }

            if (!mWasDefaultFolderChecked) {
                openDefaultFolder()
                mWasDefaultFolderChecked = true
            }

            checkOTGPath()
            checkDefaultSpamFolders()

            if (config.showAll) {
                showAllMedia()
            } else {
                getDirectories()
            }

            setupLayoutManager()
        }
    }

    private fun getDirectories() {
        if (mIsGettingDirs) {
            return
        }

        mShouldStopFetching = true
        mIsGettingDirs = true
        val getImages = mIsPickImageIntent || mIsGetImageContentIntent
        val getVideos = mIsPickVideoIntent || mIsGetVideoContentIntent

        getCachedDirectories(getVideos && !getImages, getImages && !getVideos) {
            gotDirectories(addTempFolderIfNeeded(it))
        }
    }

    private fun launchSearchActivity() {
        hideKeyboard()
        Intent(this, SearchActivity::class.java).apply {
            startActivity(this)
        }

        binding.mainMenu.postDelayed({
            binding.mainMenu.closeSearch()
        }, 500)
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this, true, false) {
            binding.directoriesGrid.adapter = null
            if (config.directorySorting and SORT_BY_DATE_MODIFIED != 0 || config.directorySorting and SORT_BY_DATE_TAKEN != 0) {
                getDirectories()
            } else {
                ensureBackgroundThread {
                    gotDirectories(getCurrentlyDisplayedDirs())
                }
            }

            getRecyclerAdapter()?.directorySorting = config.directorySorting
        }
    }

    private fun showFilterMediaDialog() {
        FilterMediaDialog(this) {
            mShouldStopFetching = true
            binding.directoriesRefreshLayout.isRefreshing = true
            binding.directoriesGrid.adapter = null
            getDirectories()
        }
    }

    private fun showAllMedia() {
        config.showAll = true
        Intent(this, MediaActivity::class.java).apply {
            putExtra(DIRECTORY, "")

            if (mIsThirdPartyIntent) {
                handleMediaIntent(this)
            } else {
                hideKeyboard()
                startActivity(this)
                finish()
            }
        }
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this, true) {
            refreshMenuItems()
            setupLayoutManager()
            binding.directoriesGrid.adapter = null
            setupAdapter(getRecyclerAdapter()?.dirs ?: mDirs)
        }
    }

    private fun tryToggleTemporarilyShowHidden() {
        if (config.temporarilyShowHidden) {
            toggleTemporarilyShowHidden(false)
        } else {
            if (isRPlus() && !isExternalStorageManager()) {
                GrantAllFilesDialog(this)
            } else {
                handleHiddenFolderPasswordProtection {
                    toggleTemporarilyShowHidden(true)
                }
            }
        }
    }

    private fun toggleTemporarilyShowHidden(show: Boolean) {
        mLoadedInitialPhotos = false
        config.temporarilyShowHidden = show
        binding.directoriesGrid.adapter = null
        getDirectories()
        refreshMenuItems()
    }

    private fun tryToggleTemporarilyShowExcluded() {
        if (config.temporarilyShowExcluded) {
            toggleTemporarilyShowExcluded(false)
        } else {
            handleExcludedFolderPasswordProtection {
                toggleTemporarilyShowExcluded(true)
            }
        }
    }

    private fun toggleTemporarilyShowExcluded(show: Boolean) {
        mLoadedInitialPhotos = false
        config.temporarilyShowExcluded = show
        binding.directoriesGrid.adapter = null
        getDirectories()
        refreshMenuItems()
    }

    override fun deleteFolders(folders: ArrayList<File>) {
        val fileDirItems = folders
            .asSequence()
            .filter { it.isDirectory }
            .map { FileDirItem(it.absolutePath, it.name, true) }
            .toMutableList() as ArrayList<FileDirItem>

        when {
            fileDirItems.isEmpty() -> return
            fileDirItems.size == 1 -> {
                try {
                    toast(
                        String.format(
                            getString(org.fossify.commons.R.string.deleting_folder),
                            fileDirItems.first().name
                        )
                    )
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }

            else -> {
                val baseString = if (config.useRecycleBin && !config.tempSkipRecycleBin) {
                    org.fossify.commons.R.plurals.moving_items_into_bin
                } else {
                    org.fossify.commons.R.plurals.delete_items
                }

                toast(
                    msg = resources.getQuantityString(
                        baseString, fileDirItems.size, fileDirItems.size
                    )
                )
            }
        }

        val itemsToDelete = ArrayList<FileDirItem>()
        val filter = config.filterMedia
        val showHidden = config.shouldShowHidden
        fileDirItems.filter { it.isDirectory }.forEach {
            val files = File(it.path).listFiles()
            files?.filter {
                it.absolutePath.isMediaFile()
                        && (showHidden || !it.name.startsWith('.'))
                        && ((it.isImageFast() && filter and TYPE_IMAGES != 0)
                        || (it.isVideoFast() && filter and TYPE_VIDEOS != 0)
                        || (it.isGif() && filter and TYPE_GIFS != 0)
                        || (it.isRawFast() && filter and TYPE_RAWS != 0)
                        || (it.isSvg() && filter and TYPE_SVGS != 0))
            }?.mapTo(itemsToDelete) { it.toFileDirItem(applicationContext) }
        }

        if (config.useRecycleBin && !config.tempSkipRecycleBin) {
            val pathsToDelete = ArrayList<String>()
            itemsToDelete.mapTo(pathsToDelete) { it.path }

            movePathsInRecycleBin(pathsToDelete) {
                if (it) {
                    deleteFilteredFileDirItems(itemsToDelete, folders)
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
            }
        } else {
            deleteFilteredFileDirItems(itemsToDelete, folders)
        }
    }

    private fun deleteFilteredFileDirItems(
        fileDirItems: ArrayList<FileDirItem>,
        folders: ArrayList<File>
    ) {
        val OTGPath = config.OTGPath
        deleteFiles(fileDirItems) {
            runOnUiThread {
                refreshItems()
            }

            ensureBackgroundThread {
                folders.filter { !getDoesFilePathExist(it.absolutePath, OTGPath) }.forEach {
                    directoryDB.deleteDirPath(it.absolutePath)
                }

                if (config.deleteEmptyFolders) {
                    folders.filter {
                        !it.absolutePath.isDownloadsFolder()
                                && it.isDirectory
                                && it.toFileDirItem(this).getProperFileCount(this, true) == 0
                    }
                        .forEach {
                            tryDeleteFileDirItem(it.toFileDirItem(this), true, true)
                        }
                }
            }
        }
    }

    private fun setupLayoutManager() {
        if (config.viewTypeFolders == VIEW_TYPE_GRID) {
            setupGridLayoutManager()
        } else {
            setupListLayoutManager()
        }

        (binding.directoriesRefreshLayout.layoutParams as RelativeLayout.LayoutParams)
            .addRule(RelativeLayout.BELOW, R.id.directories_switch_searching)
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager
        if (config.scrollHorizontally) {
            layoutManager.orientation = RecyclerView.HORIZONTAL
            binding.directoriesRefreshLayout.layoutParams =
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
        } else {
            layoutManager.orientation = RecyclerView.VERTICAL
            binding.directoriesRefreshLayout.layoutParams =
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
        }

        layoutManager.spanCount = config.dirColumnCnt
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        layoutManager.orientation = RecyclerView.VERTICAL
        binding.directoriesRefreshLayout.layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        mZoomListener = null
    }

    private fun initZoomListener() {
        if (config.viewTypeFolders == VIEW_TYPE_GRID) {
            val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager
            mZoomListener = object : MyRecyclerView.MyZoomListener {
                override fun zoomIn() {
                    if (layoutManager.spanCount > 1) {
                        reduceColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }

                override fun zoomOut() {
                    if (layoutManager.spanCount < MAX_COLUMN_COUNT) {
                        increaseColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }
            }
        } else {
            mZoomListener = null
        }
    }

    private fun createNewFolder() {
        FilePickerDialog(this, internalStoragePath, false, config.shouldShowHidden, false, true) {
            CreateNewFolderDialog(this, it) {
                config.tempFolderPath = it
                ensureBackgroundThread {
                    gotDirectories(addTempFolderIfNeeded(getCurrentlyDisplayedDirs()))
                }
            }
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..MAX_COLUMN_COUNT) {
            items.add(
                RadioItem(
                    id = i,
                    title = resources.getQuantityString(
                        org.fossify.commons.R.plurals.column_counts, i, i
                    )
                )
            )
        }

        val currentColumnCount =
            (binding.directoriesGrid.layoutManager as MyGridLayoutManager).spanCount
        RadioGroupDialog(this, items, currentColumnCount) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.dirColumnCnt = newColumnCount
                columnCountChanged()
            }
        }
    }

    private fun increaseColumnCount() {
        config.dirColumnCnt += 1
        columnCountChanged()
    }

    private fun reduceColumnCount() {
        config.dirColumnCnt -= 1
        columnCountChanged()
    }

    private fun columnCountChanged() {
        (binding.directoriesGrid.layoutManager as MyGridLayoutManager).spanCount =
            config.dirColumnCnt
        refreshMenuItems()
        getRecyclerAdapter()?.apply {
            notifyItemRangeChanged(0, dirs.size)
        }
    }

    private fun isPickImageIntent(intent: Intent): Boolean {
        return isPickIntent(intent) && (hasImageContentData(intent) || isImageType(intent))
    }

    private fun isPickVideoIntent(intent: Intent): Boolean {
        return isPickIntent(intent) && (hasVideoContentData(intent) || isVideoType(intent))
    }

    private fun isPickIntent(intent: Intent): Boolean {
        return intent.action == Intent.ACTION_PICK
    }

    private fun isGetContentIntent(intent: Intent): Boolean {
        return intent.action == Intent.ACTION_GET_CONTENT && intent.type != null
    }

    private fun anyExtraMimeTypeStartingWith(intent: Intent, mimeTypePrefix: String): Boolean {
        return intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
            ?.any { it.startsWith(mimeTypePrefix) } == true
    }

    private fun isGetImageContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent)
                && (intent.type!!.startsWith("image/")
                || intent.type == Images.Media.CONTENT_TYPE
                || anyExtraMimeTypeStartingWith(intent, "image/"))
    }

    private fun isGetVideoContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent)
                && (intent.type!!.startsWith("video/")
                || intent.type == Video.Media.CONTENT_TYPE
                || anyExtraMimeTypeStartingWith(intent, "video/"))
    }

    private fun isGetAnyContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent) && intent.type == "*/*"
    }

    private fun isSetWallpaperIntent(intent: Intent?): Boolean {
        return intent?.action == Intent.ACTION_SET_WALLPAPER
    }

    private fun hasImageContentData(intent: Intent): Boolean {
        return intent.data == Images.Media.EXTERNAL_CONTENT_URI
                || intent.data == Images.Media.INTERNAL_CONTENT_URI
    }

    private fun hasVideoContentData(intent: Intent): Boolean {
        return intent.data == Video.Media.EXTERNAL_CONTENT_URI
                || intent.data == Video.Media.INTERNAL_CONTENT_URI
    }

    private fun isImageType(intent: Intent): Boolean {
        return (intent.type?.startsWith("image/") == true
                || intent.type == Images.Media.CONTENT_TYPE)
    }

    private fun isVideoType(intent: Intent): Boolean {
        return (intent.type?.startsWith("video/") == true
                || intent.type == Video.Media.CONTENT_TYPE)
    }

    private fun fillExtraOutput(resultData: Intent): Uri? {
        val file = File(resultData.data!!.path!!)
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        try {
            val output = intent.extras!!.get(MediaStore.EXTRA_OUTPUT) as Uri
            inputStream = FileInputStream(file)
            outputStream = contentResolver.openOutputStream(output)
            inputStream.copyTo(outputStream!!)
        } catch (e: SecurityException) {
            showErrorToast(e)
        } catch (ignored: FileNotFoundException) {
            return getFilePublicUri(file, BuildConfig.APPLICATION_ID)
        } finally {
            inputStream?.close()
            outputStream?.close()
        }

        return null
    }

    private fun fillPickedPaths(resultData: Intent, resultIntent: Intent) {
        val paths = resultData.extras!!.getStringArrayList(PICKED_PATHS)
        val uris = paths!!
            .map { getFilePublicUri(File(it), BuildConfig.APPLICATION_ID) } as ArrayList
        val clipData = ClipData(
            "Attachment",
            arrayOf("image/*", "video/*"),
            ClipData.Item(uris.removeAt(0))
        )

        uris.forEach {
            clipData.addItem(ClipData.Item(it))
        }

        resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        resultIntent.clipData = clipData
    }

    private fun fillIntentPath(resultData: Intent, resultIntent: Intent) {
        val data = resultData.data
        val path = if (data.toString().startsWith("/")) data.toString() else data!!.path
        val uri = getFilePublicUri(File(path!!), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        resultIntent.setDataAndTypeAndNormalize(uri, type)
        resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun itemClicked(path: String) {
        handleLockedFolderOpening(path) { success ->
            if (success) {
                Intent(this, MediaActivity::class.java).apply {
                    putExtra(SKIP_AUTHENTICATION, true)
                    putExtra(DIRECTORY, path)
                    handleMediaIntent(this)
                }
            }
        }
    }

    private fun handleMediaIntent(intent: Intent) {
        hideKeyboard()
        intent.apply {
            if (mIsSetWallpaperIntent) {
                putExtra(SET_WALLPAPER_INTENT, true)
                startActivityForResult(this, PICK_WALLPAPER)
            } else {
                putExtra(GET_IMAGE_INTENT, mIsPickImageIntent || mIsGetImageContentIntent)
                putExtra(GET_VIDEO_INTENT, mIsPickVideoIntent || mIsGetVideoContentIntent)
                putExtra(GET_ANY_INTENT, mIsGetAnyContentIntent)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, mAllowPickingMultiple)
                startActivityForResult(this, PICK_MEDIA)
            }
        }
    }

    private fun gotDirectories(newDirs: ArrayList<Directory>) {
        mIsGettingDirs = false
        mShouldStopFetching = false

        // if hidden item showing is disabled but all Favorite items are hidden, hide the Favorites folder
        if (!config.shouldShowHidden) {
            val favoritesFolder = newDirs.firstOrNull { it.areFavorites() }
            if (
                favoritesFolder != null
                && favoritesFolder.tmb.getFilenameFromPath().startsWith('.')
            ) {
                newDirs.remove(favoritesFolder)
            }
        }

        val dirs = getSortedDirectories(newDirs)
        if (config.groupDirectSubfolders) {
            mDirs = dirs.clone() as ArrayList<Directory>
        }

        var isPlaceholderVisible = dirs.isEmpty()

        runOnUiThread {
            checkPlaceholderVisibility(dirs)
            setupAdapter(dirs.clone() as ArrayList<Directory>)
        }

        // cached folders have been loaded, recheck folders one by one starting with the first displayed
        mLastMediaFetcher?.shouldStop = true
        mLastMediaFetcher = MediaFetcher(applicationContext)
        val getImages = mIsPickImageIntent || mIsGetImageContentIntent
        val getVideos = mIsPickVideoIntent || mIsGetVideoContentIntent
        val getImagesOnly = getImages && !getVideos
        val getVideosOnly = getVideos && !getImages
        val favoritePaths = getFavoritePaths()
        val hiddenString = getString(R.string.hidden)
        val albumCovers = config.parseAlbumCovers()
        val includedFolders = config.includedFolders
        val noMediaFolders = getNoMediaFoldersSync()
        val tempFolderPath = config.tempFolderPath
        val getProperFileSize = config.directorySorting and SORT_BY_SIZE != 0
        val dirPathsToRemove = ArrayList<String>()
        val lastModifieds = mLastMediaFetcher!!.getLastModifieds()
        val dateTakens = mLastMediaFetcher!!.getDateTakens()

        if (
            config.showRecycleBinAtFolders
            && !config.showRecycleBinLast
            && !dirs.map { it.path }.contains(RECYCLE_BIN)
        ) {
            try {
                if (mediaDB.getDeletedMediaCount() > 0) {
                    val recycleBin = Directory().apply {
                        path = RECYCLE_BIN
                        name = getString(org.fossify.commons.R.string.recycle_bin)
                        location = LOCATION_INTERNAL
                    }

                    dirs.add(0, recycleBin)
                }
            } catch (ignored: Exception) {
            }
        }

        if (dirs.map { it.path }.contains(FAVORITES)) {
            if (mediaDB.getFavoritesCount() > 0) {
                val favorites = Directory().apply {
                    path = FAVORITES
                    name = getString(org.fossify.commons.R.string.favorites)
                    location = LOCATION_INTERNAL
                }

                dirs.add(0, favorites)
            }
        }

        // fetch files from MediaStore only, unless the app has the MANAGE_EXTERNAL_STORAGE permission on Android 11+
        val android11Files = mLastMediaFetcher?.getAndroid11FolderMedia(
            isPickImage = getImagesOnly,
            isPickVideo = getVideosOnly,
            favoritePaths = favoritePaths,
            getFavoritePathsOnly = false,
            getProperDateTaken = true,
            dateTakens = dateTakens
        )
        try {
            for (directory in dirs) {
                if (mShouldStopFetching || isDestroyed || isFinishing) {
                    return
                }

                val sorting = config.getFolderSorting(directory.path)
                val grouping = config.getFolderGrouping(directory.path)
                val getProperDateTaken = config.directorySorting and SORT_BY_DATE_TAKEN != 0
                        || sorting and SORT_BY_DATE_TAKEN != 0
                        || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0
                        || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0

                val getProperLastModified =
                    config.directorySorting and SORT_BY_DATE_MODIFIED != 0
                            || sorting and SORT_BY_DATE_MODIFIED != 0
                            || grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0
                            || grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

                val curMedia = mLastMediaFetcher!!.getFilesFrom(
                    curPath = directory.path,
                    isPickImage = getImagesOnly,
                    isPickVideo = getVideosOnly,
                    getProperDateTaken = getProperDateTaken,
                    getProperLastModified = getProperLastModified,
                    getProperFileSize = getProperFileSize,
                    favoritePaths = favoritePaths,
                    getVideoDurations = false,
                    lastModifieds = lastModifieds,
                    dateTakens = dateTakens,
                    android11Files = android11Files
                )

                val newDir = if (curMedia.isEmpty()) {
                    if (directory.path != tempFolderPath) {
                        dirPathsToRemove.add(directory.path)
                    }
                    directory
                } else {
                    createDirectoryFromMedia(
                        path = directory.path,
                        curMedia = curMedia,
                        albumCovers = albumCovers,
                        hiddenString = hiddenString,
                        includedFolders = includedFolders,
                        getProperFileSize = getProperFileSize,
                        noMediaFolders = noMediaFolders
                    )
                }

                // we are looping through the already displayed folders looking for changes, do not do anything if nothing changed
                if (directory.copy(subfoldersCount = 0, subfoldersMediaCount = 0) == newDir) {
                    continue
                }

                directory.apply {
                    tmb = newDir.tmb
                    name = newDir.name
                    mediaCnt = newDir.mediaCnt
                    modified = newDir.modified
                    taken = newDir.taken
                    this@apply.size = newDir.size
                    types = newDir.types
                    sortValue = getDirectorySortingValue(curMedia, path, name, size, mediaCnt)
                }

                setupAdapter(dirs)

                // update directories and media files in the local db, delete invalid items. Intentionally creating a new thread
                if (!directory.isSmartAlbum()) {
                    updateDBDirectory(directory)
                }
                if (!directory.isRecycleBin() && !directory.areFavorites() && !directory.isSmartAlbum()) {
                    Thread {
                        try {
                            mediaDB.insertAll(curMedia)
                        } catch (ignored: Exception) {
                        }
                    }.start()
                }

                if (!directory.isRecycleBin() && !directory.isSmartAlbum()) {
                    getCachedMedia(directory.path, getVideosOnly, getImagesOnly) {
                        val mediaToDelete = ArrayList<Medium>()
                        it.forEach {
                            if (!curMedia.contains(it)) {
                                val medium = it as? Medium
                                val path = medium?.path
                                if (path != null) {
                                    mediaToDelete.add(medium)
                                }
                            }
                        }
                        mediaDB.deleteMedia(*mediaToDelete.toTypedArray())
                    }
                }
            }

            if (dirPathsToRemove.isNotEmpty()) {
                val dirsToRemove = dirs.filter { dirPathsToRemove.contains(it.path) }
                dirsToRemove.forEach {
                    directoryDB.deleteDirPath(it.path)
                }
                dirs.removeAll(dirsToRemove)
                setupAdapter(dirs)
            }
        } catch (ignored: Exception) {
        }

        val foldersToScan = mLastMediaFetcher!!.getFoldersToScan()
        foldersToScan.remove(FAVORITES)
        foldersToScan.add(0, FAVORITES)
        if (config.showRecycleBinAtFolders) {
            if (foldersToScan.contains(RECYCLE_BIN)) {
                foldersToScan.remove(RECYCLE_BIN)
                foldersToScan.add(0, RECYCLE_BIN)
            } else {
                foldersToScan.add(0, RECYCLE_BIN)
            }
        } else {
            foldersToScan.remove(RECYCLE_BIN)
        }

        dirs.filterNot { it.path == RECYCLE_BIN || it.path == FAVORITES }.forEach {
            foldersToScan.remove(it.path)
        }

        // check the remaining folders which were not cached at all yet
        for (folder in foldersToScan) {
            if (mShouldStopFetching || isDestroyed || isFinishing) {
                return
            }

            val sorting = config.getFolderSorting(folder)
            val grouping = config.getFolderGrouping(folder)
            val getProperDateTaken = config.directorySorting and SORT_BY_DATE_TAKEN != 0
                    || sorting and SORT_BY_DATE_TAKEN != 0
                    || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0
                    || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0

            val getProperLastModified = config.directorySorting and SORT_BY_DATE_MODIFIED != 0
                    || sorting and SORT_BY_DATE_MODIFIED != 0
                    || grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0
                    || grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

            val newMedia = mLastMediaFetcher!!.getFilesFrom(
                curPath = folder,
                isPickImage = getImagesOnly,
                isPickVideo = getVideosOnly,
                getProperDateTaken = getProperDateTaken,
                getProperLastModified = getProperLastModified,
                getProperFileSize = getProperFileSize,
                favoritePaths = favoritePaths,
                getVideoDurations = false,
                lastModifieds = lastModifieds,
                dateTakens = dateTakens,
                android11Files = android11Files
            )

            if (newMedia.isEmpty()) {
                continue
            }

            if (isPlaceholderVisible) {
                isPlaceholderVisible = false
                runOnUiThread {
                    binding.directoriesEmptyPlaceholder.beGone()
                    binding.directoriesEmptyPlaceholder2.beGone()
                    binding.directoriesFastscroller.beVisible()
                }
            }

            val newDir = createDirectoryFromMedia(
                path = folder,
                curMedia = newMedia,
                albumCovers = albumCovers,
                hiddenString = hiddenString,
                includedFolders = includedFolders,
                getProperFileSize = getProperFileSize,
                noMediaFolders = noMediaFolders
            )
            dirs.add(newDir)
            setupAdapter(dirs)

            // make sure to create a new thread for these operations, dont just use the common bg thread
            Thread {
                try {
                    directoryDB.insert(newDir)
                    if (folder != RECYCLE_BIN && folder != FAVORITES) {
                        mediaDB.insertAll(newMedia)
                    }
                } catch (ignored: Exception) {
                }
            }.start()
        }

        mLoadedInitialPhotos = true
        if (config.appRunCount > 1) {
            checkLastMediaChanged()
        }

        runOnUiThread {
            binding.directoriesRefreshLayout.isRefreshing = false
            checkPlaceholderVisibility(dirs)
        }

        checkInvalidDirectories(dirs)
        if (mDirs.size > 50) {
            excludeSpamFolders()
        }

        val excludedFolders = config.excludedFolders
        val everShownFolders = config.everShownFolders.toMutableSet() as HashSet<String>

        // do not add excluded folders and their subfolders at everShownFolders
        dirs.filter { dir ->
            return@filter !excludedFolders.any { dir.path.startsWith(it) }
        }.mapTo(everShownFolders) { it.path }

        try {
            // scan the internal storage from time to time for new folders
            if (config.appRunCount == 1 || config.appRunCount % 30 == 0) {
                everShownFolders.addAll(getFoldersWithMedia(config.internalStoragePath))
            }

            // catch some extreme exceptions like too many everShownFolders for storing, shouldnt really happen
            config.everShownFolders = everShownFolders
        } catch (e: Exception) {
            config.everShownFolders = HashSet()
        }

        mDirs = dirs.clone() as ArrayList<Directory>

        // priečinky sú načítané — premietneme ich aj do krátkeho zoznamu na Domove
        runOnUiThread { refreshHomeFolders() }
    }

    private fun setAsDefaultFolder() {
        config.defaultFolder = ""
        refreshMenuItems()
    }

    private fun openDefaultFolder() {
        if (config.defaultFolder.isEmpty()) {
            return
        }

        val defaultDir = File(config.defaultFolder)

        if ((!defaultDir.exists() || !defaultDir.isDirectory) && (config.defaultFolder != RECYCLE_BIN && config.defaultFolder != FAVORITES)) {
            config.defaultFolder = ""
            return
        }

        Intent(this, MediaActivity::class.java).apply {
            putExtra(DIRECTORY, config.defaultFolder)
            handleMediaIntent(this)
        }
    }

    private fun checkPlaceholderVisibility(dirs: ArrayList<Directory>) {
        binding.directoriesEmptyPlaceholder.beVisibleIf(dirs.isEmpty() && mLoadedInitialPhotos)
        binding.directoriesEmptyPlaceholder2.beVisibleIf(dirs.isEmpty() && mLoadedInitialPhotos)

        if (binding.mainMenu.isSearchOpen) {
            binding.directoriesEmptyPlaceholder.text =
                getString(org.fossify.commons.R.string.no_items_found)
            binding.directoriesEmptyPlaceholder2.beGone()
        } else if (dirs.isEmpty() && config.filterMedia == getDefaultFileFilter()) {
            if (isRPlus() && !isExternalStorageManager()) {
                binding.directoriesEmptyPlaceholder.text =
                    getString(org.fossify.commons.R.string.no_items_found)
                binding.directoriesEmptyPlaceholder2.beGone()
            } else {
                binding.directoriesEmptyPlaceholder.text = getString(R.string.no_media_add_included)
                binding.directoriesEmptyPlaceholder2.text = getString(R.string.add_folder)
            }

            binding.directoriesEmptyPlaceholder2.setOnClickListener {
                showAddIncludedFolderDialog {
                    refreshItems()
                }
            }
        } else {
            binding.directoriesEmptyPlaceholder.text = getString(R.string.no_media_with_filters)
            binding.directoriesEmptyPlaceholder2.text =
                getString(R.string.change_filters_underlined)

            binding.directoriesEmptyPlaceholder2.setOnClickListener {
                showFilterMediaDialog()
            }
        }

        binding.directoriesEmptyPlaceholder2.underlineText()
        binding.directoriesFastscroller.beVisibleIf(binding.directoriesEmptyPlaceholder.isGone())
    }

    private fun setupAdapter(
        dirs: ArrayList<Directory>,
        textToSearch: String = binding.mainMenu.getCurrentQuery(),
        forceRecreate: Boolean = false
    ) {
        val currAdapter = binding.directoriesGrid.adapter
        val distinctDirs = dirs
            .distinctBy { it.path.getDistinctPath() }
            .toMutableList() as ArrayList<Directory>

        val sortedDirs = getSortedDirectories(distinctDirs)
        var dirsToShow = getDirsToShow(
            dirs = sortedDirs,
            allDirs = mDirs,
            currentPathPrefix = mCurrentPathPrefix
        ).clone() as ArrayList<Directory>

        if (currAdapter == null || forceRecreate) {
            mDirsIgnoringSearch = dirs
            initZoomListener()
            DirectoryAdapter(
                this,
                dirsToShow,
                this,
                binding.directoriesGrid,
                isPickIntent(intent) || isGetAnyContentIntent(intent),
                binding.directoriesRefreshLayout
            ) {
                val clickedDir = it as Directory
                val path = clickedDir.path
                if (clickedDir.subfoldersCount == 1 || !config.groupDirectSubfolders) {
                    if (path != config.tempFolderPath) {
                        itemClicked(path)
                    }
                } else {
                    mCurrentPathPrefix = path
                    mOpenedSubfolders.add(path)
                    setupAdapter(mDirs, "")
                }
            }.apply {
                setupZoomListener(mZoomListener)
                runOnUiThread {
                    binding.directoriesGrid.adapter = this
                    setupScrollDirection()

                    if (config.viewTypeFolders == VIEW_TYPE_LIST && areSystemAnimationsEnabled) {
                        binding.directoriesGrid.scheduleLayoutAnimation()
                    }
                }
            }
        } else {
            runOnUiThread {
                if (textToSearch.isNotEmpty()) {
                    dirsToShow = dirsToShow
                        .filter { it.name.contains(textToSearch, true) }
                        .sortedBy { !it.name.startsWith(textToSearch, true) }
                        .toMutableList() as ArrayList
                }
                checkPlaceholderVisibility(dirsToShow)

                (binding.directoriesGrid.adapter as? DirectoryAdapter)?.updateDirs(dirsToShow)
            }
        }

        // recyclerview sometimes becomes empty at init/update, triggering an invisible refresh like this seems to work fine
        binding.directoriesGrid.postDelayed({
            binding.directoriesGrid.scrollBy(0, 0)
        }, 500)
    }

    private fun setupScrollDirection() {
        val scrollHorizontally =
            config.scrollHorizontally && config.viewTypeFolders == VIEW_TYPE_GRID
        binding.directoriesFastscroller.setScrollVertically(!scrollHorizontally)
    }

    private fun checkInvalidDirectories(dirs: ArrayList<Directory>) {
        val invalidDirs = ArrayList<Directory>()
        val OTGPath = config.OTGPath
        dirs.filter { !it.areFavorites() && !it.isRecycleBin() }.forEach {
            if (!getDoesFilePathExist(it.path, OTGPath)) {
                invalidDirs.add(it)
            } else if (it.path != config.tempFolderPath && (!isRPlus() || isExternalStorageManager())) {
                // avoid calling file.list() or listfiles() on Android 11+, it became way too slow
                val children = if (isPathOnOTG(it.path)) {
                    getOTGFolderChildrenNames(it.path)
                } else {
                    File(it.path).list()?.asList()
                }

                val hasMediaFile = children?.any {
                    it != null && (
                            it.isMediaFile()
                                    || (it.startsWith("img_", true)
                                    && File(it).isDirectory)
                            )
                } == true

                if (!hasMediaFile) {
                    invalidDirs.add(it)
                }
            }
        }

        if (getFavoritePaths().isEmpty()) {
            val favoritesFolder = dirs.firstOrNull { it.areFavorites() }
            if (favoritesFolder != null) {
                invalidDirs.add(favoritesFolder)
            }
        }

        if (config.useRecycleBin) {
            try {
                val binFolder = dirs.firstOrNull { it.path == RECYCLE_BIN }
                if (binFolder != null && mediaDB.getDeletedMedia().isEmpty()) {
                    invalidDirs.add(binFolder)
                }
            } catch (ignored: Exception) {
            }
        }

        if (invalidDirs.isNotEmpty()) {
            dirs.removeAll(invalidDirs)
            setupAdapter(dirs)
            invalidDirs.forEach {
                try {
                    directoryDB.deleteDirPath(it.path)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun getCurrentlyDisplayedDirs() = getRecyclerAdapter()?.dirs ?: ArrayList()

    private fun setupLatestMediaId() {
        ensureBackgroundThread {
            if (hasPermission(PERMISSION_READ_STORAGE)) {
                mLatestMediaId = getLatestMediaId()
                mLatestMediaDateId = getLatestMediaByDateId()
            }
        }
    }

    private fun checkLastMediaChanged() {
        if (isDestroyed) {
            return
        }

        mLastMediaHandler.postDelayed({
            ensureBackgroundThread {
                val mediaId = getLatestMediaId()
                val mediaDateId = getLatestMediaByDateId()
                if (mLatestMediaId != mediaId || mLatestMediaDateId != mediaDateId) {
                    mLatestMediaId = mediaId
                    mLatestMediaDateId = mediaDateId
                    runOnUiThread {
                        getDirectories()
                        // knižnica sa zmenila — aj cache Posledných musí ísť preč
                        invalidateRecent()
                    }
                } else {
                    mLastMediaHandler.removeCallbacksAndMessages(null)
                    checkLastMediaChanged()
                }
            }
        }, LAST_MEDIA_CHECK_PERIOD)
    }

    private fun checkRecycleBinItems() {
        if (config.useRecycleBin && config.lastBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000) {
            config.lastBinCheck = System.currentTimeMillis()
            Handler().postDelayed({
                ensureBackgroundThread {
                    try {
                        val filesToDelete = mediaDB.getOldRecycleBinItems(
                            System.currentTimeMillis() - MONTH_MILLISECONDS
                        )
                        filesToDelete.forEach {
                            if (File(it.path.replaceFirst(RECYCLE_BIN, recycleBinPath)).delete()) {
                                mediaDB.deleteMediumPath(it.path)
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }, 3000L)
        }
    }

    // exclude probably unwanted folders, for example facebook stickers are split between hundreds of separate folders like
    // /storage/emulated/0/Android/data/com.facebook.orca/files/stickers/175139712676531/209575122566323
    // /storage/emulated/0/Android/data/com.facebook.orca/files/stickers/497837993632037/499671223448714
    private fun excludeSpamFolders() {
        ensureBackgroundThread {
            try {
                val internalPath = internalStoragePath
                val checkedPaths = ArrayList<String>()
                val oftenRepeatedPaths = ArrayList<String>()
                val paths = mDirs
                    .map { it.path.removePrefix(internalPath) }
                    .toMutableList() as ArrayList<String>
                paths.forEach {
                    val parts = it.split("/")
                    var currentString = ""
                    for (i in 0 until parts.size) {
                        currentString += "${parts[i]}/"

                        if (!checkedPaths.contains(currentString)) {
                            val cnt = paths.count { it.startsWith(currentString) }
                            if (cnt > 50 && currentString.startsWith("/Android/data", true)) {
                                oftenRepeatedPaths.add(currentString)
                            }
                        }

                        checkedPaths.add(currentString)
                    }
                }

                val substringToRemove = oftenRepeatedPaths.filter {
                    val path = it
                    it == "/" || oftenRepeatedPaths.any { it != path && it.startsWith(path) }
                }

                oftenRepeatedPaths.removeAll(substringToRemove)
                val OTGPath = config.OTGPath
                oftenRepeatedPaths.forEach {
                    val file = File("$internalPath/$it")
                    if (getDoesFilePathExist(file.absolutePath, OTGPath)) {
                        config.addExcludedFolder(file.absolutePath)
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun getFoldersWithMedia(path: String): HashSet<String> {
        val folders = HashSet<String>()
        try {
            val files = File(path).listFiles()
            if (files != null) {
                files.sortBy { !it.isDirectory }
                for (file in files) {
                    if (file.isDirectory && !file.startsWith("${config.internalStoragePath}/Android")) {
                        folders.addAll(getFoldersWithMedia(file.absolutePath))
                    } else if (file.isFile && file.isMediaFile()) {
                        folders.add(file.parent ?: "")
                        break
                    }
                }
            }
        } catch (ignored: Exception) {
        }

        return folders
    }

    override fun refreshItems() {
        getDirectories()
    }

    override fun recheckPinnedFolders() {
        ensureBackgroundThread {
            gotDirectories(movePinnedDirectoriesToFront(getCurrentlyDisplayedDirs()))
        }
    }

    override fun updateDirectories(directories: ArrayList<Directory>) {
        ensureBackgroundThread {
            storeDirectoryItems(directories)
            removeInvalidDBDirectories()
        }
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }

    private fun checkForAppUpdate() {
        AppUpdater.checkForUpdate(this, force = false) { update ->
            if (update != null && !isFinishing && !isDestroyed) {
                ConfirmationDialog(
                    this,
                    getString(R.string.update_available_msg, update.versionName),
                    positive = R.string.update_now,
                    negative = R.string.update_later
                ) {
                    toast(R.string.update_downloading)
                    AppUpdater.downloadAndInstall(this, update) {
                        toast(R.string.update_check_failed)
                    }
                }
            }
        }
    }
}
