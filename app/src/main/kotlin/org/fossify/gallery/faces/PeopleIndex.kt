package org.fossify.gallery.faces

import android.content.Context
import android.database.SQLException
import androidx.annotation.WorkerThread
import androidx.room.InvalidationTracker
import org.fossify.gallery.helpers.FaceAuto
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// C9 — JEDEN zdroj pravdy o tom, kto je na ktorej tvári. Pre každú tvár vráti osobu a stav (ručne /
// automaticky / neisté) a z toho osoby pre Ľudí a kartu osoby, fotky osoby pre hľadanie a mapu, mená
// na fotke pre prehliadač a model pre Návrhy, odhad presnosti (C10) a „Pomôž mi rozhodnúť“ (C11).
// Predtým si hľadanie, mapa a prehliadač brali len riadky priradení (bez automatiky) a ukazovali iné
// fotky tej istej osoby ako Ľudia.
//
// Výsledok sa drží v pamäti, kým sa nezmení počítadlo verzií alebo prah automatiky. Počítadlo zvyšuje:
//  - Room InvalidationTracker pri akomkoľvek zápise do people.db, extras.db (ignorované) a faces.db —
//    zachytí aj zápisy, ktoré nejdú cez UI (rodinné prepojenie, Picasa, prepočet odtlačkov, relink);
//  - invalidate() — volá ho FamilyRecorder po každej zmene osôb z UI hneď (tracker hlási s oneskorením,
//    obrazovka, ktorá sa po zápise hneď znova načíta, by inak dostala starý stav).
// Počíta sa VÝHRADNE na pozadí (@WorkerThread), nikdy na hlavnom vlákne; nič nebeží trvalo.
// Viditeľnosť fotiek (FaceVisibility) tu nie je — rieši ju obrazovka (súkromie, chýbajúce súbory).
object PeopleIndex {
    enum class State { MANUAL, AUTO, UNCERTAIN }

    class Owner(val personId: Long, val state: State)

    class Snapshot(
        val persons: List<Person>,
        val uncertain: Map<Long, List<PersonGrouper.Scored>>,
        val contested: List<PersonGrouper.Scored>,
        val model: KnnModel?,
        val threshold: Float,
        private val owners: Map<Long, Owner>,
    ) {
        // osoba a stav tváre (ručne / automaticky / neisté), null = nikto
        fun ownerOf(faceId: Long): Owner? = owners[faceId]

        // cesta fotky → osoby na nej (ručne aj automaticky; neisté nie)
        val photoPersons: Map<String, Set<Long>> by lazy {
            val m = HashMap<String, HashSet<Long>>()
            for (p in persons) {
                val pid = p.id ?: continue
                for (f in p.faces) m.getOrPut(f.mediaFullPath) { HashSet() }.add(pid)
            }
            m
        }

        // osoby na jednej fotke so stavom (pre info v prehliadači), bez neistých
        fun personsOnPhoto(path: String): List<Pair<Person, State>> = persons.mapNotNull { p ->
            val onPhoto = p.faces.filter { it.mediaFullPath == path }
            if (onPhoto.isEmpty()) return@mapNotNull null
            val manual = onPhoto.any { f -> f.id?.let { p.manualFaceIds.contains(it) } == true }
            p to if (manual) State.MANUAL else State.AUTO
        }
    }

    private val version = AtomicLong()
    private val observing = AtomicBoolean(false)
    private val lock = Any()
    private var cached: Snapshot? = null
    private var cachedVersion = -1L

    // osoby sa zmenili (zápis z UI) — ďalšie get() prepočíta
    fun invalidate() {
        version.incrementAndGet()
    }

    // onTrimMemory: snímok (osoby so všetkými tvárami, k-NN model) z pamäte preč; ďalšie get() ho
    // na pozadí znova vypočíta. Pod tým istým zámkom ako get(), takže nikomu „nezmizne pod rukami“
    // (kto už snímok má, používa svoj odkaz ďalej). Volať z pozadia (get() môže držať zámok dlhšie).
    fun release() {
        synchronized(lock) {
            cached = null
            cachedVersion = -1L
        }
    }

    // aktuálny snímok bez prepočtu (null = treba get() na pozadí)
    fun peek(context: Context): Snapshot? = synchronized(lock) {
        cached?.takeIf { cachedVersion == version.get() && it.threshold == FaceAuto.threshold(context) }
    }

    @WorkerThread
    fun get(context: Context): Snapshot {
        val app = context.applicationContext
        observe(app)
        val threshold = FaceAuto.threshold(app)
        synchronized(lock) {
            val v = version.get()
            cached?.let { if (cachedVersion == v && it.threshold == threshold) return it }
            val snapshot = build(app, threshold)
            cached = snapshot
            cachedVersion = v
            return snapshot
        }
    }

    private val tracked = object : InvalidationTracker.Observer(
        arrayOf("persons", "face_assignments", "cannot_links", "anchor_embeddings"),
    ) {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }

    private val trackedExtras = object : InvalidationTracker.Observer(arrayOf("ignored_faces")) {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }

    private val trackedFaces = object : InvalidationTracker.Observer(arrayOf("faces")) {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }

    @WorkerThread
    private fun observe(app: Context) {
        if (!observing.compareAndSet(false, true)) return
        try {
            PeopleDatabase.getInstance(app).invalidationTracker.addObserver(tracked)
            ExtrasDatabase.getInstance(app).invalidationTracker.addObserver(trackedExtras)
            FacesDatabase.getInstance(app).invalidationTracker.addObserver(trackedFaces)
        } catch (ignored: SQLException) {
            notObserving()
        } catch (ignored: IllegalStateException) {
            notObserving()
        } catch (ignored: IllegalArgumentException) {
            notObserving()
        }
    }

    // DB sa nedá otvoriť — skúsi sa pri ďalšom get(); kým nie je tracker, cache drží len invalidate()
    private fun notObserving() {
        observing.set(false)
        invalidate()
    }

    @WorkerThread
    private fun build(app: Context, threshold: Float): Snapshot {
        val facesDao = FacesDatabase.getInstance(app).FaceDao()
        val peopleDao = PeopleDatabase.getInstance(app).PeopleDao()
        val allFaces = facesDao.getAllFaces()
        // základ = priradené tváre BEZ filtra skóre/veľkosti — ručné potvrdenie má prednosť (C5)
        val confirmed = PersonGrouper.confirmedPersons(allFaces, peopleDao.getPersons(), peopleDao.getAssignments())
        // ignorované tváre (extras.db) sa nezaraďujú (C1) — bez ich zoznamu radšej nič automaticky
        val ignored = try {
            ExtrasDatabase.getInstance(app).ExtrasDao().getIgnoredIds().toHashSet()
        } catch (ignored: SQLException) {
            null
        } catch (ignored: IllegalStateException) {
            null
        } ?: return Snapshot(confirmed, emptyMap(), emptyList(), null, threshold, manualOwners(confirmed))
        val anchors = PersonGrouper.splitAnchors(peopleDao.getAllAnchors()) { FaceEmbedder.toFloats(it) }
        val links = peopleDao.getCannotLinks()
        val byId = HashMap<Long, FaceEntity>(allFaces.size * 2)
        allFaces.forEach { f -> f.id?.let { byId[it] = f } }
        // automatika a neisté len z „dobrých" tvárí; odmietnuté tváre (C8) sa berú všetky
        val pool = allFaces.filter { FaceFilter.isGood(it) }
        val c = PersonGrouper.classify(
            confirmed, pool, anchors, links, ignored, threshold, FaceAuto.MARGIN, negativeFaces = byId,
        )
        val owners = manualOwners(confirmed)
        c.autoOwner.forEach { (faceId, pid) -> owners[faceId] = Owner(pid, State.AUTO) }
        c.uncertain.forEach { (pid, list) ->
            list.forEach { s -> s.face.id?.let { owners.putIfAbsent(it, Owner(pid, State.UNCERTAIN)) } }
        }
        return Snapshot(c.persons, c.uncertain, c.contested, c.model, threshold, owners)
    }

    private fun manualOwners(persons: List<Person>): HashMap<Long, Owner> {
        val owners = HashMap<Long, Owner>()
        for (p in persons) {
            val pid = p.id ?: continue
            p.manualFaceIds.forEach { owners[it] = Owner(pid, State.MANUAL) }
        }
        return owners
    }
}
