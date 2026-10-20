package org.fossify.gallery.family

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Spoločná logika výmeny opov pre server aj klienta (obsah zašifrovaných rámcov je JSON).
//
//  /f/hello  req {v, lamport, have, unpair}        res {v, lamport, have, unpaired}
//  /f/ops    req {since, limit, push:[op…]}         res {ops:[op…], more, have}
//  /f/bundle req packBlob({kind, ref, id}, dáta)    res {ok}
//  /f/ack    req {have}                             res {ok}
// „have“ = najvyšší lamport opov druhej strany, ktoré už mám uložené (od neho pokračuje ďalšia výmena,
// takže prerušená synchronizácia sa obnoví od posledného potvrdeného bodu).
internal class OpExchange(private val db: FamilyDatabase) {

    class Batch(val ops: List<OpEntity>, val more: Boolean)

    // vlastné opy s lamport > since, najviac `limit` (opy s rovnakým lamport sa nedelia)
    fun ownOpsSince(selfId: String, since: Long, limit: Int): Batch {
        // F3 tu doplní snapshot Ľudí po spárovaní a čerstvé odtlačky osôb
        FamilyHandlers.beforeOwnOps()
        val all = db.opDao().getOpsSince(selfId, since)
        if (all.size <= limit) return Batch(all, false)
        var end = limit
        while (end < all.size && all[end].lamport == all[end - 1].lamport) end++
        return Batch(all.subList(0, end), end < all.size)
    }

    // Uloží opy od peera (len tie, ktoré naozaj patria jemu) a posunie jeho „have“. Vráti počet nových.
    fun storeFromPeer(peer: PeerEntity, ops: List<OpEntity>): Int {
        if (ops.isEmpty()) return 0
        var added = 0
        var maxLamport = peer.lastLamportSeen
        db.runInTransaction(
            Runnable {
                for (op in ops) {
                    if (op.deviceId != peer.deviceId) continue
                    if (db.opDao().insertOp(op.copy(appliedAt = null)) != -1L) added++
                    maxLamport = maxOf(maxLamport, op.lamport)
                }
                db.peerDao().markPeerSeen(peer.deviceId, System.currentTimeMillis(), maxLamport)
                // lamport hodiny: vlastná ďalšia hodnota bude vyššia ako všetko videné
                db.metaDao().nextLamport(maxLamport)
            },
        )
        if (added > 0) FamilyHandlers.opsReceived(peer.copy(lastLamportSeen = maxLamport), added)
        return added
    }

    // aktuálny stav vlastných lamport hodín (len informatívne v hello)
    fun clock(): Long = db.metaDao().get(MetaEntity.KEY_LAMPORT)?.toLongOrNull() ?: 0

    companion object {
        const val VERSION = 1
        const val BATCH_LIMIT = 200

        fun opsToJson(ops: List<OpEntity>): JSONArray = JSONArray().apply {
            for (op in ops) {
                put(
                    JSONObject()
                        .put("id", op.opId)
                        .put("lamport", op.lamport)
                        .put("type", op.type)
                        .put("payload", op.payloadJson),
                )
            }
        }

        // opy z JSON, deviceId doplní podľa overeného odosielateľa (rámec), nie podľa obsahu
        fun opsFromJson(array: JSONArray?, deviceId: String): List<OpEntity> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { opFromJson(it, deviceId) } }
        }

        private fun opFromJson(o: JSONObject, deviceId: String): OpEntity? {
            val id = o.optString("id")
            val lamport = o.optLong("lamport", -1)
            return if (id.isNotEmpty() && lamport > 0) {
                OpEntity(id, deviceId, lamport, o.optString("type"), o.optString("payload"))
            } else {
                null
            }
        }

        fun parse(bytes: ByteArray): JSONObject? = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (ignored: JSONException) {
            null
        }

        fun bytes(json: JSONObject): ByteArray = json.toString().toByteArray(Charsets.UTF_8)
    }
}
