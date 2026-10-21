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
    val store = LocalOpStore(FamilyDatabase.getInstance(app).opDao(), clock, FaceEmbedder.MODEL_ID)
    val sink = LocalActionSink(app, state, clock)
    val ownDeviceId: String by lazy { FamilyDatabase.getInstance(app).metaDao().deviceId() }
}

@Suppress("TooManyFunctions") // implementácia celého FamilyState
class LocalFamilyState(context: Context) : FamilyState {
    private val family = FamilyDatabase.getInstance(context)
    private val people = PeopleDatabase.getInstance(context).PeopleDao()
    private val extras = ExtrasDatabase.getInstance(context).ExtrasDao()
    // D4: tváre sa hľadajú aj na kópiách nájdených len cez zálohu (bundle_inbox.applied_path)
    private val faces = FamilyFaces(
        context,
        FacesDatabase.getInstance(context).FaceDao(),
        ContentKeyStore(family.contentKeyDao()),
        spaces = family.faceSpaceDao(),
        aliases = { family.bundleInboxDao().appliedPaths(it) },
    )
    private val familyPersons = family.familyPersonDao()
    private val remote = family.remoteAssignmentDao()

    private var personsCache: Map<Long, String?>? = null
    private var assignmentsCache: MutableMap<Long, FaceAssignmentEntity>? = null
    private var aliasesCache: Map<String, String>? = null
    private var pairedCache: Set<String>? = null

    private val persons: Map<Long, String?>
        get() = personsCache ?: people.getPersons().associate { it.id to it.name }.also { personsCache = it }

    private val assignments: MutableMap<Long, FaceAssignmentEntity>
        get() = assignmentsCache ?: people.getAssignments()
            .associateByTo(HashMap()) { it.faceId }
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

    fun onAssignmentChanged(faceId: Long, value: FaceAssignmentEntity?) {
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

    override fun assignment(faceId: Long): LocalAssignment? =
        assignments[faceId]?.let { LocalAssignment(it.personId, it.isManual) }

    override fun nameVersion(uuid: String): NameVersion =
        familyPersons.getPersonByUuid(uuid)?.let { NameVersion(it.nameVersionLamport, it.nameDeviceId) }
            ?: NameVersion.NONE

    override fun assignmentOrigin(faceId: Long): String? {
        val row = remote.get(faceId) ?: return null
        return row.deviceId.takeIf { RemoteOrigin.untouched(row, assignments[faceId]) }
    }

    override fun anchorSources(localId: Long): Set<String> =
        people.getAllAnchors().filter { it.personId == localId }.mapTo(HashSet()) { it.source }

    override fun isPairedDevice(deviceId: String): Boolean {
        val paired = pairedCache
            ?: family.peerDao().getPeers().mapTo(HashSet()) { it.deviceId }.also { pairedCache = it }
        return deviceId in paired
    }

    override fun groupMembers(name: String): Set<Long>? =
        extras.groupByName(name)?.let { extras.getMembers(it.id).toSet() }

    override fun anchors(localId: Long, source: String): List<FloatArray> =
        people.getAllAnchors().filter { it.personId == localId && it.source == source }
            .map { FaceEmbedder.toFloats(it.embedding) }

    companion object {
        private const val MAX_ALIAS_HOPS = 8
    }
}

// D9: staršie AnchorsSet tej istej osoby z toho istého telefónu (aj vlastné) sú nahradené novšou sadou
internal fun dropSupersededAnchors(dao: OpDao, op: LoggedOp) {
    val uuid = (op.op as? AnchorsSet)?.uuid ?: return
    dao.getOpsOfType(op.deviceId, OpType.ANCHORS_SET)
        .filter { it.lamport < op.lamport && (OpCodec.decode(it.type, it.payloadJson) as? AnchorsSet)?.uuid == uuid }
        .forEach { dao.deleteSupersededAnchors(it.opId) }
}

class LocalOpStore(private val dao: OpDao, private val clock: () -> Long, private val faceModelId: String) :
    OpStore {
    override fun store(op: LoggedOp): Boolean =
        dao.insertOp(OpEntity(op.opId, op.deviceId, op.lamport, op.op.type, OpCodec.encode(op.op))) != -1L

    // Neznáme typy (novšia appka) a poškodené payloady ostanú v op-logu neaplikované. D9: operácie, ktoré
    // čakajú na fotku, sa vrátia, až keď sa jej obsahový kľúč objaví; po 90 dňoch sa odložia nabok.
    override fun pending(): List<LoggedOp> {
        val now = clock()
        dao.parkStale(now - PARK_AFTER_MS, now)
        return dao.getPendingOps(OpApplier.modelWait(faceModelId)).mapNotNull { e ->
            OpCodec.decode(e.type, e.payloadJson)?.let { LoggedOp(e.opId, e.deviceId, e.lamport, it) }
        }
    }

    override fun markApplied(opId: String) = dao.markOpApplied(opId, clock())

    override fun deferred(op: LoggedOp, waitKey: String?) = dao.deferOp(op.opId, waitKey, clock())

    // nahradené sady odtlačkov tej istej osoby z toho istého telefónu (staršie, aj odložené pre iný model)
    override fun supersede(op: LoggedOp) = dropSupersededAnchors(dao, op)

    companion object {
        // D9: operácia s tvárou, ktorej fotka sa ani za 90 dní neobjavila, sa odloží nabok (nezmaže sa)
        const val PARK_AFTER_MS = 90L * 24 * 60 * 60 * 1000
    }
}

class LocalActionSink(context: Context, private val state: LocalFamilyState, private val clock: () -> Long) :
    ActionSink {
    private val family = FamilyDatabase.getInstance(context)
    private val people = PeopleDatabase.getInstance(context).PeopleDao()
    private val extras = ExtrasDatabase.getInstance(context).ExtrasDao()

    // osoby, ktoré vznikli týmto príjmom (D10: po obnove zálohy sa ohlásia prepojeným telefónom)
    val createdUuids = ArrayList<String>()

    override fun execute(action: ApplyAction, op: LoggedOp) {
        when (action) {
            is ApplyAction.CreatePerson -> createPerson(action, op)
            is ApplyAction.RenamePerson -> renamePerson(action, op)
            is ApplyAction.Assign -> {
                val entity = FaceAssignmentEntity(action.faceId, action.localId, action.manual, clock())
                people.upsertAssignment(entity)
                state.onAssignmentChanged(action.faceId, entity)
                // D6: pôvod priradenia (obnova zálohy osôb je vlastná, nie cudzia)
                if (!PeopleBackupFormat.isBackupDevice(op.deviceId)) {
                    family.remoteAssignmentDao().upsert(RemoteOrigin.record(entity, op.deviceId, op.opId))
                }
            }

            is ApplyAction.Reject -> {
                people.rejectFacesForPerson(listOf(action.faceId), action.localId)
                state.onAssignmentChanged(action.faceId, null)
                family.remoteAssignmentDao().forget(action.faceId)
            }

            is ApplyAction.CreateGroup, is ApplyAction.AddGroupMembers, is ApplyAction.RenameGroup -> group(action)
            is ApplyAction.ReplaceAnchors -> replaceAnchors(action)

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
        createdUuids.add(action.uuid)
    }

    // D7: sada zdroja sa nahradí (len odtlačky z rodiny alebo zo zálohy, nikdy Picasa ani tváre); D10: aj sady
    // starého deviceId toho istého telefónu
    private fun replaceAnchors(action: ApplyAction.ReplaceAnchors) {
        (action.clearSources + action.source).filter { OpApplier.isReplaceableSource(it) }
            .forEach { people.deleteAnchorsFromSource(action.localId, it) }
        action.vectors.forEach {
            val bytes = FaceEmbedder.toBytes(it)
            people.insertAnchor(AnchorEmbeddingEntity(0, action.localId, bytes, action.source, clock()))
        }
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
