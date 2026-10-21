package org.fossify.gallery.activities

import android.content.Intent
import android.database.SQLException
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.commons.views.MyEditText
import org.fossify.commons.views.MyTextView
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PersonFacesAdapter
import org.fossify.gallery.adapters.PhotoPathsAdapter
import org.fossify.gallery.databinding.ActivityPersonBinding
import org.fossify.gallery.dialogs.ChangeSortingDialog
import org.fossify.gallery.extensions.config
import org.fossify.gallery.faces.FaceCropLoader
import org.fossify.gallery.faces.FaceEntity
import org.fossify.gallery.faces.FaceMediaMeta
import org.fossify.gallery.faces.FaceSorter
import org.fossify.gallery.faces.FaceVisibility
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PeopleIndex
import org.fossify.gallery.family.FamilyRecorder
import org.fossify.gallery.helpers.FaceDecisions
import org.fossify.gallery.helpers.GridZoom
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION

class PersonActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPersonBinding::inflate)
    private var personId: Long = -1L
    private var personName: String? = null
    // ručne potvrdené tváre karty — ostatné sú zaradené automaticky a v mriežke majú značku (C7)
    private var manualIds: Set<Long> = emptySet()
    private var facesAdapter: PersonFacesAdapter? = null

    // C7: sekcia „Potvrď“ — neisté tváre osoby z jedného zdroja pravdy (PeopleIndex)
    private var confirmAdapter: PersonFacesAdapter? = null
    private var uncertainFaces: List<FaceEntity> = emptyList()
    private var confirmMode = false
    private var photosAdapter: PhotoPathsAdapter? = null
    private var loadedFaces: List<FaceEntity> = emptyList()
    private var photoPaths: ArrayList<String> = arrayListOf()
    private var meta: Map<String, FaceMediaMeta.Meta> = emptyMap()
    private var showFullPhotos = false
    private var resumedOnce = false
    private val prefs by lazy { getSharedPreferences("galeria_faces", android.content.Context.MODE_PRIVATE) }

    // tváre presunutých/zmazaných fotiek a fotiek mimo rozsahu (zamknuté/vylúčené priečinky) sa na karte
    // neukazujú — len v UI, DB ostáva bez zmeny; cache na dobu otvorenia obrazovky
    private val faceVisibility by lazy { FaceVisibility(this) }

    private fun sortPath() = "person_$personId"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        personId = intent.getLongExtra(PERSON_ID, -1L)
        personName = intent.getStringExtra(PERSON_NAME)
        manualIds = intent.getLongArrayExtra(MANUAL_IDS)?.toSet() ?: emptySet()
        val faceIds = intent.getLongArrayExtra(FACE_IDS)?.toList() ?: emptyList()
        val lm = GridLayoutManager(this, prefs.getInt("person_columns", COLUMNS))
        binding.personGrid.layoutManager = lm
        GridZoom.setup(binding.personGrid, lm, prefs, "person_columns")
        setupEdgeToEdge(
            padTopSystem = listOf(binding.personAppbar),
            padBottomSystem = listOf(binding.personGrid),
        )
        setupMaterialScrollListener(binding.personGrid, binding.personAppbar)
        binding.personConfirmBanner.setOnClickListener { toggleConfirmMode() }
        loadFaces(faceIds)
    }

    override fun onResume() {
        super.onResume()
        binding.personFastscroller.updateColors(getProperPrimaryColor())
        binding.personToolbar.title = personName ?: getString(R.string.person_suggested)
        binding.personToolbar.menu.clear()
        binding.personToolbar.inflateMenu(R.menu.menu_person)
        updateMenuTitles()
        binding.personToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.suggestions -> {
                    if (personId >= 0) openSuggestions()
                    true
                }

                R.id.toggle_view -> {
                    showFullPhotos = !showFullPhotos
                    confirmMode = false
                    updateConfirmBanner()
                    updateMenuTitles()
                    render()
                    true
                }

                R.id.sort_order -> {
                    ChangeSortingDialog(this, false, true, sortPath()) { render() }
                    true
                }

                else -> false
            }
        }
        // až po naplnení menu — setupTopAppBar farbí ikony, ktoré v lište práve sú
        setupTopAppBar(binding.personAppbar, NavigationIcon.Arrow)
        // návrat z „Návrhov" / prehliadača: tváre potvrdené inde sa musia objaviť aj na tejto karte
        // (prvé zobrazenie berie dáta z Intentu, preto sa preskočí)
        if (resumedOnce) refreshPerson() else resumedOnce = true
    }

    private fun updateMenuTitles() {
        binding.personToolbar.menu.findItem(R.id.toggle_view)?.title =
            getString(if (showFullPhotos) R.string.show_faces else R.string.show_photos)
    }

    private fun loadFaces(faceIds: List<Long>) {
        ensureBackgroundThread {
            val faces = try {
                if (faceIds.isEmpty()) emptyList()
                else faceVisibility.filterFaces(FacesDatabase.getInstance(this).FaceDao().getFacesByIds(faceIds))
            } catch (e: Throwable) {
                emptyList()
            }
            val uncertain = loadUncertain()
            val loadedMeta = FaceMediaMeta.load(this, faces.map { it.mediaFullPath }.distinct())
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loadedFaces = faces
                meta = loadedMeta
                uncertainFaces = uncertain
                updateConfirmBanner()
                render()
            }
        }
    }

    // neisté tváre osoby (C7), len viditeľné; volať z pozadia
    private fun loadUncertain(): List<FaceEntity> {
        if (personId < 0) return emptyList()
        return try {
            faceVisibility.filterFaces(PeopleIndex.get(this).uncertain[personId].orEmpty().map { it.face })
        } catch (ignored: SQLException) {
            emptyList()
        } catch (ignored: IllegalStateException) {
            emptyList()
        }
    }

    private fun updateConfirmBanner() {
        val banner = binding.personConfirmBanner
        if (personId < 0 || (uncertainFaces.isEmpty() && !confirmMode)) {
            banner.beGone()
            return
        }
        banner.text = if (confirmMode) {
            getString(R.string.tc2_confirm_hint, personName ?: "")
        } else {
            resources.getQuantityString(R.plurals.tc2_confirm_banner, uncertainFaces.size, uncertainFaces.size)
        }
        banner.setTextColor(getProperPrimaryColor())
        banner.beVisible()
    }

    // prepnutie medzi tvárami osoby a sekciou „Potvrď“
    private fun toggleConfirmMode() {
        confirmMode = !confirmMode && uncertainFaces.isNotEmpty()
        if (confirmMode) showFullPhotos = false
        updateMenuTitles()
        updateConfirmBanner()
        render()
    }

    // Prepočet osoby rovnakou logikou ako zoznam Ľudia (ručne potvrdené + auto-zaradené tváre) — NIE holým
    // dotazom na priradenia, ten by auto-zaradené tváre z karty vyhodil. Nepotvrdená skupina (bez id) je
    // nemenný snímok, tá sa neobnovuje. Ak sa množina tvárí nezmenila, mriežka sa nedotkne (ostane scroll aj výber).
    private fun refreshPerson() {
        if (personId < 0) return
        val id = personId
        val knownIds = loadedFaces.mapNotNullTo(HashSet()) { it.id }
        ensureBackgroundThread {
            val person = try {
                PeopleActivity.loadPersons(this).firstOrNull { it.id == id }
            } catch (e: Throwable) {
                null
            } ?: return@ensureBackgroundThread
            val uncertain = loadUncertain()
            val visibleFaces = faceVisibility.filterFaces(person.faces)
            val newIds = visibleFaces.mapNotNullTo(HashSet()) { it.id }
            val newMeta = if (newIds == knownIds) {
                emptyMap()
            } else {
                FaceMediaMeta.load(this, visibleFaces.map { it.mediaFullPath }.distinct())
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                manualIds = person.manualFaceIds
                personName = person.name ?: personName
                val uncertainChanged = uncertain.map { it.id } != uncertainFaces.map { it.id }
                uncertainFaces = uncertain
                val wasConfirm = confirmMode
                if (uncertain.isEmpty()) confirmMode = false
                updateConfirmBanner()
                if (newIds == knownIds) {
                    // značky (ručne/automaticky) a sekcia „Potvrď“ sa mohli zmeniť aj bez zmeny množiny tvárí
                    if (wasConfirm && (uncertainChanged || !confirmMode)) {
                        render()
                    } else {
                        facesAdapter?.notifyDataSetChanged()
                    }
                    return@runOnUiThread
                }
                loadedFaces = visibleFaces
                meta = newMeta
                render()
            }
        }
    }

    private fun render() {
        val sorting = config.getFolderSorting(sortPath())
        val faces = loadedFaces
        // triedenie tisícok tvárí (lowercase, lookup do meta) patrí na pozadie — UI len nastaví adaptér
        ensureBackgroundThread {
            val facesSorted = FaceSorter.sortFaces(faces, meta, sorting)
            val sortedPaths = ArrayList(
                FaceSorter.sortPaths(faces.map { it.mediaFullPath }.distinct(), meta, sorting).take(2000)
            )
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                photoPaths = sortedPaths
                if (confirmMode) {
                    showConfirmGrid()
                } else if (showFullPhotos) {
                    // jednotná mriežka s výberovým režimom; „toto nie je osoba" je kontextová akcia
                    // vo výbere (predtým deštruktívny long-press v rozpore so zvyškom appky)
                    val existing = photosAdapter
                    if (existing != null && binding.personGrid.adapter === existing) {
                        // nevymieňaj adaptér — aktualizácia dát zachová pozíciu scrollu
                        existing.updateItems(photoPaths)
                    } else {
                        val adapter = PhotoPathsAdapter(
                            this, ArrayList(photoPaths), binding.personGrid,
                            onClick = { path -> openPhoto(path) },
                            onDeleted = { deleted -> onPhotosDeleted(deleted) },
                            onChanged = { renamed -> onPhotosRenamed(renamed) },
                            extraAction = if (personId >= 0) {
                                PhotoPathsAdapter.ExtraAction(R.string.action_not_this_person) { selection ->
                                    confirmNotThisPersonPhotos(selection)
                                }
                            } else {
                                null
                            },
                        )
                        photosAdapter = adapter
                        binding.personGrid.adapter = adapter
                    }
                } else {
                    val existing = facesAdapter
                    if (existing != null && binding.personGrid.adapter === existing) {
                        // nevymieňaj adaptér — aktualizácia dát zachová pozíciu scrollu
                        existing.updateItems(facesSorted)
                    } else {
                        val adapter = PersonFacesAdapter(
                            this, facesSorted.toMutableList(), binding.personGrid,
                            canReject = personId >= 0,
                            onClick = { face -> openPhoto(face.mediaFullPath) },
                            onMove = { selected -> moveFaces(selected) },
                            onReject = { selected -> confirmRejectFaces(selected) },
                            badgeOf = { face -> badgeOf(face) },
                        )
                        facesAdapter = adapter
                        binding.personGrid.adapter = adapter
                    }
                }
            }
        }
    }

    // ručne potvrdená tvár bez značky, ostatné (automaticky zaradené) so značkou „A“ (C7)
    private fun badgeOf(face: FaceEntity): Int {
        val id = face.id ?: return PersonFacesAdapter.BADGE_NONE
        val manual = personId < 0 || manualIds.contains(id)
        return if (manual) PersonFacesAdapter.BADGE_NONE else PersonFacesAdapter.BADGE_AUTO
    }

    // sekcia „Potvrď“: ťuk = áno, podržanie = výberový režim (Áno / Nie je táto osoba)
    private fun showConfirmGrid() {
        val faces = uncertainFaces
        val existing = confirmAdapter
        if (existing != null && binding.personGrid.adapter === existing) {
            existing.updateItems(faces)
            return
        }
        val adapter = PersonFacesAdapter(
            this, faces.toMutableList(), binding.personGrid,
            canReject = true,
            onClick = { face -> confirmFaces(listOf(face)) },
            onMove = {},
            onReject = { selected -> confirmRejectFaces(selected) },
            badgeOf = { PersonFacesAdapter.BADGE_UNCERTAIN },
            confirmMode = true,
            onConfirm = { selected -> confirmFaces(selected) },
        )
        confirmAdapter = adapter
        binding.personGrid.adapter = adapter
    }

    // „Áno, je to táto osoba“ — ručné potvrdenie neistých tvárí (s upozornením, ak by osoba bola na fotke dvakrát)
    private fun confirmFaces(selected: List<FaceEntity>) {
        val ids = selected.mapNotNull { it.id }
        if (personId < 0 || ids.isEmpty()) return
        FaceTaggingActivity.confirmSamePhoto(this, ids, personId, personName) {
            ensureBackgroundThread {
                PeopleDatabase.getInstance(this).PeopleDao().assignFaces(ids, personId, System.currentTimeMillis())
                FamilyRecorder.facesAssigned(this, ids, personId)
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    val confirmed = ids.toHashSet()
                    val known = loadedFaces.mapNotNullTo(HashSet()) { it.id }
                    loadedFaces = loadedFaces + selected.filter { f -> f.id?.let { !known.contains(it) } == true }
                    manualIds = manualIds + confirmed
                    uncertainFaces = uncertainFaces.filter { f -> f.id?.let { !confirmed.contains(it) } ?: true }
                    confirmAdapter?.removeFaces(confirmed)
                    afterConfirmChange()
                }
            }
        }
    }

    // po potvrdení/odmietnutí v sekcii „Potvrď“: prázdna sekcia sa zavrie
    private fun afterConfirmChange() {
        if (uncertainFaces.isEmpty() && confirmMode) {
            confirmMode = false
            toast(R.string.tc2_confirm_empty)
            render()
        }
        updateConfirmBanner()
    }

    // po zmazaní fotiek vo výberovom režime — adaptér si mriežku upravil sám, tu len zosúladíme
    // lokálne dáta osoby, aby ďalší render()/prepnutie zobrazenia zmazané fotky nevzkriesilo
    private fun onPhotosDeleted(deleted: List<String>) {
        faceVisibility.forget(deleted)
        val gone = deleted.toHashSet()
        loadedFaces = loadedFaces.filter { !gone.contains(it.mediaFullPath) }
        photoPaths = ArrayList(photoPaths.filter { !gone.contains(it) })
    }

    // fotky premenované / odkryté vo výberovom režime (stará cesta -> nová). LEN v pamäti obrazovky:
    // faces.db ostáva na starej ceste, preto sa pri ďalšom otvorení karty takáto fotka skryje
    // (trvalé riešenie príde s identitou fotky podľa obsahu — contentKey)
    private fun onPhotosRenamed(renamed: Map<String, String>) {
        if (renamed.isEmpty()) return
        faceVisibility.forget(renamed.keys)
        loadedFaces = loadedFaces.map { face ->
            renamed[face.mediaFullPath]?.let { face.copy(mediaFullPath = it) } ?: face
        }
        photoPaths = ArrayList(photoPaths.map { renamed[it] ?: it })
        meta = meta.mapKeys { (path, _) -> renamed[path] ?: path }
    }

    // tváre odišli z karty (presun / „nie je táto osoba") — zosúladí lokálne dáta aj mriežku
    // a ukončí výberový režim
    private fun removeFacesLocally(ids: Set<Long>) {
        loadedFaces = loadedFaces.filter { f -> f.id?.let { !ids.contains(it) } ?: true }
        manualIds = manualIds - ids
        facesAdapter?.removeFaces(ids)
        if (uncertainFaces.any { f -> f.id?.let { ids.contains(it) } == true }) {
            uncertainFaces = uncertainFaces.filter { f -> f.id?.let { !ids.contains(it) } ?: true }
            confirmAdapter?.removeFaces(ids)
            afterConfirmChange()
        }
    }

    // výber tvárí → presun k inej (alebo novej) osobe
    private fun moveFaces(selected: List<FaceEntity>) {
        val ids = selected.mapNotNull { it.id }
        if (ids.isEmpty()) return
        ensureBackgroundThread {
            val persons = PeopleDatabase.getInstance(this).PeopleDao().getPersons().filter { it.id != personId }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val items = ArrayList<RadioItem>()
                persons.forEachIndexed { index, person -> items.add(RadioItem(index, person.name ?: "#${person.id}")) }
                items.add(RadioItem(persons.size, getString(R.string.new_person)))
                RadioGroupDialog(this, items, titleId = R.string.action_move_to_person) {
                    val which = it as Int
                    if (which == persons.size) {
                        promptName(null) { name -> assignToNewPerson(ids, name) }
                    } else {
                        val target = persons[which]
                        FaceTaggingActivity.confirmSamePhoto(this, ids, target.id, target.name) {
                            assignToPerson(ids, target.id)
                        }
                    }
                }
            }
        }
    }

    // presun z tejto osoby na inú: priradenie + „nie je táto osoba“ (C8) v jednej transakcii
    private fun assignToPerson(faceIds: List<Long>, targetId: Long) {
        ensureBackgroundThread {
            FaceDecisions.move(this, faceIds, targetId, knownFrom = personId.takeIf { it >= 0 })
            runOnUiThread {
                toast(R.string.person_saved)
                removeFacesLocally(faceIds.toHashSet())
            }
        }
    }

    private fun assignToNewPerson(faceIds: List<Long>, name: String) {
        ensureBackgroundThread {
            // nová osoba + priradenie tvárí + „nie je táto osoba“ (C8) v jednej transakcii
            FaceDecisions.moveToNewPerson(this, faceIds, name, knownFrom = personId.takeIf { it >= 0 })
            runOnUiThread {
                toast(R.string.person_saved)
                removeFacesLocally(faceIds.toHashSet())
            }
        }
    }

    // „toto nie je táto osoba" nad výberom tvárí — nevratné (tvár sa už nebude navrhovať), preto s potvrdením
    private fun confirmRejectFaces(selected: List<FaceEntity>) {
        val ids = selected.mapNotNull { it.id }
        if (personId < 0 || ids.isEmpty()) return
        ConfirmationDialog(
            this,
            message = resources.getQuantityString(R.plurals.c2_reject_faces_confirm, ids.size, ids.size, personName ?: ""),
            positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel,
            dialogTitle = getString(R.string.action_not_this_person),
        ) {
            rejectFaces(ids)
        }
    }

    private fun rejectFaces(faceIds: List<Long>) {
        if (personId < 0) return
        ensureBackgroundThread {
            // zrušenie priradenia + negatívne pravidlo (aby sa už nenavrhla) v jednej transakcii
            PeopleDatabase.getInstance(this).PeopleDao().rejectFacesForPerson(faceIds, personId)
            FamilyRecorder.facesRejected(this, faceIds, personId)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                removeFacesLocally(faceIds.toHashSet())
            }
        }
    }

    // hromadné „toto nie je [osoba]" z výberového režimu — JEDNO potvrdenie pre celý výber
    private fun confirmNotThisPersonPhotos(selection: ArrayList<String>) {
        if (personId < 0 || selection.isEmpty()) return
        ConfirmationDialog(
            this,
            message = getString(R.string.not_this_person_confirm, personName ?: ""),
            positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel,
            dialogTitle = getString(R.string.action_not_this_person),
        ) {
            notThisPersonForPaths(selection)
        }
    }

    // „Toto nie je táto osoba" nad fotkami: odmietne LEN tvár, ktorá na fotke nesie túto osobu (C2). Iné tváre
    // fotky sa nedotknú. Ak má osoba na fotke viac tvárí, appka sa spýta, ktorú (výber s výrezmi tvárí).
    private fun notThisPersonForPaths(selection: List<String>) {
        if (personId < 0) return
        val pathSet = selection.toHashSet()
        val byPath = loadedFaces.filter { pathSet.contains(it.mediaFullPath) }.groupBy { it.mediaFullPath }
        val sure = ArrayList<Long>()
        val ambiguous = ArrayList<List<FaceEntity>>()
        for (faces in byPath.values) {
            if (faces.size == 1) faces.first().id?.let { sure.add(it) } else ambiguous.add(faces)
        }
        pickAmbiguousFaces(ambiguous, 0, sure)
    }

    // fotky s viacerými tvárami osoby postupne, jedna po druhej; nakoniec jedno spoločné odmietnutie
    private fun pickAmbiguousFaces(ambiguous: List<List<FaceEntity>>, index: Int, picked: ArrayList<Long>) {
        if (isDestroyed || isFinishing) return
        if (index >= ambiguous.size) {
            rejectPickedFaces(picked)
            return
        }
        val faces = ambiguous[index]
        val next = { pickAmbiguousFaces(ambiguous, index + 1, picked) }
        val margin = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, margin / 2, 0, margin / 2)
        }
        var dialog: AlertDialog? = null
        // zľava doprava, ako sú tváre na fotke
        faces.sortedBy { it.bboxLeft }.forEachIndexed { i, face ->
            holder.addView(faceRow(getString(R.string.tc_pick_face_item, i + 1), face, margin) {
                dialog?.dismiss()
                face.id?.let { picked.add(it) }
                next()
            })
        }
        holder.addView(faceRow(getString(R.string.tc_pick_face_all, faces.size), null, margin) {
            dialog?.dismiss()
            picked.addAll(faces.mapNotNull { it.id })
            next()
        })
        val view = ScrollView(this).apply { addView(holder) }
        getAlertDialogBuilder()
            .setNegativeButton(R.string.tc_pick_face_skip) { _, _ -> next() }
            .setOnCancelListener { next() }
            .apply {
                setupDialogStuff(view, this, titleText = getString(R.string.tc_pick_face_title, personName ?: "")) {
                    dialog = it
                }
            }
    }

    // riadok výberu: výrez tváre (ak je) + text, celý riadok klikateľný
    private fun faceRow(text: String, face: FaceEntity?, margin: Int, onPick: () -> Unit): LinearLayout {
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(margin, margin / 2, margin, margin / 2)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onPick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        if (face != null) {
            val thumb = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, FACE_THUMB_DP, resources.displayMetrics)
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            line.addView(image, LinearLayout.LayoutParams(thumb.toInt(), thumb.toInt()).apply { marginEnd = margin })
            FaceCropLoader.load(face, image)
        }
        line.addView(
            MyTextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SP)
            }
        )
        return line
    }

    private fun rejectPickedFaces(faceIds: List<Long>) {
        if (personId < 0 || faceIds.isEmpty()) return
        val rejected = faceIds.toHashSet()
        ensureBackgroundThread {
            PeopleDatabase.getInstance(this).PeopleDao().rejectFacesForPerson(faceIds, personId)
            FamilyRecorder.facesRejected(this, faceIds, personId)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                // fotka ostane, ak na nej osoba má ešte inú (neodmietnutú) tvár
                loadedFaces = loadedFaces.filter { f -> f.id?.let { !rejected.contains(it) } ?: true }
                manualIds = manualIds - rejected
                toast(R.string.person_saved)
                render()
            }
        }
    }

    private fun openSuggestions() {
        val intent = Intent(this, FaceTaggingActivity::class.java)
        intent.putExtra(FaceTaggingActivity.MODE, FaceTaggingActivity.MODE_SUGGESTIONS)
        intent.putExtra(FaceTaggingActivity.PERSON_ID, personId)
        intent.putExtra(FaceTaggingActivity.PERSON_NAME, personName)
        startActivity(intent)
    }

    private fun openPhoto(path: String) {
        // uzavretý set = len fotky tejto osoby (swipe neuteká do celého priečinka)
        org.fossify.gallery.helpers.PathTransfer.forViewer = photoPaths
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, path)
            putExtra(SKIP_AUTHENTICATION, true)
            putExtra(SHOW_ALL, false)
            startActivity(this)
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
        const val PERSON_ID = "person_id"
        const val PERSON_NAME = "person_name"
        const val FACE_IDS = "face_ids"
        const val MANUAL_IDS = "manual_ids"
        private const val COLUMNS = 3
        private const val ROW_TEXT_SP = 16f
        private const val FACE_THUMB_DP = 64f
    }
}
