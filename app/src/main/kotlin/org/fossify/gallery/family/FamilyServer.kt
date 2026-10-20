package org.fossify.gallery.family

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// Server rodinného prepojenia (NanoHTTPD, port 8090 — iný ako Server pre PC 8089).
//
// Endpointy (všetky POST, telo požiadavky aj odpovede je zašifrovaná obálka FamilyWire):
//   /f/pair   párovanie cez jednorazový token z QR (PairingResponder)
//   /f/hello  začiatok sedenia so spárovaným telefónom (SecureChannel), aj oznámenie o zrušení prepojenia
//   /f/ops    výmena opov oboma smermi podľa lamport („since“/„have“, FamilySync)
//   /f/bundle balík k fotke (FamilyHandler.onBundle, doplní F4)
//   /f/ack    koniec synchronizácie (FamilyHandler.onSyncFinished)
// Čokoľvek bez platného rámca (neznámy telefón, zlý kľúč, opakovanie, iná metóda) dostane 404 bez detailov.
//
// Nič nebeží trvalo: server žije, len kým ho niekto drží cez acquire() (napr. otvorené párovanie,
// neskôr okno synchronizácie z F5), a posledné release() ho zastaví aj s NSD ohlásením.
class FamilyServer private constructor(
    private val store: FamilyStore,
    db: FamilyDatabase,
    port: Int,
) : NanoHTTPD(port) {
    private val selfId = store.selfId()
    private val sync = FamilySync(db)
    private val peerDao = db.peerDao()
    private val nonces = NonceCache()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val responder = PairingResponder(selfId, { store.selfName() }, pairingTokens)

    private class Session(val channel: ServerChannel) {
        @Volatile
        var lastUsed = System.currentTimeMillis()
    }

    override fun serve(session: IHTTPSession): Response {
        val reply = if (session.method == Method.POST) {
            readBody(session, if (session.uri == PATH_BUNDLE) MAX_BUNDLE_BYTES else MAX_BODY_BYTES)
                ?.let { handle(session.uri, it) }
        } else {
            null
        }
        return if (reply == null) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        } else {
            newFixedLengthResponse(Response.Status.OK, MIME_BINARY, ByteArrayInputStream(reply), reply.size.toLong())
        }
    }

    // chyba pri spracovaní (DB, poškodený JSON…) = 404, server ostáva bežať
    @Suppress("TooGenericExceptionCaught")
    private fun handle(path: String, body: ByteArray): ByteArray? = try {
        when (path) {
            PairingProtocol.PATH -> pair(body)
            SecureChannel.HELLO_PATH -> hello(body)
            PATH_OPS, PATH_BUNDLE, PATH_ACK -> inSession(path, body)
            else -> null
        }
    } catch (e: RuntimeException) {
        android.util.Log.w(TAG, "požiadavka $path zlyhala", e)
        null
    }

    private fun pair(body: ByteArray): ByteArray? {
        val accepted = responder.handle(body) ?: return null
        if (!store.savePairing(accepted.result)) return null
        onPaired?.invoke(accepted.result.peerName)
        return accepted.response
    }

    private fun hello(body: ByteArray): ByteArray? {
        val hello = SecureChannel.openHello(body, selfId, nonces) { store.pairKey(it) } ?: return null
        val request = FamilySync.parse(hello.payload) ?: return null
        val peerId = hello.peerId
        val peer = store.peer(peerId)
        val response = JSONObject().put("v", FamilySync.VERSION).put("lamport", sync.clock())
        if (peer == null || request.optBoolean("unpair")) {
            // prepojenie zrušené tu (čakalo na oznámenie) alebo ho práve zrušila druhá strana
            if (peer == null) store.finishUnpair(peerId) else store.forget(peerId)
            sessions.remove(peerId)
            return hello.response(FamilySync.bytes(response.put("unpaired", true)))
        }
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { now - it.value.lastUsed > SESSION_TTL_MS }
        sessions[peerId] = Session(hello.channel)
        peerDao.markPeerSeen(peerId, now, peer.lastLamportSeen)
        return hello.response(FamilySync.bytes(response.put("have", peer.lastLamportSeen)))
    }

    private fun inSession(path: String, body: ByteArray): ByteArray? {
        val header = FamilyWire.readHeader(body) ?: return null
        val session = sessions[header.deviceId] ?: return null
        if (System.currentTimeMillis() - session.lastUsed > SESSION_TTL_MS) {
            sessions.remove(header.deviceId)
            return null
        }
        val request = session.channel.open(path, body) ?: return null
        session.lastUsed = System.currentTimeMillis()
        val peer = store.peer(header.deviceId) ?: return null
        val out = when (path) {
            PATH_OPS -> ops(peer, request.payload)
            PATH_BUNDLE -> bundle(peer, request.payload)
            else -> ack(peer)
        } ?: return null
        if (path == PATH_ACK) sessions.remove(header.deviceId)
        return session.channel.seal(path, request, out)
    }

    private fun ops(peer: PeerEntity, payload: ByteArray): ByteArray? {
        val request = FamilySync.parse(payload) ?: return null
        sync.storeFromPeer(peer, FamilySync.opsFromJson(request.optJSONArray("push"), peer.deviceId))
        val have = store.peer(peer.deviceId)?.lastLamportSeen ?: peer.lastLamportSeen
        val limit = request.optInt("limit", FamilySync.BATCH_LIMIT).coerceIn(1, FamilySync.BATCH_LIMIT)
        val batch = sync.ownOpsSince(selfId, request.optLong("since", 0), limit)
        return FamilySync.bytes(
            JSONObject().put("ops", FamilySync.opsToJson(batch.ops)).put("more", batch.more).put("have", have),
        )
    }

    private fun bundle(peer: PeerEntity, payload: ByteArray): ByteArray? {
        val (meta, data) = FamilyWire.unpackBlob(payload) ?: return null
        val ok = FamilyHandlers.bundle(peer, meta["kind"].orEmpty(), meta["ref"].orEmpty(), data)
        return FamilySync.bytes(JSONObject().put("ok", ok))
    }

    private fun ack(peer: PeerEntity): ByteArray {
        peerDao.markPeerSeen(peer.deviceId, System.currentTimeMillis(), peer.lastLamportSeen)
        FamilyHandlers.syncFinished(peer)
        return FamilySync.bytes(JSONObject().put("ok", true))
    }

    private fun readBody(session: IHTTPSession, max: Long): ByteArray? {
        val length = session.headers["content-length"]?.toLongOrNull() ?: return null
        if (length <= 0 || length > max) return null
        val buffer = ByteArray(length.toInt())
        return try {
            var read = 0
            while (read < buffer.size) {
                val n = session.inputStream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.takeIf { read == buffer.size }
        } catch (ignored: IOException) {
            null
        }
    }

    companion object {
        const val DEFAULT_PORT = 8090
        const val PATH_OPS = "/f/ops"
        const val PATH_BUNDLE = "/f/bundle"
        const val PATH_ACK = "/f/ack"
        const val OWNER_PAIRING = "pairing"

        private const val TAG = "FamilyServer"
        private const val MIME_BINARY = "application/octet-stream"
        private const val MAX_BODY_BYTES = 8L * 1024 * 1024
        private const val MAX_BUNDLE_BYTES = 96L * 1024 * 1024
        private const val SESSION_TTL_MS = 10 * 60 * 1000L

        // jednorazový token práve zobrazeného QR kódu (PairingActivity)
        val pairingTokens = PairingTokens()

        // volá sa z vlákna servera po úspešnom spárovaní (názov druhého telefónu)
        @Volatile
        var onPaired: ((String) -> Unit)? = null

        private val owners = HashSet<String>()
        private var running: FamilyServer? = null
        private var discovery: FamilyDiscovery? = null

        // Spustí server (ak ešte nebeží) a zaregistruje vlastníka. Vráti port alebo null (napr. bez siete).
        // Blokuje (bind, DB), volať na pozadí.
        @Synchronized
        fun acquire(context: Context, owner: String): Int? {
            running?.let {
                owners.add(owner)
                return it.listeningPort
            }
            val appContext = context.applicationContext
            val store = FamilyStore(appContext)
            val db = FamilyDatabase.getInstance(appContext)
            val server = startOn(store, db, DEFAULT_PORT) ?: startOn(store, db, 0) ?: return null
            running = server
            owners.add(owner)
            discovery = FamilyDiscovery(appContext).also {
                it.register(server.listeningPort, FamilyCrypto.publicTag(server.selfId))
            }
            return server.listeningPort
        }

        @Synchronized
        fun release(owner: String) {
            owners.remove(owner)
            if (owners.isNotEmpty()) return
            discovery?.unregister()
            discovery = null
            running?.stop()
            running = null
        }

        @Synchronized
        fun port(): Int? = running?.listeningPort

        private fun startOn(store: FamilyStore, db: FamilyDatabase, port: Int): FamilyServer? = try {
            FamilyServer(store, db, port).also { it.start(SOCKET_READ_TIMEOUT, true) }
        } catch (ignored: IOException) {
            null
        }
    }
}
