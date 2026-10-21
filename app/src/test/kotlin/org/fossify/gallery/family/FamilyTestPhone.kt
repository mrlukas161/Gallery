package org.fossify.gallery.family

// Pamäťová napodobenina jedného telefónu pre scenáre balíka D: people.db (osoby, priradenia s časom zápisu,
// odtlačky podľa zdroja), family.db (mapa UUID, verzie mien, op-log, remote_assignment, spárované telefóny)
// a vlastné lamport hodiny. Operácie sa vymieňajú medzi dvoma telefónmi cez exchange().
class FamilyTestPhone(val deviceId: String, private val model: String = "m1") :
    FamilyState, OpStore, ActionSink {
    val persons = LinkedHashMap<Long, String?>()
    val uuidToLocal = HashMap<String, Long>()
    val nameVersions = HashMap<String, NameVersion>()
    val faces = HashMap<String, Long>() // contentKey → faceId
    val assignments = HashMap<Long, Pair<LocalAssignment, Long>>() // faceId → (priradenie, updatedAt)
    val remote = HashMap<Long, RemoteAssignmentEntity>()
    val anchors = HashMap<Pair<Long, String>, List<FloatArray>>()
    val proposals = ArrayList<Proposal>()
    val paired = HashSet<String>()
    val own = ArrayList<LoggedOp>() // vlastný op-log (posiela sa druhej strane)
    private val received = LinkedHashMap<String, LoggedOp>()
    private val applied = HashSet<String>()
    private var nextId = 100L
    private var time = 1_000L
    var clock = 0L

    val applier = OpApplier(deviceId, model)

    fun tick() = ++time

    fun person(name: String?, uuid: String): Long {
        val id = nextId++
        persons[id] = name
        uuidToLocal[uuid] = id
        return id
    }

    // vlastná zmena (ako OpRecorder.append)
    fun record(op: Op): LoggedOp {
        clock++
        val logged = LoggedOp("$deviceId-$clock", deviceId, clock, op)
        own.add(logged)
        if (op is PersonRename || op is PersonCreate) {
            val uuid = (op as? PersonRename)?.uuid ?: (op as PersonCreate).uuid
            nameVersions[uuid] = NameVersion(logged.lamport, deviceId)
        }
        return logged
    }

    // lokálne premenovanie používateľom (PeopleActivity → FamilyRecorder.personRenamed)
    fun renameLocally(uuid: String, name: String) {
        val id = uuidToLocal.getValue(uuid)
        val old = persons[id]
        persons[id] = name
        record(PersonRename(uuid, name, old))
    }

    // lokálne ručné priradenie (FaceTagging)
    fun assignLocally(faceId: Long, personId: Long) {
        assignments[faceId] = LocalAssignment(personId, true) to tick()
    }

    // prijme vlastné operácie druhého telefónu (ako storeFromPeer + retryPending)
    fun receive(ops: List<LoggedOp>): ApplyReport {
        ops.maxOfOrNull { it.lamport }?.let { clock = maxOf(clock, it) }
        return applier.process(ops, this, this, this)
    }

    // --- FamilyState ---
    override fun localPersonId(uuid: String): Long? = uuidToLocal[uuid]?.takeIf { persons.containsKey(it) }

    override fun isKnown(uuid: String) = uuidToLocal.containsKey(uuid)

    override fun personName(localId: Long) = persons[localId]

    override fun personByName(name: String): Long? =
        persons.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key

    override fun findFace(ref: FaceRef) = faces[ref.contentKey]

    override fun assignment(faceId: Long) = assignments[faceId]?.first

    override fun groupMembers(name: String): Set<Long>? = null

    override fun anchors(localId: Long, source: String): List<FloatArray> = anchors[localId to source].orEmpty()

    override fun nameVersion(uuid: String): NameVersion = nameVersions[uuid] ?: NameVersion.NONE

    override fun assignmentOrigin(faceId: Long): String? {
        val row = remote[faceId] ?: return null
        val (a, at) = assignments[faceId] ?: return null
        return row.deviceId.takeIf { RemoteOrigin.untouched(row, a.personId, at) }
    }

    override fun anchorSources(localId: Long): Set<String> =
        anchors.keys.filter { it.first == localId }.mapTo(HashSet()) { it.second }

    override fun isPairedDevice(deviceId: String) = deviceId in paired

    // --- OpStore ---
    override fun store(op: LoggedOp): Boolean {
        if (received.containsKey(op.opId)) return false
        received[op.opId] = op
        return true
    }

    override fun pending() = received.values.filter { it.opId !in applied }

    override fun markApplied(opId: String) {
        applied.add(opId)
    }

    // --- ActionSink (ako LocalActionSink) ---
    override fun execute(action: ApplyAction, op: LoggedOp) {
        when (action) {
            is ApplyAction.CreatePerson -> {
                person(action.name, action.uuid)
                nameVersions[action.uuid] = NameVersion(op.lamport, op.deviceId)
            }

            is ApplyAction.RenamePerson -> {
                persons[action.localId] = action.name
                nameVersions[action.uuid] = NameVersion(op.lamport, op.deviceId)
            }

            is ApplyAction.Assign -> {
                val at = tick()
                assignments[action.faceId] = LocalAssignment(action.localId, action.manual) to at
                if (!PeopleBackupFormat.isBackupDevice(op.deviceId)) {
                    remote[action.faceId] =
                        RemoteAssignmentEntity(action.faceId, op.deviceId, op.opId, action.localId, at)
                }
            }

            is ApplyAction.Reject -> {
                assignments.remove(action.faceId)
                remote.remove(action.faceId)
            }

            is ApplyAction.ReplaceAnchors -> {
                (action.clearSources + action.source).forEach { anchors.remove(action.localId to it) }
                anchors[action.localId to action.source] = action.vectors
            }

            is ApplyAction.Propose -> proposals.add(action.proposal)
            else -> Unit
        }
    }
}

// obe strany si vymenia všetky vlastné operácie (ako jedna synchronizácia)
fun exchange(a: FamilyTestPhone, b: FamilyTestPhone) {
    val fromA = a.own.toList()
    val fromB = b.own.toList()
    b.receive(fromA)
    a.receive(fromB)
}
