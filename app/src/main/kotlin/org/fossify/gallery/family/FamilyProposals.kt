package org.fossify.gallery.family

import android.content.Context
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceAssignmentEntity
import org.fossify.gallery.faces.PeopleDatabase

// Rozhodnutia o návrhoch z druhého telefónu (lišta a zoznam v Ľuďoch). Volá sa len z pozadia.
// Do people.db/extras.db zapisuje len cez existujúce DAO metódy a len na výslovné rozhodnutie
// používateľa (zmazanie osoby až po ConfirmationDialog v UI).
class FamilyProposals(context: Context) {
    private val app = context.applicationContext
    private val family = FamilyDatabase.getInstance(app)
    private val people = PeopleDatabase.getInstance(app).PeopleDao()
    private val extras = ExtrasDatabase.getInstance(app).ExtrasDao()

    data class Item(
        val id: Long,
        val proposal: Proposal,
        val fromDeviceId: String,
        val phoneName: String?, // null = telefón už nie je spárovaný
        val createdAt: Long,
    )

    // Otvorené návrhy, ktoré sa dajú zobraziť (nepoškodené); najstaršie prvé.
    fun openItems(): List<Item> {
        val names = HashMap<String, String?>(family.peerDao().getPeers().associate { it.deviceId to it.name })
        return family.proposalDao().getProposals(ProposalEntity.STATE_OPEN).mapNotNull { e ->
            val proposal = OpCodec.decodeProposal(e.type, e.payloadJson) ?: return@mapNotNull null
            // návrhy z obnovy zálohy osôb (F6) nesú popis zálohy namiesto názvu telefónu
            if (PeopleBackupFormat.isBackupDevice(e.fromDeviceId) && !names.containsKey(e.fromDeviceId)) {
                names[e.fromDeviceId] = family.metaDao().get(PeopleBackup.labelKey(e.fromDeviceId))
            }
            Item(e.id, proposal, e.fromDeviceId, names[e.fromDeviceId], e.createdAt)
        }
    }

    // mená lokálnych osôb pre texty návrhov
    fun personNames(): Map<Long, String?> = people.getPersons().associate { it.id to it.name }

    // „Prijať / Prevziať / Zlúčiť / Zmazať aj tu“
    fun accept(item: Item) {
        val persons = personNames()
        when (val p = item.proposal) {
            is RenameProposal -> if (persons.containsKey(p.localId)) {
                people.renamePerson(p.localId, p.theirName)
                // D8: rozhodnutie je nová zmena mena → obe strany dôjdu k rovnakému stavu
                recordRename(p.localId, RenameResolution.onAccept(p))
            }
            is SameNameProposal -> linkSameName(p, persons)
            is MergeProposal -> if (persons.containsKey(p.fromLocalId) && persons.containsKey(p.toLocalId)) {
                people.mergeManyInto(listOf(p.fromLocalId), p.toLocalId)
                extras.moveMemberships(p.fromLocalId, p.toLocalId)
                // vlastné zlúčenie = alias UUID (a druhý telefón sa dozvie, že sme to prijali)
                OpRecorder(app).append(PersonMerge(p.fromUuid, p.toUuid))
            }

            is FaceMoveProposal -> if (persons.containsKey(p.toLocalId)) {
                people.upsertAssignment(FaceAssignmentEntity(p.faceId, p.toLocalId, true, System.currentTimeMillis()))
            }

            is FaceRemoveProposal -> people.rejectFacesForPerson(listOf(p.faceId), p.localId)
            is DeleteProposal -> if (persons.containsKey(p.localId)) {
                people.deletePersonsCompletely(listOf(p.localId))
                extras.removePersonFromGroups(p.localId)
            }
        }
        close(item, ProposalEntity.STATE_ACCEPTED)
    }

    // „Ponechať / Ponechať oddelene“
    fun keep(item: Item) = keepAll(listOf(item))

    fun keepAll(items: List<Item>) {
        val sink = FamilyLocal(app).sink
        items.forEach { item ->
            val p = item.proposal
            if (p is SameNameProposal) {
                // ich osoba vznikne tu ako samostatná (s rovnakým menom)
                sink.createPerson(ApplyAction.CreatePerson(p.uuid, p.name), null)
            }
            // D8: „ponechať“ moje meno je tiež novšia zmena — inak by telefóny ostali s rôznymi menami
            if (p is RenameProposal && !PeopleBackupFormat.isBackupDevice(item.fromDeviceId)) {
                RenameResolution.onKeep(p)?.let { recordRename(p.localId, it) }
            }
            family.proposalDao().setProposalState(item.id, ProposalEntity.STATE_REJECTED)
        }
        retry()
    }

    private fun recordRename(localId: Long, op: PersonRename) {
        OpRecorder(app).use { it.personRenamed(localId, op.previousName, op.name) }
    }

    private fun linkSameName(p: SameNameProposal, persons: Map<Long, String?>) {
        if (!persons.containsKey(p.localId)) return
        val existing = family.familyPersonDao().getPersonByLocalId(p.localId)
        if (existing == null) {
            family.familyPersonDao().upsertPerson(FamilyPersonEntity(p.uuid, p.localId))
        } else if (existing.familyUuid != p.uuid) {
            // lokálna osoba už má vlastné rodinné UUID → ich UUID sa naň prepojí ako zlúčenie
            OpRecorder(app).append(PersonMerge(p.uuid, existing.familyUuid))
        }
    }

    private fun close(item: Item, state: String) {
        family.proposalDao().setProposalState(item.id, state)
        retry()
    }

    // rozhodnutie mohlo odblokovať odložené operácie (napr. priradenia osoby s rovnakým menom)
    private fun retry() {
        try {
            FamilySync(app).retryPending()
        } catch (ignored: RuntimeException) {
        }
    }
}
