package org.fossify.gallery.family

import android.content.Context
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.IndexScope
import org.fossify.gallery.faces.MergeUndo
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PeopleIndex
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

// Zachytávanie zmien v Ľuďoch do op-logu (F3). Volá sa z miest, kde UI zapisuje cez PeopleDao/ExtrasDao,
// VŽDY až po úspešnom zápise. Recorder NIKDY nezablokuje ani nezhodí pôvodnú akciu: práca beží na
// vlastnom vlákne na pozadí (v poradí volaní) a každá chyba sa zahodí. Keď nie je spárovaný žiadny
// telefón, nerobí nič (stav pred spárovaním prenesie FamilySync.snapshotIfNeeded).
object FamilyRecorder {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    // nová osoba (aj s tvárami, ktoré dostala pri založení); zverejní sa až s tvárou z povoleného priečinka
    fun personCreated(context: Context, localId: Long, faceIds: List<Long> = emptyList()) = record(context) {
        it.facesAssigned(faceIds, localId)
        it.publishedUuid(localId)
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
        it.groupRenamed(oldName, newName)
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
//
// Súkromie (spec 2.5): osoba sa druhému telefónu ukáže (PersonCreate s menom) až vtedy, keď má aspoň jednu
// tvár z povoleného priečinka (PublishRules). Dovtedy môže mať rodinné UUID len lokálne (záloha osôb), ale jej
// meno, premenovanie, zlúčenie, zmazanie ani členstvo v skupine neodíde.
@Suppress("TooManyFunctions")
class OpRecorder(context: Context, private val clock: () -> Long = System::currentTimeMillis) : Closeable {
    private val app = context.applicationContext
    private val family = FamilyDatabase.getInstance(app)
    private val meta = family.metaDao()
    private val people = PeopleDatabase.getInstance(app).PeopleDao()
    private val extras = ExtrasDatabase.getInstance(app).ExtrasDao()
    private val faceDao = FacesDatabase.getInstance(app).FaceDao()
    private val familyPersons = family.familyPersonDao()
    private val prefs = FamilyPrefs(app)
    private val scope by lazy { IndexScope(app) }
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
    // Lamport a zápis operácie sú v jednej transakcii family.db: operácia s nižším lamport sa tak nikdy
    // neobjaví až po vyššej, ktorú druhá strana už potvrdila (inak by sa navždy stratila). Hodiny idú aj nad
    // najvyššiu kedy rozdanú hodnotu mimo DB (obnova staršej family.db zo zálohy).
    fun append(op: Op): LoggedOp {
        val deviceId = meta.deviceId()
        val opId = UUID.randomUUID().toString()
        val highWater = prefs.lamportHighWater
        val lamport = family.runInTransaction(
            Callable {
                val next = meta.nextLamport(highWater)
                family.opDao().insertOp(OpEntity(opId, deviceId, next, op.type, OpCodec.encode(op), clock()))
                next
            },
        )
        prefs.lamportHighWater = lamport
        return LoggedOp(opId, deviceId, lamport, op)
    }

    fun existingUuid(localId: Long): String? = familyPersons.getPersonByLocalId(localId)?.familyUuid

    // Rodinné UUID len pre tento telefón (záloha osôb F6): nič neodchádza. Zverejní ho až publishedUuid.
    fun localUuid(localId: Long): String = familyExclusive {
        existingUuid(localId) ?: UUID.randomUUID().toString().also {
            familyPersons.upsertPerson(FamilyPersonEntity(it, localId))
        }
    }

    // Zverejnené rodinné UUID osoby. Pri prvom zverejnení odíde PersonCreate s aktuálnym menom — len keď má
    // osoba tvár z povoleného priečinka. null = osoba sa druhému telefónu (zatiaľ) neukazuje.
    fun publishedUuid(localId: Long): String? = familyExclusive {
        val existing = existingUuid(localId)
        when {
            existing != null && isPublished(existing) -> existing
            !hasAllowedFace(localId) -> null
            else -> publish(existing ?: UUID.randomUUID().toString(), localId)
        }
    }

    private fun publish(uuid: String, localId: Long): String {
        val name = people.getPerson(localId)?.name
        val logged = append(PersonCreate(uuid, name))
        familyPersons.upsertPerson(FamilyPersonEntity(uuid, localId, logged.lamport, logged.deviceId))
        meta.put(MetaEntity(publishedKey(uuid), logged.lamport.toString()))
        return uuid
    }

    // Pozná osobu druhý telefón? (vlastný alebo jeho PersonCreate v op-logu; zo zálohy osôb nie)
    fun isPublished(uuid: String): Boolean {
        if (meta.get(publishedKey(uuid)) != null) return true
        val known = family.opDao().countPersonCreates("%$uuid%", PeopleBackupFormat.DEVICE_PREFIX + "%") > 0
        if (known) meta.put(MetaEntity(publishedKey(uuid), "1"))
        return known
    }

    // zverejnené UUID osoby, ktorá ho už má (bez zakladania)
    private fun publishedExisting(localId: Long): String? = existingUuid(localId)?.takeIf { isPublished(it) }

    private fun hasAllowedFace(localId: Long): Boolean {
        val faceIds = people.getAssignmentsForPerson(localId).map { it.faceId }
        val paths = faceIds.chunked(MergeUndo.ID_CHUNK).asSequence()
            .flatMap { chunk -> faceDao.getFacesByIds(chunk).asSequence().map { it.mediaFullPath } }
        return PublishRules.hasAllowedFace(paths) { scope.allows(it) }
    }

    // aj rozhodnutie o návrhu premenovania (D8: FamilyProposals), aby obe strany došli k rovnakému menu
    fun personRenamed(localId: Long, oldName: String?, newName: String) {
        val uuid = publishedExisting(localId)
        if (uuid == null) {
            publishedUuid(localId) // PersonCreate (ak sa osoba práve zverejní) už nesie nové meno
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
        val sources = fromIds.filter { it != toId }.mapNotNull { publishedExisting(it) }
        if (sources.isEmpty()) return
        val toUuid = publishedUuid(toId) ?: return
        sources.filter { it != toUuid }.forEach { append(PersonMerge(it, toUuid)) }
        anchors.markDirty(listOf(toId))
    }

    fun personDeleted(localId: Long, name: String?) {
        publishedExisting(localId)?.let { append(PersonDelete(it, name)) }
    }

    fun facesAssigned(faceIds: List<Long>, localId: Long) {
        if (faceIds.isEmpty()) return
        // tváre zo zamknutých/vylúčených priečinkov (a nejasné) nemajú FaceRef → bez nich sa nič neposiela
        val refs = faceIds.chunked(MergeUndo.ID_CHUNK).flatMap { faceDao.getFacesByIds(it) }
            .mapNotNull { f -> faces.refFor(f)?.let { f to it } }
        if (refs.isEmpty()) return
        val uuid = publishedUuid(localId) ?: return
        // E2: pôvod priradenia sa pošle ako informácia (AssignFace.source)
        val sources = faceIds.chunked(MergeUndo.ID_CHUNK).flatMap { people.getAssignmentsForFaces(it) }
            .associate { it.faceId to it.source }
        refs.forEach { (f, ref) -> append(AssignFace(ref, uuid, true, f.id?.let { id -> sources[id] })) }
        anchors.markDirty(listOf(localId))
    }

    fun facesRejected(faceIds: List<Long>, localId: Long) {
        if (faceIds.isEmpty()) return
        val uuid = publishedExisting(localId) ?: return // osobu druhý telefón nepozná
        faceDao.getFacesByIds(faceIds).forEach { f -> faces.refFor(f)?.let { append(UnassignFace(it, uuid)) } }
        anchors.markDirty(listOf(localId))
    }

    fun groupChanged(groupId: Long) {
        val group = extras.getGroups().firstOrNull { it.id == groupId } ?: return
        val members = extras.getMembers(groupId)
        trackRemovedMembers(group.name, members)
        val uuids = PublishRules.groupMembers(members) { publishedUuid(it) } ?: return
        append(GroupSet(group.name, uuids))
    }

    // premenovanie skupiny odíde len vtedy, keď ju druhý telefón môže poznať (má zverejneného člena)
    fun groupRenamed(oldName: String, newName: String) {
        if (oldName == newName) return
        meta.get(removedKey(oldName))?.let {
            meta.put(MetaEntity(removedKey(newName), it))
            meta.delete(removedKey(oldName))
        }
        val group = extras.getGroups().firstOrNull { it.name == newName } ?: return
        if (extras.getMembers(group.id).none { publishedExisting(it) != null }) return
        append(GroupRename(oldName, newName))
    }

    // Kto v skupine bol (podľa GroupSet tu aj z druhého telefónu) a teraz nie je: prijatý GroupSet ho späť
    // nepridá (LocalFamilyState.removedFromGroup). Opätovne pridaný člen zo zoznamu vypadne.
    private fun trackRemovedMembers(groupName: String, members: List<Long>) {
        val known = family.opDao().getAllOpsOfType(OpType.GROUP_SET)
            .mapNotNull { OpCodec.decode(it.type, it.payloadJson) as? GroupSet }
            .filter { it.name == groupName }
            .flatMapTo(HashSet()) { it.memberUuids }
        val current = members.mapNotNullTo(HashSet()) { existingUuid(it) }
        val before = PublishRules.decodeSet(meta.get(removedKey(groupName)))
        val removed = PublishRules.removedMembers(known, current, before)
        when {
            removed == before -> Unit
            removed.isEmpty() -> meta.delete(removedKey(groupName))
            else -> meta.put(MetaEntity(removedKey(groupName), PublishRules.encodeSet(removed)))
        }
    }

    companion object {
        private const val PUBLISHED_PREFIX = "f068_published:"
        private const val GROUP_REMOVED_PREFIX = "f068_group_removed:"

        fun publishedKey(uuid: String) = "$PUBLISHED_PREFIX$uuid"

        // kľúč v tabuľke meta: rodinné UUID lokálne odobratých členov skupiny
        fun removedKey(groupName: String) = "$GROUP_REMOVED_PREFIX$groupName"
    }
}

// Jeden zámok pre zápisy rodinného prepojenia z rôznych vlákien: recorder (UUID osoby, zoznam odtlačkov) aj
// JEDINÝ vlastník aplikovania operácií (FamilySync.retryPending, rozhodnutia o návrhoch, obnova zálohy osôb).
// Bez neho by vlákno servera a klient naraz aplikovali tie isté operácie a osoba by vznikla dvakrát.
internal object FamilyLock

internal inline fun <T> familyExclusive(block: () -> T): T = synchronized(FamilyLock) { block() }
