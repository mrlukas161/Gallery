package org.fossify.gallery.family

import android.content.Context
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.MergeUndo
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PeopleIndex
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

// Zachytávanie zmien v Ľuďoch do op-logu (F3). Volá sa z miest, kde UI zapisuje cez PeopleDao/ExtrasDao,
// VŽDY až po úspešnom zápise. Recorder NIKDY nezablokuje ani nezhodí pôvodnú akciu: práca beží na
// vlastnom vlákne na pozadí (v poradí volaní) a každá chyba sa zahodí. Keď nie je spárovaný žiadny
// telefón, nerobí nič (stav pred spárovaním prenesie FamilySync.snapshotIfNeeded).
object FamilyRecorder {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    // nová osoba (aj s tvárami, ktoré dostala pri založení)
    fun personCreated(context: Context, localId: Long, faceIds: List<Long> = emptyList()) = record(context) {
        it.uuidFor(localId)
        it.facesAssigned(faceIds, localId)
    }

    fun personRenamed(context: Context, localId: Long, oldName: String?, newName: String) =
        record(context) { it.personRenamed(localId, oldName, newName) }

    fun personsMerged(context: Context, fromIds: List<Long>, toId: Long) =
        record(context) { it.personsMerged(fromIds, toId) }

    // names = mená zmazaných osôb (zmazanie sa na druhom telefóne ukáže len ako návrh)
    fun personsDeleted(context: Context, names: Map<Long, String?>) =
        record(context) { r -> names.forEach { (id, name) -> r.personDeleted(id, name) } }

    // ručné priradenie (aj preradenie) tvárí osobe
    fun facesAssigned(context: Context, faceIds: List<Long>, localId: Long) =
        record(context) { it.facesAssigned(faceIds, localId) }

    // „toto nie je táto osoba“
    fun facesRejected(context: Context, faceIds: List<Long>, localId: Long) =
        record(context) { it.facesRejected(faceIds, localId) }

    fun groupChanged(context: Context, groupId: Long) = record(context) { it.groupChanged(groupId) }

    fun groupRenamed(context: Context, oldName: String, newName: String) = record(context) {
        if (oldName != newName) it.append(GroupRename(oldName, newName))
    }

    private fun record(context: Context, block: (OpRecorder) -> Unit) {
        // C9: každá zmena osôb z UI ide cez recorder → jeden zdroj pravdy sa hneď prepočíta (aj bez prepojenia)
        PeopleIndex.invalidate()
        val app = context.applicationContext
        try {
            worker.execute {
                try {
                    OpRecorder(app).use { recorder -> if (recorder.hasPeers()) block(recorder) }
                } catch (ignored: Throwable) {
                    // rodinné prepojenie je vedľajšie — pôvodná akcia už prebehla
                }
            }
        } catch (ignored: RejectedExecutionException) {
        }
    }
}

// Zápis vlastných operácií do op-logu. Volá sa len z pozadia (FamilyRecorder, FamilySync, návrhy).
// Po použití close(): pri tvárach na otočených fotkách sa priestor rámčeka overuje odtlačkom (D3, modely tvárí).
@Suppress("TooManyFunctions")
class OpRecorder(context: Context, private val clock: () -> Long = System::currentTimeMillis) : Closeable {
    private val app = context.applicationContext
    private val family = FamilyDatabase.getInstance(app)
    private val people = PeopleDatabase.getInstance(app).PeopleDao()
    private val extras = ExtrasDatabase.getInstance(app).ExtrasDao()
    private val faceDao = FacesDatabase.getInstance(app).FaceDao()
    private val familyPersons = family.familyPersonDao()
    private var verifier: FaceSpaceVerifier? = null
    private val faces by lazy {
        val v = FaceSpaceVerifier(app).also { verifier = it }
        FamilyFaces(app, faceDao, ContentKeyStore(family.contentKeyDao()), family.faceSpaceDao(), verifier = v)
    }
    val anchors by lazy { AnchorOutbox(app, this) }

    fun hasPeers(): Boolean = family.peerDao().getPeers().isNotEmpty()

    override fun close() {
        verifier?.close()
    }

    // Zapíše vlastnú operáciu: nové opId, ďalšia lamport hodnota, vlastné deviceId; je hneď aplikovaná.
    fun append(op: Op): LoggedOp {
        val meta = family.metaDao()
        val logged = LoggedOp(UUID.randomUUID().toString(), meta.deviceId(), meta.nextLamport(), op)
        family.opDao().insertOp(
            OpEntity(logged.opId, logged.deviceId, logged.lamport, op.type, OpCodec.encode(op), clock())
        )
        return logged
    }

    fun existingUuid(localId: Long): String? = familyPersons.getPersonByLocalId(localId)?.familyUuid

    // Rodinné UUID lokálnej osoby; pri prvom použití vznikne a odíde PersonCreate s aktuálnym menom.
    fun uuidFor(localId: Long): String = synchronized(FamilyLock) {
        existingUuid(localId)?.let { return it }
        val uuid = UUID.randomUUID().toString()
        val name = people.getPersons().firstOrNull { it.id == localId }?.name
        val logged = append(PersonCreate(uuid, name))
        familyPersons.upsertPerson(FamilyPersonEntity(uuid, localId, logged.lamport, logged.deviceId))
        uuid
    }

    // aj rozhodnutie o návrhu premenovania (D8: FamilyProposals), aby obe strany došli k rovnakému menu
    fun personRenamed(localId: Long, oldName: String?, newName: String) {
        val uuid = existingUuid(localId)
        if (uuid == null) {
            uuidFor(localId) // PersonCreate už nesie nové meno
            return
        }
        if (oldName == newName) return
        val logged = append(PersonRename(uuid, newName, oldName))
        familyPersons.getPersonByUuid(uuid)?.let {
            familyPersons.upsertPerson(it.copy(nameVersionLamport = logged.lamport, nameDeviceId = logged.deviceId))
        }
    }

    // zlúčené osoby, ktoré druhý telefón nikdy nevidel, netreba hlásiť
    fun personsMerged(fromIds: List<Long>, toId: Long) {
        val sources = fromIds.filter { it != toId }.mapNotNull { existingUuid(it) }
        if (sources.isEmpty()) return
        val toUuid = uuidFor(toId)
        sources.filter { it != toUuid }.forEach { append(PersonMerge(it, toUuid)) }
        anchors.markDirty(listOf(toId))
    }

    fun personDeleted(localId: Long, name: String?) {
        existingUuid(localId)?.let { append(PersonDelete(it, name)) }
    }

    fun facesAssigned(faceIds: List<Long>, localId: Long) {
        if (faceIds.isEmpty()) return
        val uuid = uuidFor(localId)
        // E2: pôvod priradenia sa pošle ako informácia (AssignFace.source)
        val sources = faceIds.chunked(MergeUndo.ID_CHUNK).flatMap { people.getAssignmentsForFaces(it) }
            .associate { it.faceId to it.source }
        faceDao.getFacesByIds(faceIds).forEach { f ->
            faces.refFor(f)?.let { append(AssignFace(it, uuid, true, f.id?.let { id -> sources[id] })) }
        }
        anchors.markDirty(listOf(localId))
    }

    fun facesRejected(faceIds: List<Long>, localId: Long) {
        if (faceIds.isEmpty()) return
        val uuid = existingUuid(localId) ?: return // osobu druhý telefón nepozná
        faceDao.getFacesByIds(faceIds).forEach { f -> faces.refFor(f)?.let { append(UnassignFace(it, uuid)) } }
        anchors.markDirty(listOf(localId))
    }

    fun groupChanged(groupId: Long) {
        val group = extras.getGroups().firstOrNull { it.id == groupId } ?: return
        append(GroupSet(group.name, extras.getMembers(groupId).map { uuidFor(it) }))
    }
}

// Zámok pre zápisy, ktoré robí recorder aj synchronizácia z iného vlákna (UUID osoby, zoznam odtlačkov).
internal object FamilyLock
