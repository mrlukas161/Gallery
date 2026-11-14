package org.fossify.gallery.family

import android.content.Context
import android.util.Log
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

// Verejné API op-logu pre prenos (F2: FamilyServer/FamilyClient) a balíky fotiek (F4).
// Všetky metódy čítajú/zapisujú databázy — volaj ich LEN z pozadia (nikdy z hlavného vlákna).
//
// Typický priebeh synchronizácie s druhým telefónom (F2):
//   FamilySnapshot.start(context)                            // raz po spárovaní, na pozadí s priebehom
//   val mine = sync.opsSince(peer.lastLamportSeen)           // moje zmeny pre druhú stranu (stránkovane)
//   val report = sync.receive(theirOps)                      // ich zmeny: potichu / návrh / počká
//   peerDao.markPeerSeen(peer.deviceId, now, maxOf(peer.lastLamportSeen, theirOps.maxOf { it.lamport }))
// Prenáša sa OpEntity tak, ako je (opId, deviceId, lamport, type, payloadJson); appliedAt sa neposiela.
//
// Operácie aplikuje vždy len jedno vlákno naraz (FamilyLock): vlákno servera, klient, rozhodnutia o návrhoch
// aj obnova zálohy osôb sa striedajú, inak by tá istá osoba vznikla dvakrát.
@Suppress("TooManyFunctions")
class FamilySync(context: Context) {
    private val app = context.applicationContext
    private val family = FamilyDatabase.getInstance(app)

    // vlastné deviceId (vznikne raz pri prvom použití)
    fun deviceId(): String = family.metaDao().deviceId()

    // Vlastné operácie s lamport > sinceLamport, zoradené, najviac `limit` (ďalšia strana: since = posledný lamport).
    fun opsSince(sinceLamport: Long, limit: Int = PAGE_SIZE): List<OpEntity> {
        prepareOwnOps()
        return family.opDao().getOpsSincePage(deviceId(), sinceLamport, limit).map { it.copy(appliedAt = null) }
    }

    // Pred odoslaním vlastných opov (aj z prenosu F2 cez FamilyHandler.beforeOwnOps): odtlačky osôb, ktorých
    // tváre sa zmenili. Snapshot po spárovaní sa tu NEROBÍ — pri veľkej knižnici trvá dlho a v obsluhe požiadavky
    // by klient prekročil časový limit; spustí sa na pozadí (FamilySnapshot) a jeho operácie odídu hneď potom.
    fun prepareOwnOps() {
        try {
            if (!isSnapshotDone() && family.peerDao().getPeers().isNotEmpty()) FamilySnapshot.start(app)
            if (FamilySnapshot.isRunning()) return // odtlačky odídu po snapshote (označí všetky osoby)
            // OpRecorder je Closeable (overovač priestoru tvárí drží modely) — zavrieť aj pri chybe
            OpRecorder(app).use { it.anchors.flush() }
        } catch (ignored: RuntimeException) {
            // odtlačky sú vedľajšie, operácie sa odovzdajú aj bez nich
        }
    }

    // Prijme operácie druhého telefónu. Opakované doručenie (rovnaké opId) a vlastné operácie sa ignorujú,
    // každá operácia sa aplikuje práve raz. Operácie, ktoré ešte nemôžu prebehnúť (osoba alebo fotka
    // chýba), ostanú v op-logu a skúsia sa pri ďalšom príjme alebo cez retryPending().
    fun receive(ops: List<OpEntity>): ApplyReport {
        val own = deviceId()
        var duplicates = 0
        for (e in ops) {
            val fresh = e.deviceId != own && family.opDao().insertOp(e.copy(appliedAt = null)) != -1L
            if (!fresh) duplicates++
        }
        // lamport hodiny: moje ďalšie operácie budú „neskôr“ než všetko prijaté
        ops.maxOfOrNull { it.lamport }?.let { family.metaDao().nextLamport(it) }
        val report = retryPending()
        return report.copy(duplicates = report.duplicates + duplicates)
    }

    // Znova skúsi odložené operácie (napr. po prijatí fotky v F4 alebo po rozhodnutí o návrhu).
    // D9: operácie s tvárou čakajú na obsahový kľúč svojej fotky; kľúče knižnice sa preto prepočítajú (lenivo,
    // najviac raz za KEY_PASS_INTERVAL_MS) len vtedy, keď niečo na fotku čaká. withKeys = false: kľúč, na ktorý
    // sa čaká, je už známy (prijatá fotka zapísaná v bundle_inbox) — knižnica sa neprechádza.
    fun retryPending(withKeys: Boolean = true): ApplyReport {
        if (withKeys && family.opDao().countFaceOpsWaiting() > 0) prepareFaceKeys()
        return familyExclusive {
            val local = FamilyLocal(app)
            OpApplier(local.ownDeviceId, FaceEmbedder.MODEL_ID)
                .process(emptyList(), local.store, local.state, local.sink)
        }
    }

    fun isSnapshotDone(): Boolean = family.metaDao().get(KEY_SNAPSHOT_DONE) != null

    // nové spárovanie (alebo zrušenie posledného prepojenia): snapshot prebehne znova, aby druhý telefón dostal
    // aj zmeny z času, keď nebol spárovaný žiadny telefón a recorder nič nezapisoval
    fun resetSnapshot() {
        family.metaDao().delete(KEY_SNAPSHOT_DONE)
        family.metaDao().delete(KEY_SNAPSHOT_PROGRESS)
    }

    // Po spárovaní raz zverejní doterajší stav Ľudí (osoby, ručné priradenia, skupiny, odtlačky).
    // Vráti false, keď už prebehol, práve beží inde alebo nie je spárovaný žiadny telefón.
    // progress(hotové osoby, všetky osoby). Prerušený snapshot pokračuje od ďalšej osoby (bez dvojitého zápisu).
    fun snapshotIfNeeded(progress: (Int, Int) -> Unit = { _, _ -> }): Boolean {
        if (isSnapshotDone() || !snapshotLock.tryLock()) return false
        return try {
            !isSnapshotDone() && OpRecorder(app).use { snapshot(it, progress) }
        } finally {
            snapshotLock.unlock()
        }
    }

    private fun snapshot(recorder: OpRecorder, progress: (Int, Int) -> Unit): Boolean {
        val meta = family.metaDao()
        if (!recorder.hasPeers()) return false
        val people = PeopleDatabase.getInstance(app).PeopleDao()
        val persons = people.getPersons()
        val manual = people.getAssignments().filter { it.isManual }.groupBy({ it.personId }, { it.faceId })
        val done = PublishRules.decodeSet(meta.get(KEY_SNAPSHOT_PROGRESS)).toHashSet()
        persons.forEachIndexed { i, person ->
            val key = person.id.toString()
            if (key !in done) {
                // osoba sa zverejní len s tvárou z povoleného priečinka (spec 2.5, PublishRules)
                recorder.publishedUuid(person.id)
                manual[person.id]?.let { recorder.facesAssigned(it, person.id) }
                done.add(key)
                meta.put(MetaEntity(KEY_SNAPSHOT_PROGRESS, PublishRules.encodeSet(done)))
            }
            progress(i + 1, persons.size)
        }
        catchUpNames(recorder, persons.map { it.id to it.name })
        ExtrasDatabase.getInstance(app).ExtrasDao().getGroups().forEach { recorder.groupChanged(it.id) }
        recorder.anchors.markDirty(persons.map { it.id })
        meta.put(MetaEntity(KEY_SNAPSHOT_DONE, System.currentTimeMillis().toString()))
        meta.delete(KEY_SNAPSHOT_PROGRESS)
        return true
    }

    // čakajú v op-logu operácie, ktoré ešte nemohli prebehnúť (napr. z obnovy zálohy, kým chýbajú fotky)?
    fun hasPendingOps(): Boolean = family.opDao().countPendingOps(OpApplier.modelWait(FaceEmbedder.MODEL_ID)) > 0

    fun openProposalCount(): Int = family.proposalDao().countProposals(ProposalEntity.STATE_OPEN)

    // Osoba mohla dostať rodinné UUID ešte pred spárovaním (záloha osôb F6) a potom ju premenovali, kým
    // recorder nič nezapisoval (žiadny spárovaný telefón). Snapshot preto dorovná meno z posledného
    // vlastného PersonCreate/PersonRename na aktuálne.
    private fun catchUpNames(recorder: OpRecorder, persons: List<Pair<Long, String?>>) {
        val self = deviceId()
        val logged = HashMap<String, String?>()
        val ops = family.opDao().getOpsOfType(self, OpType.PERSON_CREATE) +
            family.opDao().getOpsOfType(self, OpType.PERSON_RENAME)
        ops.sortedWith(compareBy<OpEntity> { it.lamport }.thenBy { it.opId }).forEach { e ->
            when (val op = OpCodec.decode(e.type, e.payloadJson)) {
                is PersonCreate -> logged[op.uuid] = op.name
                is PersonRename -> logged[op.uuid] = op.name
                else -> Unit
            }
        }
        for ((localId, name) in persons) {
            val uuid = recorder.existingUuid(localId)?.takeIf { logged.containsKey(it) }
            if (uuid != null && !name.isNullOrBlank() && logged[uuid] != name) {
                recorder.personRenamed(localId, logged[uuid], name)
            }
        }
    }

    // Obsahové kľúče fotiek s tvárami (lenivo: len keď čaká operácia s tvárou; neskôr sa vráti z cache).
    // Ľahký telefón knižnicu neprechádza pri každom príjme: najviac raz za KEY_PASS_INTERVAL_MS (force = obnova
    // zálohy osôb, ktorá potrebuje všetky kľúče hneď).
    internal fun prepareFaceKeys(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastKeyPassAt in 0 until KEY_PASS_INTERVAL_MS) return
        lastKeyPassAt = now
        val keys = ContentKeyStore(family.contentKeyDao())
        FacesDatabase.getInstance(app).FaceDao().getPathsWithFaces().forEach { path ->
            if (File(path).isFile) keys.keyFor(path)
        }
    }

    companion object {
        const val PAGE_SIZE = 500
        private const val KEY_SNAPSHOT_DONE = "f3_snapshot_done"
        private const val KEY_SNAPSHOT_PROGRESS = "f068_snapshot_progress"
        private const val KEY_PASS_INTERVAL_MS = 10 * 60 * 1000L
        private val snapshotLock = ReentrantLock()

        @Volatile
        private var lastKeyPassAt = 0L
    }
}

// Prvý snapshot Ľudí po spárovaní ako samostatná úloha na pozadí (nie v obsluhe požiadavky servera), s priebehom
// pre obrazovku párovania. Po dobehnutí sa hneď skúsi synchronizácia, aby operácie odišli. Nič nebeží trvalo:
// vlákno pracuje len počas snapshotu.
object FamilySnapshot {
    private const val TAG = "FamilySnapshot"
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    // (hotové, všetky) počas behu, inak null
    @Volatile
    var progress: Pair<Int, Int>? = null
        private set

    fun isRunning(): Boolean = running.get()

    @Suppress("TooGenericExceptionCaught")
    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        try {
            worker.execute {
                var made = false
                try {
                    made = FamilySync(app).snapshotIfNeeded { done, total -> progress = done to total }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "snapshot Ľudí zlyhal", e)
                } finally {
                    progress = null
                    running.set(false)
                }
                // operácie snapshotu hneď odídu (druhý telefón práve čaká na Ľudí)
                if (made) FamilyAutoSync.syncSoon(app)
            }
        } catch (ignored: RejectedExecutionException) {
            running.set(false)
        }
    }
}
