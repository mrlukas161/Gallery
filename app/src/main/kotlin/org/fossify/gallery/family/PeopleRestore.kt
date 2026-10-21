package org.fossify.gallery.family

// Obnova zálohy osôb (F6) ako príjem od „zariadenia zálohy“ cez OpApplier. Čistá logika bez Androidu.
//
// D10: odtlačky zo zálohy sa ukladajú ako vlastné (OpApplier.RESTORED_ANCHOR_SOURCE), nie „family:backup:…“.
// Rozdiel oproti príjmu z telefónu je inak jediný: pomenovaná osoba zo zálohy, ktorá tu medzičasom zanikla
// (zmazaná, nie zlúčená), sa vytvorí znova. Na to slúži RestoreState: také UUID sa tvári ako neznáme,
// takže PersonCreate prebehne potichu (alebo ako návrh pri rovnakom mene). Zlúčené osoby sa sledujú
// cez alias, ostanú zlúčené. Nepomenované zaniknuté osoby sa neobnovujú (bývajú to zmazané zhluky).
// Inak platí všetko z OpApplier: nič sa neprepisuje ani nemaže, rozpory sú návrhy.
class RestoreState(private val base: FamilyState, private val unnamedUuids: Set<String>) : FamilyState by base {
    override fun isKnown(uuid: String): Boolean =
        base.localPersonId(uuid) != null || (uuid in unnamedUuids && base.isKnown(uuid))

    // D8: záloha je snímka bez časovej osi — jej meno sa porovná s aktuálnym (iné = návrh, chýbajúce = doplní sa),
    // nie s lamport hodinami telefónu
    override fun nameVersion(uuid: String): NameVersion = NameVersion.NONE

    // D6: priradenia zo zálohy sú vlastné; pri obnove sa nič nepreberá ako „oprava od toho istého telefónu“
    override fun assignmentOrigin(faceId: Long): String? = null
}

// Pri obnove sa spracujú len operácie tejto zálohy, nie odložené operácie z prepojených telefónov
// (tie ostanú na bežné opakovanie s bežnými pravidlami).
class RestoreOpStore(private val base: OpStore, private val deviceId: String) : OpStore {
    override fun store(op: LoggedOp): Boolean = base.store(op)

    override fun pending(): List<LoggedOp> = base.pending().filter { it.deviceId == deviceId }

    override fun markApplied(opId: String) = base.markApplied(opId)
}

object PeopleRestore {
    // záznamy zálohy → operácie pre OpApplier (neznáme typy a poškodené payloady vypadnú)
    fun logged(backup: PeopleBackupFormat.Backup): List<LoggedOp> = backup.ops.mapNotNull { e ->
        OpCodec.decode(e.type, e.payloadJson)?.let { LoggedOp(e.opId, e.deviceId, e.lamport, it) }
    }

    // D10: všetky osoby zálohy (po obnove sa ich odtlačky znova pošlú prepojeným telefónom)
    fun restoredUuids(ops: List<LoggedOp>): Set<String> =
        ops.mapNotNullTo(LinkedHashSet()) { (it.op as? PersonCreate)?.uuid }

    // UUID nepomenovaných osôb zálohy (tie sa po zmazaní neobnovujú)
    fun unnamedUuids(ops: List<LoggedOp>): Set<String> {
        val named = ops.mapNotNull { (it.op as? PersonRename)?.uuid }.toSet() +
            ops.mapNotNull { (it.op as? PersonCreate)?.takeIf { p -> !p.name.isNullOrBlank() }?.uuid }
        return ops.mapNotNull { (it.op as? PersonCreate)?.uuid }.filter { it !in named }.toSet()
    }

    fun restore(
        backup: PeopleBackupFormat.Backup,
        applier: OpApplier,
        store: OpStore,
        state: FamilyState,
        sink: ActionSink,
    ): ApplyReport {
        val ops = logged(backup)
        return applier.process(
            ops,
            RestoreOpStore(store, backup.deviceId),
            RestoreState(state, unnamedUuids(ops)),
            sink,
        )
    }
}
