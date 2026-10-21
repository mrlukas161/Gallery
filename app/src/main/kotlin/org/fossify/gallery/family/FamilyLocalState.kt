package org.fossify.gallery.family

import android.content.Context
import org.fossify.gallery.faces.AnchorEmbeddingEntity
import org.fossify.gallery.faces.ExtrasDao
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceAssignmentEntity
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.GroupMemberEntity
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PersonEntity
import org.fossify.gallery.faces.PersonGroupEntity

// Napojenie OpApplier na skutočné databázy (jedna inštancia na jeden príjem, len z pozadia):
//  - LocalFamilyState číta people.db, extras.db, faces.db a mapu UUID ↔ id vo family_persons;
//    vlastné zlúčenia (PersonMerge z tohto telefónu) slúžia ako aliasy rodinných UUID;
//  - LocalOpStore je op-log vo family.db;
//  - LocalActionSink zapisuje do people.db a extras.db VÝHRADNE cez existujúce DAO metódy a nič v nich
//    nemaže (jediné „odobratie“ je rejectFacesForPerson pri automatickom priradení, ktoré druhá strana
//    ručne odmietla).
class FamilyLocal(context: Context, clock: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext
    val state = LocalFamilyState(app)
    val store = LocalOpStore(FamilyDatabase.getInstance(app).opDao(), clock)
    val sink = LocalActionSink(app, state, clock)
    val ownDeviceId: String by lazy { FamilyDatabase.getInstance(app).metaDao().deviceId() }
}

class LocalFamilyState(context: Context) : FamilyState {
    private val family = FamilyDatabase.getInstance(context)
    private val people = PeopleDatabase.getInstance(context).PeopleDao()
    private val extras = ExtrasDatabase.getInstance(context).ExtrasDao()
    private val faces = FamilyFaces(
        context,
        FacesDatabase.getInstance(context).FaceDao(),
        ContentKeyStore(family.contentKeyDao()),
    )
    private val familyPersons = family.familyPersonDao()

    private var personsCache: Map<Long, String?>? = null
    private var assignmentsCache: MutableMap<Long, LocalAssignment>? = null
    private var aliasesCache: Map<String, String>? = null

    private val persons: Map<Long, String?>
        get() = personsCache ?: people.getPersons().associate { it.id to it.name }.also { personsCache = it }

    private val assignments: MutableMap<Long, LocalAssignment>
        get() = assignmentsCache ?: people.getAssignments()
            .associateTo(HashMap()) { it.faceId to LocalAssignment(it.personId, it.isManual) }
            .also { assignmentsCache = it }

    // vlastné zlúčenia: fromUuid → toUuid
    private val aliases: Map<String, String>
        get() = aliasesCache ?: family.opDao().getOpsOfType(family.metaDao().deviceId(), OpType.PERSON_MERGE)
            .mapNotNull { OpCodec.decode(it.type, it.payloadJson) as? PersonMerge }
            .associate { it.fromUuid to it.toUuid }
            .also { aliasesCache = it }

    // po zápise osôb alebo vlastného zlúčenia (LocalActionSink)
    fun onPersonsChanged() {
        personsCache = null
        aliasesCache = null
    }

    fun onAssignmentChanged(faceId: Long, value: LocalAssignment?) {
        val cache = assignmentsCache ?: return
        if (value == null) cache.remove(faceId) else cache[faceId] = value
    }

    override fun localPersonId(uuid: String): Long? {
        var current = uuid
        repeat(MAX_ALIAS_HOPS) {
            val local = familyPersons.getPersonByUuid(current)?.localPersonId
            if (local != null && persons.containsKey(local)) return local
            current = aliases[current] ?: return null
        }
        return null
    }

    override fun isKnown(uuid: String): Boolean =
        familyPersons.getPersonByUuid(uuid) != null || aliases.containsKey(uuid)

    override fun personName(localId: Long): String? = persons[localId]

    override fun personByName(name: String): Long? {
        val wanted = name.trim()
        return persons.entries.firstOrNull { it.value?.trim().equals(wanted, ignoreCase = true) }?.key
    }

    override fun findFace(ref: FaceRef): Long? = faces.findFace(ref)

    override fun assignment(faceId: Long): LocalAssignment? = assignments[faceId]

    override fun groupMembers(name: String): Set<Long>? =
        extras.groupByName(name)?.let { extras.getMembers(it.id).toSet() }

    override fun anchors(localId: Long, source: String): List<FloatArray> =
        people.getAllAnchors().filter { it.personId == localId && it.source == source }
            .map { FaceEmbedder.toFloats(it.embedding) }

    companion object {
        private const val MAX_ALIAS_HOPS = 8
    }
}

class LocalOpStore(private val dao: OpDao, private val clock: () -> Long) : OpStore {
    override fun store(op: LoggedOp): Boolean =
        dao.insertOp(OpEntity(op.opId, op.deviceId, op.lamport, op.op.type, OpCodec.encode(op.op))) != -1L

    // neznáme typy (novšia appka) a poškodené payloady ostanú v op-logu neaplikované
    override fun pending(): List<LoggedOp> = dao.getUnappliedOps().mapNotNull { e ->
        OpCodec.decode(e.type, e.payloadJson)?.let { LoggedOp(e.opId, e.deviceId, e.lamport, it) }
    }

    override fun markApplied(opId: String) = dao.markOpApplied(opId, clock())
}

class LocalActionSink(context: Context, private val state: LocalFamilyState, private val clock: () -> Long) :
    ActionSink {
    private val family = FamilyDatabase.getInstance(context)
    private val people = PeopleDatabase.getInstance(context).PeopleDao()
    private val extras = ExtrasDatabase.getInstance(context).ExtrasDao()

    override fun execute(action: ApplyAction, op: LoggedOp) {
        when (action) {
            is ApplyAction.CreatePerson -> createPerson(action, op)
            is ApplyAction.RenamePerson -> renamePerson(action, op)
            is ApplyAction.Assign -> {
                people.upsertAssignment(FaceAssignmentEntity(action.faceId, action.localId, action.manual, clock()))
                state.onAssignmentChanged(action.faceId, LocalAssignment(action.localId, action.manual))
            }

            is ApplyAction.Reject -> {
                people.rejectFacesForPerson(listOf(action.faceId), action.localId)
                state.onAssignmentChanged(action.faceId, null)
            }

            is ApplyAction.CreateGroup, is ApplyAction.AddGroupMembers, is ApplyAction.RenameGroup -> group(action)
            is ApplyAction.AddAnchors -> action.vectors.forEach {
                val bytes = FaceEmbedder.toBytes(it)
                people.insertAnchor(AnchorEmbeddingEntity(0, action.localId, bytes, action.source, clock()))
            }

            is ApplyAction.Propose -> propose(action, op)
        }
    }

    // A7: návrh sa pri opakovanom aplikovaní tej istej operácie (pád pred markApplied) nezdvojí —
    // kľúčom je opId, zapísaný v tej istej transakcii family.db ako návrh
    private fun propose(action: ApplyAction.Propose, op: LoggedOp) {
        val key = PROPOSAL_OP_PREFIX + op.opId
        family.runInTransaction(Runnable {
            if (family.metaDao().get(key) == null) {
                family.proposalDao().insertProposal(
                    ProposalEntity(
                        fromDeviceId = op.deviceId,
                        type = action.proposal.type,
                        payloadJson = OpCodec.encodeProposal(action.proposal),
                        createdAt = clock(),
                    )
                )
                family.metaDao().put(MetaEntity(key, op.lamport.toString()))
            }
        })
    }

    // nová osoba z druhého telefónu (aj „ponechať oddelene“ pri rovnakom mene, vtedy op = null)
    //
    // A7: osoba (people.db) a mapa UUID (family.db) sú v dvoch DB, spolu sa atomicky zapísať nedajú.
    // Preto sa najprv do family.db zapíše „rozpracované“ UUID s časom vzniku, potom osoba s tým istým
    // časom a až nakoniec mapa. Keď appka spadne medzi krokmi, opakované aplikovanie osobu nájde
    // (rovnaký čas vzniku a meno, zatiaľ bez mapy) a len doplní mapu — duplicitná osoba nevznikne.
    fun createPerson(action: ApplyAction.CreatePerson, op: LoggedOp?) {
        if (state.localPersonId(action.uuid) != null) return // opakované aplikovanie po páde
        val meta = family.metaDao()
        val key = PENDING_PERSON_PREFIX + action.uuid
        val pendingAt = meta.get(key)?.toLongOrNull()
        val id = pendingAt?.let { unmappedPerson(it, action.name) } ?: run {
            val createdAt = pendingAt ?: clock().also { meta.put(MetaEntity(key, it.toString())) }
            people.insertPerson(PersonEntity(name = action.name, createdAt = createdAt))
        }
        family.familyPersonDao().upsertPerson(FamilyPersonEntity(action.uuid, id, op?.lamport ?: 0, op?.deviceId))
        meta.delete(key)
        state.onPersonsChanged()
    }

    // osoba vytvorená pred pádom: rovnaký čas vzniku a meno, bez rodinného UUID
    private fun unmappedPerson(createdAt: Long, name: String?): Long? = people.getPersons()
        .filter { it.createdAt == createdAt && it.name == name }
        .firstOrNull { family.familyPersonDao().getPersonByLocalId(it.id) == null }
        ?.id

    private fun renamePerson(action: ApplyAction.RenamePerson, op: LoggedOp) {
        people.renamePerson(action.localId, action.name)
        val dao = family.familyPersonDao()
        dao.getPersonByUuid(action.uuid)?.let {
            dao.upsertPerson(it.copy(nameVersionLamport = op.lamport, nameDeviceId = op.deviceId))
        }
        state.onPersonsChanged()
    }

    private fun group(action: ApplyAction) {
        when (action) {
            is ApplyAction.CreateGroup -> {
                val gid = extras.insertGroup(PersonGroupEntity(name = action.name, createdAt = clock()))
                action.members.forEach { extras.addMember(GroupMemberEntity(gid, it)) }
            }

            is ApplyAction.AddGroupMembers -> extras.groupByName(action.name)?.let { g ->
                action.members.forEach { extras.addMember(GroupMemberEntity(g.id, it)) }
            }

            is ApplyAction.RenameGroup ->
                extras.groupByName(action.oldName)?.let { extras.renameGroup(it.id, action.newName) }

            else -> Unit
        }
    }
}

private const val PENDING_PERSON_PREFIX = "pending_person:"
private const val PROPOSAL_OP_PREFIX = "proposal_op:"

private fun ExtrasDao.groupByName(name: String): PersonGroupEntity? = getGroups().firstOrNull { it.name == name }
