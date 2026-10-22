package org.fossify.gallery.family

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

// JSON payload operácií (ops.payload_json) a návrhov (proposals.payload_json).
// Formát je súčasťou prenosu medzi telefónmi: kľúče len pridávať, nikdy nepremenovať.
// Odtlačky sa posielajú ako Base64 float32 little-endian (nezávisle od poradia bajtov zariadenia).
object OpCodec {
    private const val UUID = "uuid"
    private const val NAME = "name"
    private const val PREV = "prev"
    private const val FROM = "from"
    private const val TO = "to"
    private const val FACE = "face"
    private const val MANUAL = "manual"
    private const val MEMBERS = "members"
    private const val OLD = "old"
    private const val NEW = "new"
    private const val MODEL = "model"
    private const val VECTORS = "vectors"
    private const val KEY = "ck"
    private const val BOXES = "boxes"
    private const val LOCAL = "local"
    private const val LOCAL_FROM = "local_from"
    private const val LOCAL_TO = "local_to"
    private const val FACE_ID = "face_id"
    private const val THEIRS = "theirs"
    private const val MINE = "mine"
    private const val BOX_VALUES = 4
    private const val LEFT = 0
    private const val TOP = 1
    private const val RIGHT = 2
    private const val BOTTOM = 3

    fun encode(op: Op): String = when (op) {
        is PersonCreate -> obj(UUID to op.uuid, NAME to op.name)
        is PersonRename -> obj(UUID to op.uuid, NAME to op.name, PREV to op.previousName)
        is PersonMerge -> obj(FROM to op.fromUuid, TO to op.toUuid)
        is PersonDelete -> obj(UUID to op.uuid, NAME to op.name)
        is AssignFace -> obj(FACE to faceJson(op.face), UUID to op.uuid, MANUAL to op.manual)
        is UnassignFace -> obj(FACE to faceJson(op.face), UUID to op.uuid)
        is GroupSet -> obj(NAME to op.name, MEMBERS to JSONArray(op.memberUuids))
        is GroupRename -> obj(OLD to op.oldName, NEW to op.newName)
        is AnchorsSet -> obj(
            UUID to op.uuid,
            MODEL to op.faceModelId,
            VECTORS to JSONArray(op.vectors.map { AnchorVectors.toBase64(it) }),
        )
    }.toString()

    // null = neznámy typ (novšia verzia appky) alebo poškodený payload; operácia ostane odložená
    fun decode(type: String, json: String): Op? = try {
        val o = JSONObject(json)
        when (type) {
            OpType.PERSON_CREATE -> PersonCreate(o.getString(UUID), o.optStr(NAME))
            OpType.PERSON_RENAME -> PersonRename(o.getString(UUID), o.getString(NAME), o.optStr(PREV))
            OpType.PERSON_MERGE -> PersonMerge(o.getString(FROM), o.getString(TO))
            OpType.PERSON_DELETE -> PersonDelete(o.getString(UUID), o.optStr(NAME))
            OpType.ASSIGN_FACE -> AssignFace(face(o.getJSONObject(FACE)), o.getString(UUID), o.getBoolean(MANUAL))
            OpType.UNASSIGN_FACE -> UnassignFace(face(o.getJSONObject(FACE)), o.getString(UUID))
            OpType.GROUP_SET -> GroupSet(o.getString(NAME), strings(o.getJSONArray(MEMBERS)))
            OpType.GROUP_RENAME -> GroupRename(o.getString(OLD), o.getString(NEW))
            OpType.ANCHORS_SET -> AnchorsSet(
                o.getString(UUID),
                o.getString(MODEL),
                strings(o.getJSONArray(VECTORS)).map { AnchorVectors.fromBase64(it) },
            )

            else -> null
        }
    } catch (ignored: JSONException) {
        null
    } catch (ignored: IllegalArgumentException) {
        null // poškodený Base64
    }

    fun encodeProposal(p: Proposal): String = when (p) {
        is RenameProposal -> obj(UUID to p.uuid, LOCAL to p.localId, THEIRS to p.theirName, MINE to p.myName)
        is SameNameProposal -> obj(UUID to p.uuid, NAME to p.name, LOCAL to p.localId)
        is MergeProposal ->
            obj(FROM to p.fromUuid, TO to p.toUuid, LOCAL_FROM to p.fromLocalId, LOCAL_TO to p.toLocalId)

        is FaceMoveProposal ->
            obj(FACE to faceJson(p.face), FACE_ID to p.faceId, LOCAL_FROM to p.fromLocalId, LOCAL_TO to p.toLocalId)

        is FaceRemoveProposal -> obj(FACE to faceJson(p.face), FACE_ID to p.faceId, LOCAL to p.localId)
        is DeleteProposal -> obj(UUID to p.uuid, LOCAL to p.localId, NAME to p.name)
    }.toString()

    fun decodeProposal(type: String, json: String): Proposal? = try {
        val o = JSONObject(json)
        when (type) {
            ProposalType.RENAME ->
                RenameProposal(o.getString(UUID), o.getLong(LOCAL), o.getString(THEIRS), o.optStr(MINE))

            ProposalType.SAME_NAME -> SameNameProposal(o.getString(UUID), o.getString(NAME), o.getLong(LOCAL))
            ProposalType.MERGE ->
                MergeProposal(o.getString(FROM), o.getString(TO), o.getLong(LOCAL_FROM), o.getLong(LOCAL_TO))

            ProposalType.FACE_MOVE -> FaceMoveProposal(
                face(o.getJSONObject(FACE)),
                o.getLong(FACE_ID),
                o.getLong(LOCAL_FROM),
                o.getLong(LOCAL_TO),
            )

            ProposalType.FACE_REMOVE ->
                FaceRemoveProposal(face(o.getJSONObject(FACE)), o.getLong(FACE_ID), o.getLong(LOCAL))

            ProposalType.DELETE -> DeleteProposal(o.getString(UUID), o.getLong(LOCAL), o.optStr(NAME))
            else -> null
        }
    } catch (ignored: JSONException) {
        null
    }

    private fun faceJson(f: FaceRef): JSONObject {
        val boxes = f.boxes.map { b -> JSONArray(listOf(b.left, b.top, b.right, b.bottom).map(Float::toDouble)) }
        return JSONObject().put(KEY, f.contentKey).put(BOXES, JSONArray(boxes))
    }

    private fun face(o: JSONObject): FaceRef {
        val arr = o.getJSONArray(BOXES)
        val boxes = (0 until arr.length()).map { i ->
            val b = arr.getJSONArray(i)
            val v = (0 until b.length()).map { b.getDouble(it).toFloat() }
            if (v.size != BOX_VALUES) throw JSONException("rámček musí mať $BOX_VALUES čísla")
            RelBox(v[LEFT], v[TOP], v[RIGHT], v[BOTTOM])
        }
        return FaceRef(o.getString(KEY), boxes)
    }

    private fun strings(arr: JSONArray): List<String> = (0 until arr.length()).map { arr.getString(it) }

    // null hodnoty sa do JSON nezapíšu (optStr ich potom vráti ako null)
    private fun obj(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        pairs.forEach { (k, v) -> if (v != null) o.put(k, v) }
        return o
    }

    private fun JSONObject.optStr(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}

// Odtlačky na prenos: Base64 z float32 little-endian (nezávisle od poradia bajtov zariadenia).
object AnchorVectors {
    private const val FLOAT_BYTES = 4

    fun toBase64(v: FloatArray): String {
        val bb = ByteBuffer.allocate(v.size * FLOAT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putFloat(it) }
        return Base64.getEncoder().encodeToString(bb.array())
    }

    fun fromBase64(s: String): FloatArray {
        val bb = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bb.remaining() / FLOAT_BYTES) { bb.float }
    }
}
