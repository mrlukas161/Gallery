package org.fossify.gallery.family

import android.content.Context
import android.net.Uri
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

// Záloha a obnova osôb (F6): súbor .galeria-family (PeopleBackupFormat) cez SAF.
// Záloha = snapshot Ľudí v tvare op-logu (ako F3 snapshot po spárovaní). Obnova = príjem operácií
// od „zariadenia zálohy“ cez OpApplier (PeopleRestore): potichu doplní, čo chýba, rozpory sú návrhy
// v Ľuďoch. Nič neprepisuje ani nemaže. Do people.db a extras.db zapisuje len LocalActionSink cez
// existujúce DAO metódy. Všetko blokuje (DB, súbory) → volať len z pozadia.
class PeopleBackup(context: Context) {
    private val app = context.applicationContext
    private val family = FamilyDatabase.getInstance(app)

    data class Summary(val persons: Int, val assignments: Int, val groups: Int, val anchors: Int)

    sealed interface RestoreResult {
        data class Done(val report: ApplyReport) : RestoreResult

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
        val manual = people.getAssignments().filter { it.isManual && it.personId in uuids }
        val byFace = manual.associate { it.faceId to it.personId }
        val refs = manual.map { it.faceId }.chunked(QUERY_CHUNK).flatMap { faceDao.getFacesByIds(it) }
            .mapNotNull { f ->
                val uuid = f.id?.let { byFace[it] }?.let { uuids[it] } ?: return@mapNotNull null
                faces.refFor(f)?.let { it to uuid }
            }

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
        )
        val summary = Summary(persons.size, refs.size, groups.size, anchors.values.count { it.isNotEmpty() })
        return backup to summary
    }

    @Throws(IOException::class)
    fun export(uri: Uri): Summary {
        val (backup, summary) = create()
        val out = openOutput(uri) ?: throw IOException("súbor sa nedá otvoriť na zápis")
        out.use { PeopleBackupFormat.write(backup, it) }
        return summary
    }

    // null = súbor nie je platná záloha osôb
    @Throws(IOException::class)
    fun read(uri: Uri): PeopleBackupFormat.Backup? {
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("súbor sa nedá otvoriť")
        return input.use { PeopleBackupFormat.read(it) }
    }

    // sourceLabel = ako sa záloha ukáže pri návrhoch v Ľuďoch (namiesto názvu telefónu)
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
        return RestoreResult.Done(report)
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
        private const val QUERY_CHUNK = 500
        private const val LABEL_PREFIX = "f6_label:"

        // kľúč v tabuľke meta: popis zálohy pre návrhy (FamilyProposals)
        fun labelKey(deviceId: String) = "$LABEL_PREFIX$deviceId"
    }
}
