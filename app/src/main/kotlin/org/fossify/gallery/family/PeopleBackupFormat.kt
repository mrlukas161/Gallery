package org.fossify.gallery.family

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Záloha osôb `.galeria-family` (F6, spec 2.3 posledný bod): ZIP s jedným súborom people.json.
//
//   {"format":"galeria-family","v":1,"id":"<uuid zálohy>","created":<ms>,"device":"<názov>",
//    "face_model":"<FaceEmbedder.MODEL_ID>","ops":[{id, lamport, type, payload}, …]}
//
// „ops“ je ten istý zoznam ako pri prenose medzi telefónmi (OpJson, payload = OpCodec.encode):
// snapshot Ľudí ako operácie op-logu (PersonCreate, PersonRename, AssignFace s contentKey + RelBox,
// GroupSet, AnchorsSet ≤ 20 odtlačkov). Obnova ich prijme ako operácie od „zariadenia zálohy“
// (deviceId = backup:<id>) cez OpApplier — potichu pridá, čo chýba, rozpory sú návrhy.
//
// Doplnené kľúče (balík A3, verzia ostáva 1 — staršia appka ich ignoruje), mimo op-logu, lebo ide
// o lokálne rozhodnutia, ktoré sa medzi telefónmi neposielajú:
//   "auto_assign":   [{"ck","boxes","uuid"[,"src","conf"]}, …]  automatické priradenia tvárí (E2: pôvod, istota)
//   "cannot_links":  [{"ck","boxes","uuid"}, …]  „toto nie je táto osoba“
//   "ignored_faces": [{"ck","boxes"}, …]          ignorované tváre
// Obnova ich len dopĺňa (PeopleBackupExtras), nič neprepisuje.
// Čistá logika bez Androidu (PeopleBackupFormatTest). Kľúče len pridávať, nikdy nepremenovať.
object PeopleBackupFormat {
    const val FORMAT = "galeria-family"
    const val VERSION = 1
    const val EXTENSION = "galeria-family"
    const val MIME_TYPE = "application/octet-stream"
    const val ENTRY = "people.json"
    const val DEVICE_PREFIX = "backup:"

    // ochrana pred poškodeným alebo podvrhnutým súborom (rozbalený JSON)
    const val MAX_JSON_BYTES = 64L * 1024 * 1024

    private const val K_FORMAT = "format"
    private const val K_VERSION = "v"
    private const val K_ID = "id"
    private const val K_CREATED = "created"
    private const val K_DEVICE = "device"
    private const val K_MODEL = "face_model"
    private const val K_OPS = "ops"
    private const val BUFFER = 16 * 1024

    data class Backup(
        val id: String,
        val createdAt: Long,
        val deviceName: String?,
        val faceModelId: String,
        val ops: List<OpEntity>,
        val extras: Extras = Extras(),
    ) {
        val deviceId: String get() = deviceIdFor(id)
    }

    // tvár (obsahový kľúč fotky + rámček) a rodinné UUID osoby; E2: pôvod a istota automatického priradenia
    // (doplnené kľúče "src"/"conf", nepovinné — staršie zálohy ich nemajú)
    data class FaceLink(val face: FaceRef, val uuid: String, val source: String? = null, val confidence: Float? = null)

    // lokálne rozhodnutia mimo op-logu (A3)
    data class Extras(
        val auto: List<FaceLink> = emptyList(),
        val cannot: List<FaceLink> = emptyList(),
        val ignored: List<FaceRef> = emptyList(),
    )

    // osoba v snapshote: rodinné UUID a aktuálne meno (null = nepomenovaná)
    data class SnapPerson(val uuid: String, val name: String?)

    data class SnapGroup(val name: String, val memberUuids: List<String>)

    // Snapshot Ľudí ako operácie (rovnaké typy ako F3 snapshot po spárovaní): najprv osoby, potom
    // ručné priradenia, skupiny a odtlačky. Pomenovaná osoba dostane aj PersonRename s previousName = null:
    // na telefóne, kde má osoba iné meno, z neho vznikne návrh „prevziať / ponechať“, nepomenovanej
    // osobe sa meno doplní potichu a rovnaké meno sa preskočí.
    fun snapshotOps(
        persons: List<SnapPerson>,
        manual: List<Pair<FaceRef, String>>,
        groups: List<SnapGroup>,
        anchors: Map<String, List<FloatArray>>,
        faceModelId: String,
    ): List<Op> {
        val ops = ArrayList<Op>()
        persons.forEach { ops.add(PersonCreate(it.uuid, it.name)) }
        persons.forEach { p -> p.name?.takeIf { it.isNotBlank() }?.let { ops.add(PersonRename(p.uuid, it, null)) } }
        manual.forEach { (face, uuid) -> ops.add(AssignFace(face, uuid, true)) }
        groups.forEach { ops.add(GroupSet(it.name, it.memberUuids)) }
        persons.forEach { p ->
            val vectors = anchors[p.uuid].orEmpty().take(AnchorsSet.MAX_ANCHORS)
            if (vectors.isNotEmpty()) ops.add(AnchorsSet(p.uuid, faceModelId, vectors))
        }
        return ops
    }

    fun deviceIdFor(backupId: String) = "$DEVICE_PREFIX$backupId"

    fun isBackupDevice(deviceId: String) = deviceId.startsWith(DEVICE_PREFIX)

    // Operácie snapshotu v danom poradí → záznamy op-logu zálohy: lamport 1…n, opId „<id zálohy>:<n>“.
    // Rovnaká záloha obnovená znova má rovnaké opId, takže sa nič neaplikuje dvakrát.
    fun build(
        id: String,
        createdAt: Long,
        deviceName: String?,
        faceModelId: String,
        ops: List<Op>,
        extras: Extras = Extras(),
    ): Backup {
        val device = deviceIdFor(id)
        val entities = ops.mapIndexed { i, op ->
            val lamport = i + 1L
            OpEntity("$id:$lamport", device, lamport, op.type, OpCodec.encode(op))
        }
        return Backup(id, createdAt, deviceName, faceModelId, entities, extras)
    }

    fun toJson(backup: Backup): JSONObject {
        val o = JSONObject()
            .put(K_FORMAT, FORMAT)
            .put(K_VERSION, VERSION)
            .put(K_ID, backup.id)
            .put(K_CREATED, backup.createdAt)
            .put(K_MODEL, backup.faceModelId)
            .put(K_OPS, OpJson.toJson(backup.ops))
        backup.deviceName?.let { o.put(K_DEVICE, it) }
        PeopleBackupExtrasJson.put(backup.extras, o)
        return o
    }

    // null = nie je to záloha Galérie+, je z novšej verzie formátu alebo je poškodená
    fun fromJson(o: JSONObject): Backup? {
        if (o.optString(K_FORMAT) != FORMAT) return null
        val version = o.optInt(K_VERSION, -1)
        if (version !in 1..VERSION) return null
        val id = o.optString(K_ID)
        if (id.isEmpty() || id.contains(':')) return null
        val device = deviceIdFor(id)
        val ops = OpJson.fromJson(o.optJSONArray(K_OPS), device)
        val name = if (o.has(K_DEVICE) && !o.isNull(K_DEVICE)) o.optString(K_DEVICE) else null
        val extras = PeopleBackupExtrasJson.read(o)
        return Backup(id, o.optLong(K_CREATED, 0), name, o.optString(K_MODEL), ops, extras)
    }

    @Throws(IOException::class)
    fun write(backup: Backup, out: OutputStream) {
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(ENTRY))
        zip.write(toJson(backup).toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        zip.flush()
    }

    // null = súbor nie je platná záloha osôb
    @Throws(IOException::class)
    fun read(input: InputStream): Backup? {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: return null
            if (entry.isDirectory || entry.name != ENTRY) continue
            val bytes = readLimited(zip) ?: return null
            return try {
                fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
            } catch (ignored: JSONException) {
                null
            }
        }
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_JSON_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
