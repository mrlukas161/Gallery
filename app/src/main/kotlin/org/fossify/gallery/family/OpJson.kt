package org.fossify.gallery.family

import org.json.JSONArray
import org.json.JSONObject

// Zoznam operácií op-logu v JSON: [{id, lamport, type, payload}]. Rovnaký tvar sa posiela medzi
// telefónmi (/f/ops, OpExchange) a ukladá do zálohy osôb (.galeria-family, PeopleBackupFormat).
// payload = OpCodec.encode(op). deviceId sa neprenáša: doplní ho príjemca podľa overeného
// odosielateľa (telefón) alebo podľa identity zálohy. Čistá logika bez Androidu (okrem org.json).
object OpJson {
    private const val ID = "id"
    private const val LAMPORT = "lamport"
    private const val TYPE = "type"
    private const val PAYLOAD = "payload"

    fun toJson(ops: List<OpEntity>): JSONArray = JSONArray().apply {
        for (op in ops) {
            put(
                JSONObject()
                    .put(ID, op.opId)
                    .put(LAMPORT, op.lamport)
                    .put(TYPE, op.type)
                    .put(PAYLOAD, op.payloadJson),
            )
        }
    }

    fun fromJson(array: JSONArray?, deviceId: String): List<OpEntity> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { opFromJson(it, deviceId) } }
    }

    private fun opFromJson(o: JSONObject, deviceId: String): OpEntity? {
        val id = o.optString(ID)
        val lamport = o.optLong(LAMPORT, -1)
        return if (id.isNotEmpty() && lamport > 0) {
            OpEntity(id, deviceId, lamport, o.optString(TYPE), o.optString(PAYLOAD))
        } else {
            null
        }
    }
}
