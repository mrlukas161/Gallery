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
class FamilySync(context: Context) {
    private val app = context.applicationContext
    private val family = FamilyDatabase.getInstance(app)

    // vlastné deviceId (vznikne raz pri prvom použití)
    fun deviceId(): String = family.metaDao().deviceId()

    // Vlastné operácie s lamport > sinceLamport, zoradené, najviac `limit` (ďalšia strana: since = posledný lamport).
    // Pri prvom volaní po spárovaní zverejní doterajší stav (snapshotIfNeeded), potom vždy doplní
    // odtlačky (AnchorsSet) osôb, ktorých tváre sa medzičasom zmenili.
    fun opsSince(sinceLamport: Long, limit: Int = PAGE_SIZE): List<OpEntity> {
        try {
            snapshotIfNeeded()
            OpRecorder(app).anchors.flush()
        } catch (ignored: RuntimeException) {
            // odtlačky sú vedľajšie, operácie sa odovzdajú aj bez nich
        }
        return family.opDao().getOpsSincePage(deviceId(), sinceLamport, limit).map { it.copy(appliedAt = null) }
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
    fun retryPending(): ApplyReport {
        val local = FamilyLocal(app)
        if (local.store.pending().any { it.op is AssignFace || it.op is UnassignFace }) prepareFaceKeys()
        return OpApplier(local.ownDeviceId, FaceEmbedder.MODEL_ID)
            .process(emptyList(), local.store, local.state, local.sink)
    }

    // Po spárovaní raz zverejní doterajší stav Ľudí (osoby, ručné priradenia, skupiny, odtlačky).
    // Vráti false, keď už prebehol alebo nie je spárovaný žiadny telefón.
    fun snapshotIfNeeded(): Boolean {
        val meta = family.metaDao()
        if (meta.get(KEY_SNAPSHOT_DONE) != null) return false
        val recorder = OpRecorder(app)
        if (!recorder.hasPeers()) return false
        val people = PeopleDatabase.getInstance(app).PeopleDao()
        val persons = people.getPersons()
        persons.forEach { recorder.uuidFor(it.id) }
        people.getAssignments().filter { it.isManual }.groupBy({ it.personId }, { it.faceId })
            .forEach { (personId, faceIds) -> recorder.facesAssigned(faceIds, personId) }
        ExtrasDatabase.getInstance(app).ExtrasDao().getGroups().forEach { recorder.groupChanged(it.id) }
        recorder.anchors.markDirty(persons.map { it.id })
        meta.put(MetaEntity(KEY_SNAPSHOT_DONE, System.currentTimeMillis().toString()))
        return true
    }

    fun openProposalCount(): Int = family.proposalDao().countProposals(ProposalEntity.STATE_OPEN)

    // Obsahové kľúče fotiek s tvárami (lenivo: len keď čaká operácia s tvárou; neskôr sa vráti z cache).
    private fun prepareFaceKeys() {
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
