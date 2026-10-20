package org.fossify.gallery.family

// Rozhodovanie o operáciách z druhého telefónu (spec 2.4). Čistá logika bez Androidu (OpApplierTest).
//  - potichu sa prevezme: nová osoba, nové priradenie tváre bez konfliktu, skupina, odtlačky;
//  - návrh vznikne pri: premenovaní na oboch stranách, rovnakom mene, zlúčení, odobratí alebo
//    preradení ručne priradenej tváre a pri zmazaní (mazanie sa NIKDY nevykoná automaticky);
//  - ručné priradenie má vždy prednosť pred automatickým, aj keď automatické príde neskôr;
//  - každá operácia sa aplikuje práve raz (opId) a v deterministickom poradí (LoggedOp.ORDER).
// Samotný zápis robí ActionSink (FamilyLocalState) výhradne cez existujúce DAO metódy.

// Lokálne priradenie tváre (FaceAssignmentEntity v people.db).
data class LocalAssignment(val personId: Long, val manual: Boolean)

// Pohľad na lokálny stav, z ktorého sa rozhoduje.
interface FamilyState {
    // lokálne id osoby pre rodinné UUID (aj cez vlastné zlúčenia); null = neznáma alebo už neexistuje
    fun localPersonId(uuid: String): Long?

    // UUID je známe (má mapu alebo alias), aj keď lokálna osoba medzičasom zanikla
    fun isKnown(uuid: String): Boolean

    fun personName(localId: Long): String?

    // lokálna osoba s rovnakým menom (bez ohľadu na veľkosť písmen), null = žiadna
    fun personByName(name: String): Long?

    // lokálna tvár zodpovedajúca FaceRef, null = fotka alebo tvár tu (zatiaľ) nie je
    fun findFace(ref: FaceRef): Long?

    fun assignment(faceId: Long): LocalAssignment?

    // členovia skupiny s daným názvom, null = skupina neexistuje
    fun groupMembers(name: String): Set<Long>?

    // už uložené odtlačky osoby z daného zdroja (AnchorEmbeddingEntity.source)
    fun anchors(localId: Long, source: String): List<FloatArray>
}

// Návrh pre používateľa (ProposalEntity). Typ = ProposalEntity.type.
sealed interface Proposal {
    val type: String
}

object ProposalType {
    const val RENAME = "rename"
    const val SAME_NAME = "same_name"
    const val MERGE = "merge"
    const val FACE_MOVE = "face_move"
    const val FACE_REMOVE = "face_remove"
    const val DELETE = "delete"
}

// tá istá osoba premenovaná na oboch stranách inak → „prevziať / ponechať“
data class RenameProposal(val uuid: String, val localId: Long, val theirName: String, val myName: String?) : Proposal {
    override val type: String get() = ProposalType.RENAME
}

// nová osoba z druhého telefónu má rovnaké meno ako lokálna → „zlúčiť / ponechať oddelene“
data class SameNameProposal(val uuid: String, val name: String, val localId: Long) : Proposal {
    override val type: String get() = ProposalType.SAME_NAME
}

// druhá strana zlúčila dve osoby → „zlúčiť / ponechať“
data class MergeProposal(val fromUuid: String, val toUuid: String, val fromLocalId: Long, val toLocalId: Long) :
    Proposal {
    override val type: String get() = ProposalType.MERGE
}

// druhá strana preradila tvár, ktorú tu má ručne iná osoba → „prijať / ponechať“
data class FaceMoveProposal(val face: FaceRef, val faceId: Long, val fromLocalId: Long, val toLocalId: Long) :
    Proposal {
    override val type: String get() = ProposalType.FACE_MOVE
}

// druhá strana odobrala tvár osobe, ktorej je tu ručne priradená → „prijať / ponechať“
data class FaceRemoveProposal(val face: FaceRef, val faceId: Long, val localId: Long) : Proposal {
    override val type: String get() = ProposalType.FACE_REMOVE
}

// druhá strana osobu zmazala → „zmazať aj tu / ponechať“ (len cez potvrdenie používateľa)
data class DeleteProposal(val uuid: String, val localId: Long, val name: String?) : Proposal {
    override val type: String get() = ProposalType.DELETE
}

// Zápis, ktorý sa má vykonať (ActionSink ho prevedie na existujúce DAO metódy).
sealed interface ApplyAction {
    data class CreatePerson(val uuid: String, val name: String?) : ApplyAction

    data class RenamePerson(val localId: Long, val uuid: String, val name: String) : ApplyAction

    data class Assign(val faceId: Long, val localId: Long, val manual: Boolean) : ApplyAction

    // zruší priradenie tváre osobe a zapíše negatívne pravidlo (PeopleDao.rejectFacesForPerson)
    data class Reject(val faceId: Long, val localId: Long) : ApplyAction

    data class CreateGroup(val name: String, val members: List<Long>) : ApplyAction

    data class AddGroupMembers(val name: String, val members: List<Long>) : ApplyAction

    data class RenameGroup(val oldName: String, val newName: String) : ApplyAction

    data class AddAnchors(val localId: Long, val vectors: List<FloatArray>, val source: String) : ApplyAction

    data class Propose(val proposal: Proposal) : ApplyAction
}

enum class Reason {
    UNKNOWN_PERSON, // osoba ešte nedorazila alebo čaká na rozhodnutie (návrh rovnakého mena)
    FACE_NOT_HERE, // fotka alebo tvár tu zatiaľ nie je (príde s fotkou, F4)
    PERSON_GONE, // lokálna osoba už neexistuje
    NO_CHANGE,
    MANUAL_WINS, // automatické priradenie neprepíše iné priradenie
    OTHER_MODEL, // odtlačky iného embeddera sa zahodia
    GROUP_EXISTS,
}

sealed interface Decision {
    data class Apply(val actions: List<ApplyAction>) : Decision

    // ostane v op-logu neaplikovaná a skúsi sa znova pri ďalšom príjme
    data class Defer(val reason: Reason) : Decision

    // spracovaná bez zmeny (označí sa ako aplikovaná)
    data class Skip(val reason: Reason) : Decision
}

// Úložisko op-logu (OpDao). store() vráti false, keď operácia s rovnakým opId už je uložená.
interface OpStore {
    fun store(op: LoggedOp): Boolean

    // uložené a ešte neaplikované operácie (aj odložené z minulých príjmov)
    fun pending(): List<LoggedOp>

    fun markApplied(opId: String)
}

fun interface ActionSink {
    fun execute(action: ApplyAction, op: LoggedOp)
}

data class ApplyReport(
    val applied: Int = 0,
    val skipped: Int = 0,
    val deferred: Int = 0,
    val duplicates: Int = 0,
    val proposals: Int = 0,
    val failed: Int = 0,
)

class OpApplier(private val ownDeviceId: String, private val faceModelId: String) {

    // Uloží nové operácie (duplicitné a vlastné ignoruje) a aplikuje všetky čakajúce v poradí.
    fun process(incoming: List<LoggedOp>, store: OpStore, state: FamilyState, sink: ActionSink): ApplyReport {
        var duplicates = 0
        for (op in incoming) {
            if (op.deviceId == ownDeviceId || !store.store(op)) duplicates++
        }
        var report = ApplyReport(duplicates = duplicates)
        for (op in store.pending().sortedWith(LoggedOp.ORDER)) {
            report = applyOne(op, store, state, sink, report)
        }
        return report
    }

    private fun applyOne(op: LoggedOp, store: OpStore, state: FamilyState, sink: ActionSink, r: ApplyReport) =
        when (val decision = decide(op, state)) {
            is Decision.Defer -> r.copy(deferred = r.deferred + 1)
            is Decision.Skip -> {
                store.markApplied(op.opId)
                r.copy(skipped = r.skipped + 1)
            }

            is Decision.Apply -> try {
                decision.actions.forEach { sink.execute(it, op) }
                store.markApplied(op.opId)
                val proposals = decision.actions.count { it is ApplyAction.Propose }
                r.copy(applied = r.applied + 1, proposals = r.proposals + proposals)
            } catch (ignored: RuntimeException) {
                // neoznačí sa ako aplikovaná → skúsi sa znova; ostatné operácie pokračujú
                r.copy(failed = r.failed + 1)
            }
        }

    fun decide(logged: LoggedOp, state: FamilyState): Decision = when (val op = logged.op) {
        is PersonCreate -> PersonRules.create(op, state)
        is PersonRename -> PersonRules.rename(op, state)
        is PersonMerge -> PersonRules.merge(op, state)
        is PersonDelete -> PersonRules.delete(op, state)
        is AssignFace -> FaceRules.assign(op, state)
        is UnassignFace -> FaceRules.unassign(op, state)
        is AnchorsSet -> FaceRules.anchors(op, state, faceModelId, anchorSource(logged.deviceId))
        is GroupSet -> GroupRules.set(op, state)
        is GroupRename -> GroupRules.rename(op, state)
    }

    companion object {
        const val ANCHOR_SOURCE_PREFIX = "family:"

        fun anchorSource(deviceId: String) = "$ANCHOR_SOURCE_PREFIX$deviceId"
    }
}

// neznáme UUID počká (osoba ešte nedorazila); známe, ale lokálne zmazané sa preskočí
private fun missingPerson(uuid: String, state: FamilyState): Decision =
    if (state.isKnown(uuid)) Decision.Skip(Reason.PERSON_GONE) else Decision.Defer(Reason.UNKNOWN_PERSON)

private fun propose(p: Proposal) = Decision.Apply(listOf(ApplyAction.Propose(p)))

private object PersonRules {
    fun create(op: PersonCreate, state: FamilyState): Decision {
        if (state.isKnown(op.uuid)) return Decision.Skip(Reason.NO_CHANGE)
        val name = op.name?.trim().orEmpty()
        val sameName = if (name.isEmpty()) null else state.personByName(name)
        return if (sameName != null) {
            propose(SameNameProposal(op.uuid, name, sameName))
        } else {
            Decision.Apply(listOf(ApplyAction.CreatePerson(op.uuid, op.name)))
        }
    }

    fun rename(op: PersonRename, state: FamilyState): Decision {
        val local = state.localPersonId(op.uuid) ?: return missingPerson(op.uuid, state)
        val current = state.personName(local)
        return when {
            current == op.name -> Decision.Skip(Reason.NO_CHANGE)
            current.isNullOrBlank() || current == op.previousName ->
                Decision.Apply(listOf(ApplyAction.RenamePerson(local, op.uuid, op.name)))

            else -> propose(RenameProposal(op.uuid, local, op.name, current))
        }
    }

    fun merge(op: PersonMerge, state: FamilyState): Decision {
        val from = state.localPersonId(op.fromUuid) ?: return Decision.Skip(Reason.PERSON_GONE)
        val to = state.localPersonId(op.toUuid) ?: return missingPerson(op.toUuid, state)
        return if (from == to) {
            Decision.Skip(Reason.NO_CHANGE)
        } else {
            propose(MergeProposal(op.fromUuid, op.toUuid, from, to))
        }
    }

    // mazanie sa NIKDY nevykoná automaticky, vznikne len návrh
    fun delete(op: PersonDelete, state: FamilyState): Decision {
        val local = state.localPersonId(op.uuid) ?: return Decision.Skip(Reason.PERSON_GONE)
        return propose(DeleteProposal(op.uuid, local, op.name ?: state.personName(local)))
    }
}

private object FaceRules {
    fun assign(op: AssignFace, state: FamilyState): Decision {
        val person = state.localPersonId(op.uuid) ?: return missingPerson(op.uuid, state)
        val face = state.findFace(op.face) ?: return Decision.Defer(Reason.FACE_NOT_HERE)
        val current = state.assignment(face)
        val assign = Decision.Apply(listOf(ApplyAction.Assign(face, person, op.manual)))
        return when {
            current == null -> assign
            current.personId == person ->
                if (op.manual && !current.manual) assign else Decision.Skip(Reason.NO_CHANGE)

            !op.manual -> Decision.Skip(Reason.MANUAL_WINS)
            !current.manual -> assign
            else -> propose(FaceMoveProposal(op.face, face, current.personId, person))
        }
    }

    fun unassign(op: UnassignFace, state: FamilyState): Decision {
        val person = state.localPersonId(op.uuid) ?: return missingPerson(op.uuid, state)
        // aj odobratie čaká na fotku: inak by neskôr dorazené priradenie prebilo novšie odobratie
        val face = state.findFace(op.face) ?: return Decision.Defer(Reason.FACE_NOT_HERE)
        val current = state.assignment(face)
        return when {
            current == null || current.personId != person -> Decision.Skip(Reason.NO_CHANGE)
            !current.manual -> Decision.Apply(listOf(ApplyAction.Reject(face, person)))
            else -> propose(FaceRemoveProposal(op.face, face, person))
        }
    }

    fun anchors(op: AnchorsSet, state: FamilyState, faceModelId: String, source: String): Decision {
        if (op.faceModelId != faceModelId) return Decision.Skip(Reason.OTHER_MODEL)
        val person = state.localPersonId(op.uuid) ?: return missingPerson(op.uuid, state)
        val have = state.anchors(person, source)
        val room = AnchorsSet.MAX_ANCHORS - have.size
        val fresh = op.vectors
            .filter { v -> v.isNotEmpty() && have.none { it.contentEquals(v) } }
            .distinctBy { it.toList() }
            .take(maxOf(0, room))
        return if (fresh.isEmpty()) {
            Decision.Skip(Reason.NO_CHANGE)
        } else {
            Decision.Apply(listOf(ApplyAction.AddAnchors(person, fresh, source)))
        }
    }
}

private object GroupRules {
    // členovia sa len pridávajú, nikdy neodoberajú
    fun set(op: GroupSet, state: FamilyState): Decision {
        if (op.memberUuids.any { !state.isKnown(it) }) return Decision.Defer(Reason.UNKNOWN_PERSON)
        val members = op.memberUuids.mapNotNull { state.localPersonId(it) }.distinct()
        val existing = state.groupMembers(op.name)
            ?: return Decision.Apply(listOf(ApplyAction.CreateGroup(op.name, members)))
        val added = members.filter { it !in existing }
        return if (added.isEmpty()) {
            Decision.Skip(Reason.NO_CHANGE)
        } else {
            Decision.Apply(listOf(ApplyAction.AddGroupMembers(op.name, added)))
        }
    }

    fun rename(op: GroupRename, state: FamilyState): Decision = when {
        op.oldName == op.newName || state.groupMembers(op.oldName) == null -> Decision.Skip(Reason.NO_CHANGE)
        state.groupMembers(op.newName) != null -> Decision.Skip(Reason.GROUP_EXISTS)
        else -> Decision.Apply(listOf(ApplyAction.RenameGroup(op.oldName, op.newName)))
    }
}
