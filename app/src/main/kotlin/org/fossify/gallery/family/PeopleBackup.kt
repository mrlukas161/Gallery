package org.fossify.gallery.family

import android.content.Context
import android.net.Uri
import org.fossify.gallery.faces.CannotLinkEntity
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceAssignmentEntity
import org.fossify.gallery.faces.FaceDao
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.IgnoredFaceEntity
import org.fossify.gallery.faces.PeopleDatabase
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

// Záloha a obnova osôb (F6): súbor .galeria-family (PeopleBackupFormat) cez SAF.
// Záloha = snapshot Ľudí v tvare op-logu (ako F3 snapshot po spárovaní). Obnova = príjem operácií
// od „zariadenia zálohy“ cez OpApplier (PeopleRestore): potichu doplní, čo chýba, rozpory sú návrhy
// v Ľuďoch. Nič neprepisuje ani nemaže. Do people.db a extras.db zapisuje len LocalActionSink cez
// existujúce DAO metódy. Všetko blokuje (DB, súbory) → volať len z pozadia.
class PeopleBackup(context: Context) {
    private val app = context.applicationContext
    private val family = FamilyDatabase.getInstance(app)

    // omitted = tváre s rozhodnutím (priradenie, „nie je to on“, ignorovaná), ktoré sa do zálohy
    // nedostali: zamknuté/vylúčené priečinky alebo fotka, ktorá sa nedá prečítať
    data class Summary(
        val persons: Int,
        val assignments: Int,
        val groups: Int,
        val anchors: Int,
        val autoAssignments: Int = 0,
        val cannotLinks: Int = 0,
        val ignored: Int = 0,
        val omitted: Int = 0,
    )

    sealed interface RestoreResult {
        data class Done(val report: ApplyReport, val extras: ExtrasReport = ExtrasReport()) : RestoreResult

        data object Invalid : RestoreResult
    }

    // Zostaví zálohu z aktuálneho stavu. Osoby bez rodinného UUID ho dostanú (zapíše sa ich PersonCreate
    // do vlastného op-logu ako pri spárovaní), aby obnova tej istej zálohy osoby nezdvojila.
    fun create(): Pair<PeopleBackupFormat.Backup, Summary> {
        val people = PeopleDatabase.getInstance(app).PeopleDao()
        val faceDao = FacesDatabase.getInstance(app).FaceDao()
        val extras = ExtrasDatabase.getInstance(app).ExtrasDao()
        val recorder = OpRecorder(app)
        val persons = people.getPersons()
        val uuids = persons.associate { it.id to recorder.uuidFor(it.id) }

        // ručné priradenia (tváre zo zamknutých a vylúčených priečinkov sa nezálohujú, spec 2.5)
        val faces = FamilyFaces(app, faceDao, ContentKeyStore(family.contentKeyDao()))
        val assignments = people.getAssignments().filter { it.personId in uuids }
        val manual = assignments.filter { it.isManual }
        val refs = links(faceDao, faces, manual.map { it.faceId to it.personId }, uuids)
            .map { it.face to it.uuid }

        // A3: automatické priradenia, „nie je to on“ a ignorované tváre (mimo op-logu)
        val auto = links(faceDao, faces, assignments.filter { !it.isManual }.map { it.faceId to it.personId }, uuids)
        val cannotSource = people.getCannotLinks().filter { it.personId in uuids }
        val cannot = links(faceDao, faces, cannotSource.map { it.faceId to it.personId }, uuids)
        val ignoredIds = extras.getIgnoredIds()
        val ignored = facesByIds(faceDao, ignoredIds).mapNotNull { faces.refFor(it) }
        val omitted = (assignments.size - refs.size - auto.size) + (cannotSource.size - cannot.size) +
            (ignoredIds.size - ignored.size)

        val groups = extras.getGroups().map { g ->
            PeopleBackupFormat.SnapGroup(g.name, extras.getMembers(g.id).mapNotNull { uuids[it] })
        }
        val anchors = AnchorOutbox.pick(app, persons.map { it.id })
            .mapNotNull { (id, vectors) -> uuids[id]?.let { it to vectors } }.toMap()

        val ops = PeopleBackupFormat.snapshotOps(
            persons = persons.map { PeopleBackupFormat.SnapPerson(uuids.getValue(it.id), it.name) },
            manual = refs,
            groups = groups,
            anchors = anchors,
            faceModelId = FaceEmbedder.MODEL_ID,
        )
        val backup = PeopleBackupFormat.build(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            FamilyStore(app).selfName(),
            FaceEmbedder.MODEL_ID,
            ops,
            PeopleBackupFormat.Extras(auto, cannot, ignored),
        )
        val summary = Summary(
            persons.size, refs.size, groups.size, anchors.values.count { it.isNotEmpty() },
            auto.size, cannot.size, ignored.size, maxOf(0, omitted),
        )
        return backup to summary
    }

    @Throws(IOException::class)
    fun export(uri: Uri): Summary {
        val out = openOutput(uri) ?: throw IOException("súbor sa nedá otvoriť na zápis")
        return out.use { write(it) }
    }

    // zostaví zálohu a zapíše ju (automatická záloha A3 si cieľ otvára sama)
    @Throws(IOException::class)
    fun write(out: OutputStream): Summary {
        val (backup, summary) = create()
        PeopleBackupFormat.write(backup, out)
        return summary
    }

    // (tvár, lokálna osoba) → FaceLink; tváre bez FaceRef (zamknutý priečinok, nečitateľná fotka) vypadnú
    private fun links(
        faceDao: FaceDao,
        faces: FamilyFaces,
        pairs: List<Pair<Long, Long>>,
        uuids: Map<Long, String>,
    ): List<PeopleBackupFormat.FaceLink> {
        val byFace = pairs.groupBy({ it.first }, { it.second })
        return facesByIds(faceDao, byFace.keys.toList()).flatMap { f ->
            val persons = f.id?.let { byFace[it] }.orEmpty()
            val ref = if (persons.isEmpty()) null else faces.refFor(f)
            if (ref == null) {
                emptyList()
            } else {
                persons.mapNotNull { pid -> uuids[pid]?.let { PeopleBackupFormat.FaceLink(ref, it) } }
            }
        }
    }

    private fun facesByIds(faceDao: FaceDao, ids: List<Long>) =
        ids.chunked(QUERY_CHUNK).flatMap { faceDao.getFacesByIds(it) }

    // null = súbor nie je platná záloha osôb
    @Throws(IOException::class)
    fun read(uri: Uri): PeopleBackupFormat.Backup? {
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("súbor sa nedá otvoriť")
        return input.use { PeopleBackupFormat.read(it) }
    }

    // sourceLabel = ako sa záloha ukáže pri návrhoch v Ľuďoch (namiesto názvu telefónu)
    @Suppress("TooGenericExceptionCaught")
    fun restore(backup: PeopleBackupFormat.Backup, sourceLabel: String): RestoreResult {
        if (backup.ops.isEmpty()) return RestoreResult.Invalid
        family.metaDao().put(MetaEntity(labelKey(backup.deviceId), sourceLabel))
        val report = FamilyAutoSync.exclusive {
            val sync = FamilySync(app)
            // tváre sa hľadajú podľa obsahového kľúča fotky (lenivo, potom z cache)
            sync.prepareFaceKeys()
            val local = FamilyLocal(app)
            PeopleRestore.restore(
                backup,
                OpApplier(local.ownDeviceId, FaceEmbedder.MODEL_ID),
                local.store,
                local.state,
                local.sink,
            )
        }
        val extras = try {
            restoreExtras(backup.extras)
        } catch (e: RuntimeException) {
            android.util.Log.w(TAG, "doplnky zálohy osôb sa nepodarilo obnoviť", e)
            ExtrasReport()
        }
        return RestoreResult.Done(report, extras)
    }

    // A3: automatické priradenia, „nie je to on“ a ignorované tváre — len doplnenie (PeopleBackupExtras)
    private fun restoreExtras(backupExtras: PeopleBackupFormat.Extras): ExtrasReport {
        if (backupExtras.auto.isEmpty() && backupExtras.cannot.isEmpty() && backupExtras.ignored.isEmpty()) {
            return ExtrasReport()
        }
        val people = PeopleDatabase.getInstance(app).PeopleDao()
        val extrasDao = ExtrasDatabase.getInstance(app).ExtrasDao()
        val state = LocalFamilyState(app)
        val cannot = people.getCannotLinks().map { it.faceId to it.personId }.toHashSet()
        val ignored = extrasDao.getIgnoredIds().toHashSet()
        val target = object : ExtrasTarget {
            override fun findFace(ref: FaceRef): Long? = state.findFace(ref)

            override fun localPersonId(uuid: String): Long? = state.localPersonId(uuid)

            override fun assignment(faceId: Long): LocalAssignment? = state.assignment(faceId)

            override fun isCannot(faceId: Long, personId: Long): Boolean = (faceId to personId) in cannot

            override fun isIgnored(faceId: Long): Boolean = faceId in ignored
        }
        val (actions, report) = PeopleBackupExtras.plan(backupExtras, target)
        val now = System.currentTimeMillis()
        actions.forEach { a ->
            when (a) {
                is ExtrasAction.AutoAssign ->
                    people.upsertAssignment(FaceAssignmentEntity(a.faceId, a.personId, false, now))

                is ExtrasAction.CannotLink -> people.insertCannotLink(CannotLinkEntity(a.faceId, a.personId))
                is ExtrasAction.Ignore -> extrasDao.insertIgnored(IgnoredFaceEntity(a.faceId))
            }
        }
        return report
    }

    private fun openOutput(uri: Uri) = try {
        // „wt“ = prepísať celý súbor; niektorí poskytovatelia poznajú len „w“
        app.contentResolver.openOutputStream(uri, "wt")
    } catch (ignored: FileNotFoundException) {
        app.contentResolver.openOutputStream(uri, "w")
    } catch (ignored: IllegalArgumentException) {
        app.contentResolver.openOutputStream(uri, "w")
    }

    companion object {
        private const val TAG = "PeopleBackup"
        private const val QUERY_CHUNK = 500
        private const val LABEL_PREFIX = "f6_label:"

        // kľúč v tabuľke meta: popis zálohy pre návrhy (FamilyProposals)
        fun labelKey(deviceId: String) = "$LABEL_PREFIX$deviceId"
    }
}
