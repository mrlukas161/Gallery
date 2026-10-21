package org.fossify.gallery.family

import android.content.Context
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import java.io.File

// Verejné API op-logu pre prenos (F2: FamilyServer/FamilyClient) a balíky fotiek (F4).
// Všetky metódy čítajú/zapisujú databázy — volaj ich LEN z pozadia (nikdy z hlavného vlákna).
//
// Typický priebeh synchronizácie s druhým telefónom (F2):
//   val sync = FamilySync(context)
//   sync.snapshotIfNeeded()                                  // raz po spárovaní
//   val mine = sync.opsSince(peer.lastLamportSeen)           // moje zmeny pre druhú stranu (stránkovane)
//   val report = sync.receive(theirOps)                      // ich zmeny: potichu / návrh / počká
//   peerDao.markPeerSeen(peer.deviceId, now, maxOf(peer.lastLamportSeen, theirOps.maxOf { it.lamport }))
// Prenáša sa OpEntity tak, ako je (opId, deviceId, lamport, type, payloadJson); appliedAt sa neposiela.
@Suppress("TooManyFunctions")
class FamilySync(context: Context) {
    private val app = context.applicationContext
    private val family = FamilyDatabase.getInstance(app)

    // vlastné deviceId (vznikne raz pri prvom použití)
    fun deviceId(): String = family.metaDao().deviceId()

    // Vlastné operácie s lamport > sinceLamport, zoradené, najviac `limit` (ďalšia strana: since = posledný lamport).
    // Pri prvom volaní po spárovaní zverejní doterajší stav (snapshotIfNeeded), potom vždy doplní
    // odtlačky (AnchorsSet) osôb, ktorých tváre sa medzičasom zmenili.
    fun opsSince(sinceLamport: Long, limit: Int = PAGE_SIZE): List<OpEntity> {
        prepareOwnOps()
        return family.opDao().getOpsSincePage(deviceId(), sinceLamport, limit).map { it.copy(appliedAt = null) }
    }

    // Pred odoslaním vlastných opov (aj z prenosu F2 cez FamilyHandler.beforeOwnOps): snapshot po
    // spárovaní a odtlačky osôb, ktorých tváre sa zmenili.
    fun prepareOwnOps() {
        try {
            snapshotIfNeeded()
            OpRecorder(app).anchors.flush()
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
    // potom z cache) len vtedy, keď niečo na fotku čaká.
    fun retryPending(): ApplyReport {
        val local = FamilyLocal(app)
        if (family.opDao().countFaceOpsWaiting() > 0) prepareFaceKeys()
        return OpApplier(local.ownDeviceId, FaceEmbedder.MODEL_ID)
            .process(emptyList(), local.store, local.state, local.sink)
    }

    // Po spárovaní raz zverejní doterajší stav Ľudí (osoby, ručné priradenia, skupiny, odtlačky).
    // Vráti false, keď už prebehol alebo nie je spárovaný žiadny telefón.
    fun snapshotIfNeeded(): Boolean {
        val meta = family.metaDao()
        if (meta.get(KEY_SNAPSHOT_DONE) != null) return false
        return OpRecorder(app).use { snapshot(it) }
    }

    private fun snapshot(recorder: OpRecorder): Boolean {
        val meta = family.metaDao()
        if (!recorder.hasPeers()) return false
        val people = PeopleDatabase.getInstance(app).PeopleDao()
        val persons = people.getPersons()
        persons.forEach { recorder.uuidFor(it.id) }
        catchUpNames(recorder, persons.map { it.id to it.name })
        people.getAssignments().filter { it.isManual }.groupBy({ it.personId }, { it.faceId })
            .forEach { (personId, faceIds) -> recorder.facesAssigned(faceIds, personId) }
        ExtrasDatabase.getInstance(app).ExtrasDao().getGroups().forEach { recorder.groupChanged(it.id) }
        recorder.anchors.markDirty(persons.map { it.id })
        meta.put(MetaEntity(KEY_SNAPSHOT_DONE, System.currentTimeMillis().toString()))
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
    internal fun prepareFaceKeys() {
        val keys = ContentKeyStore(family.contentKeyDao())
        FacesDatabase.getInstance(app).FaceDao().getPathsWithFaces().forEach { path ->
            if (File(path).isFile) keys.keyFor(path)
        }
    }

    companion object {
        const val PAGE_SIZE = 500
        private const val KEY_SNAPSHOT_DONE = "f3_snapshot_done"
    }
}
