package org.fossify.gallery.family

// Rozhodovanie o operáciách z druhého telefónu (spec 2.4). Čistá logika bez Androidu (OpApplierTest).
//  - potichu sa prevezme: nová osoba, nové priradenie tváre bez konfliktu, skupina, odtlačky;
//  - návrh vznikne pri: premenovaní na oboch stranách, rovnakom mene, zlúčení, odobratí alebo
//    preradení ručne priradenej tváre a pri zmazaní (mazanie sa NIKDY nevykoná automaticky);
//  - ručné priradenie má vždy prednosť pred automatickým, aj keď automatické príde neskôr;
//  - oprava priradenia od toho istého telefónu, ktorý ho sem poslal, sa prevezme potichu, ak sa ho tu
//    používateľ nedotkol (D6, remote_assignment);
//  - mená: posledná zmena vyhráva podľa (nameVersionLamport, deviceId); návrh len pri súbežnej zmene (D8);
//  - odtlačky (AnchorsSet) sadu daného zdroja nahradia, nepridávajú (D7);
//  - každá operácia sa aplikuje práve raz (opId) a v deterministickom poradí (LoggedOp.ORDER).
// Samotný zápis robí ActionSink (FamilyLocalState) výhradne cez existujúce DAO metódy.

// Lokálne priradenie tváre (FaceAssignmentEntity v people.db).
data class LocalAssignment(val personId: Long, val manual: Boolean)

// Verzia mena osoby (D8): lamport a zariadenie operácie, ktorá meno naposledy nastavila. Novšia = vyšší
// lamport, pri rovnosti vyššie deviceId (deterministicky rovnako na oboch telefónoch).
data class NameVersion(val lamport: Long, val deviceId: String?) : Comparable<NameVersion> {
    override fun compareTo(other: NameVersion): Int =
        compareValuesBy(this, other, { it.lamport }, { it.deviceId.orEmpty() })

    companion object {
        val NONE = NameVersion(0, null)
    }
}

// Pohľad na lokálny stav, z ktorého sa rozhoduje.
@Suppress("TooManyFunctions") // pohľad na celý lokálny stav (people/extras/faces/family.db)
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

    // D8: verzia aktuálneho mena osoby (FamilyPersonEntity.nameVersionLamport/nameDeviceId)
    fun nameVersion(uuid: String): NameVersion = NameVersion.NONE

    // D6: telefón, ktorý priradenie tváre sem poslal, ak sa ho tu odvtedy nikto nedotkol; null = vlastné
    fun assignmentOrigin(faceId: Long): String? = null

    // D10: zdroje odtlačkov osoby (AnchorEmbeddingEntity.source) a či je telefón stále spárovaný
    fun anchorSources(localId: Long): Set<String> = emptySet()

    fun isPairedDevice(deviceId: String): Boolean = true
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

    // D7: sada odtlačkov zdroja `source` sa NAHRADÍ (nie pridá); clearSources = sady iných zdrojov tej istej
    // osoby, ktoré nahrádza tiež (D10: starý deviceId toho istého telefónu po preinštalovaní)
    data class ReplaceAnchors(
        val localId: Long,
        val vectors: List<FloatArray>,
        val source: String,
        val clearSources: Set<String> = emptySet(),
    ) : ApplyAction

    data class Propose(val proposal: Proposal) : ApplyAction
}

enum class Reason {
    UNKNOWN_PERSON, // osoba ešte nedorazila alebo čaká na rozhodnutie (návrh rovnakého mena)
    FACE_NOT_HERE, // fotka alebo tvár tu zatiaľ nie je (príde s fotkou, F4)
    PERSON_GONE, // lokálna osoba už neexistuje
    NO_CHANGE,
    MANUAL_WINS, // automatické priradenie neprepíše iné priradenie
    OTHER_MODEL, // odtlačky iného embeddera počkajú na rovnaký model (D9), nikdy sa nemiešajú
    STALE_NAME, // premenovanie staršie než lokálne meno (D8: posledná zmena vyhráva)
    GROUP_EXISTS,
}

sealed interface Decision {
    data class Apply(val actions: List<ApplyAction>) : Decision

    // ostane v op-logu neaplikovaná a skúsi sa znova pri ďalšom príjme; waitKey (D9) = skúsi sa až keď sa
    // objaví fotka s týmto obsahovým kľúčom, alebo „model:<id>“ = až keď je tu ten model odtlačkov
    data class Defer(val reason: Reason, val waitKey: String? = null) : Decision

    // spracovaná bez zmeny (označí sa ako aplikovaná)
    data class Skip(val reason: Reason) : Decision
}

// Úložisko op-logu (OpDao). store() vráti false, keď operácia s rovnakým opId už je uložená.
interface OpStore {
    fun store(op: LoggedOp): Boolean

    // uložené a ešte neaplikované operácie (aj odložené z minulých príjmov)
    fun pending(): List<LoggedOp>

    fun markApplied(opId: String)

    // D9: operácia sa odložila (waitKey = na čo čaká)
    fun deferred(op: LoggedOp, waitKey: String?) {
        // predvolene nič
    }

    // D9: novšia sada odtlačkov tej istej osoby z toho istého telefónu je spracovaná, staršie sa môžu zmazať
    fun supersede(op: LoggedOp) {
        // predvolene nič
    }
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
            is Decision.Defer -> {
                store.deferred(op, decision.waitKey)
                r.copy(deferred = r.deferred + 1)
            }

            is Decision.Skip -> {
                store.markApplied(op.opId)
                if (op.op is AnchorsSet) store.supersede(op)
                r.copy(skipped = r.skipped + 1)
            }

            is Decision.Apply -> try {
                decision.actions.forEach { sink.execute(it, op) }
                store.markApplied(op.opId)
                if (op.op is AnchorsSet) store.supersede(op)
                val proposals = decision.actions.count { it is ApplyAction.Propose }
                r.copy(applied = r.applied + 1, proposals = r.proposals + proposals)
            } catch (ignored: RuntimeException) {
                // neoznačí sa ako aplikovaná → skúsi sa znova; ostatné operácie pokračujú
                r.copy(failed = r.failed + 1)
            }
        }

    fun decide(logged: LoggedOp, state: FamilyState): Decision = when (val op = logged.op) {
        is PersonCreate -> PersonRules.create(op, state)
        is PersonRename -> PersonRules.rename(op, NameVersion(logged.lamport, logged.deviceId), state)
        is PersonMerge -> PersonRules.merge(op, state)
        is PersonDelete -> PersonRules.delete(op, state)
        is AssignFace -> FaceRules.assign(op, logged.deviceId, state)
        is UnassignFace -> FaceRules.unassign(op, logged.deviceId, state)
        is AnchorsSet -> AnchorRules.anchors(op, state, faceModelId, logged.deviceId)
        is GroupSet -> GroupRules.set(op, state)
        is GroupRename -> GroupRules.rename(op, state)
    }

    companion object {
        const val ANCHOR_SOURCE_PREFIX = "family:"

        // D10: odtlačky zo zálohy osôb (F6) sú vlastné — zdroj nezačína „family:“, takže sa posielajú ďalej
        // (AnchorOutbox) a nemajú nižšiu váhu cudzích odtlačkov
        const val RESTORED_ANCHOR_SOURCE = "restore"

        // kľúč čakania (D9) pre odtlačky iného modelu
        const val MODEL_WAIT_PREFIX = "model:"

        fun anchorSource(deviceId: String) = if (PeopleBackupFormat.isBackupDevice(deviceId)) {
            RESTORED_ANCHOR_SOURCE
        } else {
            "$ANCHOR_SOURCE_PREFIX$deviceId"
        }

        fun modelWait(faceModelId: String) = "$MODEL_WAIT_PREFIX$faceModelId"

        // nahradiť (zmazať a zapísať znova) sa smú len sady z rodiny a zo zálohy osôb, nikdy Picasa a iné vlastné
        fun isReplaceableSource(source: String) =
            source.startsWith(ANCHOR_SOURCE_PREFIX) || source == RESTORED_ANCHOR_SOURCE
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

    // D8: posledná zmena vyhráva. Staršie premenovanie než lokálne meno sa preskočí (druhá strana dostane
    // naše novšie a prevezme ho). Novšie sa prevezme potichu, keď odosielateľ videl naše meno (previousName)
    // alebo tu meno chýba; inak ide o súbežnú zmenu → návrh.
    fun rename(op: PersonRename, version: NameVersion, state: FamilyState): Decision {
        val local = state.localPersonId(op.uuid) ?: return missingPerson(op.uuid, state)
        val current = state.personName(local)
        return when {
            current == op.name -> Decision.Skip(Reason.NO_CHANGE)
            version <= state.nameVersion(op.uuid) -> Decision.Skip(Reason.STALE_NAME)
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
    fun assign(op: AssignFace, from: String, state: FamilyState): Decision {
        val person = state.localPersonId(op.uuid) ?: return missingPerson(op.uuid, state)
        val face = state.findFace(op.face) ?: return waitForPhoto(op.face)
        val current = state.assignment(face)
        val assign = Decision.Apply(listOf(ApplyAction.Assign(face, person, op.manual)))
        return when {
            current == null -> assign
            current.personId == person ->
                if (op.manual && !current.manual) assign else Decision.Skip(Reason.NO_CHANGE)

            !op.manual -> Decision.Skip(Reason.MANUAL_WINS)
            !current.manual -> assign
            // D6: ten istý telefón opravuje svoje vlastné (tu nedotknuté) priradenie
            state.assignmentOrigin(face) == from -> assign
            else -> propose(FaceMoveProposal(op.face, face, current.personId, person))
        }
    }

    fun unassign(op: UnassignFace, from: String, state: FamilyState): Decision {
        val person = state.localPersonId(op.uuid) ?: return missingPerson(op.uuid, state)
        // aj odobratie čaká na fotku: inak by neskôr dorazené priradenie prebilo novšie odobratie
        val face = state.findFace(op.face) ?: return waitForPhoto(op.face)
        val current = state.assignment(face)
        return when {
            current == null || current.personId != person -> Decision.Skip(Reason.NO_CHANGE)
            !current.manual -> Decision.Apply(listOf(ApplyAction.Reject(face, person)))
            // D6: chybné priradenie, ktoré sem poslal ten istý telefón a tu sa ho nikto nedotkol → potichu
            state.assignmentOrigin(face) == from -> Decision.Apply(listOf(ApplyAction.Reject(face, person)))
            else -> propose(FaceRemoveProposal(op.face, face, person))
        }
    }

    // D9: skúsi sa znova, až keď sa objaví fotka s týmto obsahovým kľúčom
    private fun waitForPhoto(ref: FaceRef) = Decision.Defer(Reason.FACE_NOT_HERE, ref.contentKey)
}

// D7 + D10: odtlačky osoby (AnchorsSet).
private object AnchorRules {
    // podiel takmer zhodných vektorov, od ktorého je sada iného zdroja tá istá (preinštalovaný telefón)
    private const val SAME_SET_SHARE = 1.0 / 3
    private const val SAME_VECTOR_COSINE = 0.995f

    fun anchors(op: AnchorsSet, state: FamilyState, faceModelId: String, from: String): Decision {
        // iný model: počká, kým tu bude ten istý (vektory rôznych modelov sa nikdy nemiešajú)
        if (op.faceModelId != faceModelId) {
            return Decision.Defer(Reason.OTHER_MODEL, OpApplier.modelWait(op.faceModelId))
        }
        val person = state.localPersonId(op.uuid) ?: return missingPerson(op.uuid, state)
        val source = OpApplier.anchorSource(from)
        val fresh = op.vectors.filter { it.isNotEmpty() }.distinctBy { it.toList() }.take(AnchorsSet.MAX_ANCHORS)
        if (fresh.isEmpty()) return Decision.Skip(Reason.NO_CHANGE)
        val stale = if (source == OpApplier.RESTORED_ANCHOR_SOURCE) {
            emptySet()
        } else {
            staleSources(person, source, fresh, state)
        }
        if (stale.isEmpty() && sameSet(state.anchors(person, source), fresh)) return Decision.Skip(Reason.NO_CHANGE)
        return Decision.Apply(listOf(ApplyAction.ReplaceAnchors(person, fresh, source, stale)))
    }

    // Sady iných telefónov tej istej osoby, ktoré sú v skutočnosti od toho istého telefónu pod starým deviceId
    // (preinštalovanie, D10): telefón už nie je spárovaný, alebo sa jeho sada s novou z veľkej časti zhoduje.
    private fun staleSources(person: Long, source: String, fresh: List<FloatArray>, state: FamilyState): Set<String> {
        val prefix = OpApplier.ANCHOR_SOURCE_PREFIX
        val backupPrefix = prefix + PeopleBackupFormat.DEVICE_PREFIX
        return state.anchorSources(person).filterTo(HashSet()) { other ->
            val foreign = other != source && other.startsWith(prefix) && !other.startsWith(backupPrefix)
            val gone = foreign && !state.isPairedDevice(other.removePrefix(prefix))
            gone || (foreign && overlaps(state.anchors(person, other), fresh))
        }
    }

    private fun overlaps(theirs: List<FloatArray>, fresh: List<FloatArray>): Boolean {
        val same = theirs.count { v -> fresh.any { FaceSpaceRules.cosine(it, v) >= SAME_VECTOR_COSINE } }
        return theirs.isNotEmpty() && same >= theirs.size * SAME_SET_SHARE
    }

    private fun sameSet(a: List<FloatArray>, b: List<FloatArray>): Boolean =
        a.size == b.size && a.map { it.toList() }.toSet() == b.map { it.toList() }.toSet()
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
