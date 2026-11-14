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
import java.util.concurrent.atomic.AtomicInteger

// Server rodinného prepojenia (NanoHTTPD, port 8090 — iný ako Server pre PC 8089).
//
// Endpointy (všetky POST, telo požiadavky aj odpovede je zašifrovaná obálka FamilyWire):
//   /f/pair   párovanie cez jednorazový token z QR (PairingResponder)
//   /f/hello  začiatok sedenia so spárovaným telefónom (SecureChannel), aj oznámenie o zrušení prepojenia
//   /f/ops    výmena opov oboma smermi podľa lamport („since“/„have“, OpExchange)
//   /f/bundle balík k fotke (FamilyHandler.onBundle, doplní F4)
//   /f/ack    koniec synchronizácie (FamilyHandler.onSyncFinished)
// Čokoľvek bez platného rámca (neznámy telefón, zlý kľúč, opakovanie, iná metóda) dostane 404 bez detailov,
// rovnako každá požiadavka z adresy mimo lokálnej siete (LocalNet).
//
// Nič nebeží trvalo: server žije, len kým ho niekto drží cez acquire() (otvorené párovanie, okno synchronizácie,
// pokus pri otvorení appky). Po poslednom release() ešte krátko dobehne (LINGER_IDLE_MS bez požiadavky, najviac
// LINGER_MAX_MS): rozbehnutý prenos (video po kúskoch) sa nepretrhne a druhý telefón sa stihne pripojiť späť,
// aby odišla aj jeho fronta. Potom sa zastaví aj s NSD ohlásením.
@Suppress("TooManyFunctions")
class FamilyServer private constructor(
    private val appContext: Context,
    private val store: FamilyStore,
    db: FamilyDatabase,
    port: Int,
) : NanoHTTPD(port) {
    private val selfId = store.selfId()
    private val sync = OpExchange(db)
    private val peerDao = db.peerDao()
    private val nonces = NonceCache()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val responder = PairingResponder(selfId, { store.selfName() }, pairingTokens)
    private val inFlight = AtomicInteger()

    @Volatile
    private var lastActivity = System.currentTimeMillis()

    private class Session(val channel: ServerChannel) {
        @Volatile
        var lastUsed = System.currentTimeMillis()
    }

    // Dobeh servera predlžuje len platná požiadavka (overený rámec), nie hocijaká z lokálnej siete; rozbehnutá
    // požiadavka (inFlight) ho drží vždy.
    override fun serve(session: IHTTPSession): Response {
        inFlight.incrementAndGet()
        return try {
            val remote = session.remoteIpAddress.orEmpty()
            val reply = if (session.method == Method.POST && LocalNet.isLocal(remote, allowLoopback = true)) {
                readBody(session, if (session.uri == PATH_BUNDLE) MAX_BUNDLE_BYTES else MAX_BODY_BYTES)
                    ?.let { handle(session.uri, it, remote) }
            } else {
                null
            }
            if (reply == null) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            } else {
                lastActivity = System.currentTimeMillis()
                val body = ByteArrayInputStream(reply)
                newFixedLengthResponse(Response.Status.OK, MIME_BINARY, body, reply.size.toLong())
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    // beží požiadavka, alebo prišla nedávno (druhý telefón je uprostred sedenia)?
    private fun busy(now: Long): Boolean = inFlight.get() > 0 || now - lastActivity < LINGER_IDLE_MS

    // chyba pri spracovaní (DB, poškodený JSON…) = 404, server ostáva bežať
    @Suppress("TooGenericExceptionCaught")
    private fun handle(path: String, body: ByteArray, remote: String): ByteArray? = try {
        when (path) {
            PairingProtocol.PATH -> pair(body)
            SecureChannel.HELLO_PATH -> hello(body, remote)
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
        val code = FamilyCrypto.verificationCode(accepted.result.pairKey)
        onPaired?.invoke(accepted.result.peerId, accepted.result.peerName, code)
        return accepted.response
    }

    private fun hello(body: ByteArray, remote: String): ByteArray? {
        val hello = SecureChannel.openHello(body, selfId, nonces) { store.pairKey(it) } ?: return null
        val request = OpExchange.parse(hello.payload) ?: return null
        val peerId = hello.peerId
        val peer = store.peer(peerId)
        val response = JSONObject().put("v", OpExchange.VERSION).put("lamport", sync.clock())
        if (peer == null || request.optBoolean("unpair")) {
            // prepojenie zrušené tu (čakalo na oznámenie) alebo ho práve zrušila druhá strana
            if (peer == null) store.finishUnpair(peerId) else store.forget(peerId)
            sessions.remove(peerId)
            return hello.response(OpExchange.bytes(response.put("unpaired", true)))
        }
        // iná (nezlučiteľná) verzia protokolu: sedenie nevznikne, klient ukáže výzvu na aktualizáciu
        if (!OpExchange.compatible(request.optInt("v", 0))) {
            return hello.response(OpExchange.bytes(response.put("incompatible", true)))
        }
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { now - it.value.lastUsed > SESSION_TTL_MS }
        sessions[peerId] = Session(hello.channel)
        peerDao.markPeerSeen(peerId, now, peer.lastLamportSeen)
        rememberPeerServer(peerId, remote, request.optInt("port", 0))
        // D1: „have“ klienta = môj najvyšší lamport, ktorý už má; vlastné hodiny aspoň naň (obnova staršej DB)
        sync.raiseClock(request.optLong("have", 0))
        return hello.response(OpExchange.bytes(response.put("have", peer.lastLamportSeen)))
    }

    // adresa servera druhého telefónu (z tohto spojenia + port z hello): po jeho synchronizácii sa naň hneď
    // pripojíme s vlastnou frontou (FamilyAutoSync.afterServedSync). Len lokálna IPv4 adresa.
    private fun rememberPeerServer(peerId: String, remote: String, port: Int) {
        val host = remote.removePrefix("::ffff:")
        if (port in 1..MAX_PORT && LocalNet.ipv4(host) != null && LocalNet.isLocal(host)) {
            store.prefs.rememberAddress(peerId, FamilyEndpoint(host, port))
        }
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
        val request = OpExchange.parse(payload) ?: return null
        sync.storeFromPeer(peer, OpExchange.opsFromJson(request.optJSONArray("push"), peer.deviceId))
        val have = store.peer(peer.deviceId)?.lastLamportSeen ?: peer.lastLamportSeen
        val limit = request.optInt("limit", OpExchange.BATCH_LIMIT).coerceIn(1, OpExchange.BATCH_LIMIT)
        val batch = sync.ownOpsSince(selfId, request.optLong("since", 0), limit)
        return OpExchange.bytes(
            JSONObject().put("ops", OpExchange.opsToJson(batch.ops)).put("more", batch.more).put("have", have),
        )
    }

    private fun bundle(peer: PeerEntity, payload: ByteArray): ByteArray? {
        val (meta, data) = FamilyWire.unpackBlob(payload) ?: return null
        val ok = FamilyHandlers.bundle(peer, meta["kind"].orEmpty(), meta["ref"].orEmpty(), data)
        return OpExchange.bytes(JSONObject().put("ok", ok))
    }

    private fun ack(peer: PeerEntity): ByteArray {
        peerDao.markPeerSeen(peer.deviceId, System.currentTimeMillis(), peer.lastLamportSeen)
        store.markSynced(peer.deviceId)
        FamilyHandlers.syncFinished(peer)
        // druhý telefón (klient) odovzdal svoje; moja fronta pre neho odíde hneď, kým jeho server ešte beží
        FamilyAutoSync.afterServedSync(appContext, peer.deviceId)
        return OpExchange.bytes(JSONObject().put("ok", true))
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

        // dobeh po poslednom release(): bez požiadavky najviac toľko, celkovo najviac LINGER_MAX_MS
        const val LINGER_IDLE_MS = 30_000L
        private const val LINGER_MAX_MS = 15 * 60 * 1000L
        private const val LINGER_TICK_MS = 1_000L

        private const val TAG = "FamilyServer"
        private const val MIME_BINARY = "application/octet-stream"
        private const val MAX_BODY_BYTES = 8L * 1024 * 1024
        private const val MAX_PORT = 65535

        // Telo sa číta celé do pamäte ešte PRED overením rámca, preto žiadne veľké limity: súbory chodia
        // po kúskoch BundleTransfer.CHUNK_SIZE (4 MB, F4), takže /f/bundle stačí rovnaký limit ako ostatné.
        private const val MAX_BUNDLE_BYTES = MAX_BODY_BYTES
        private const val SESSION_TTL_MS = 10 * 60 * 1000L

        // jednorazový token práve zobrazeného QR kódu (PairingActivity)
        val pairingTokens = PairingTokens()

        // volá sa z vlákna servera po úspešnom spárovaní (deviceId a názov druhého telefónu, overovací kód)
        @Volatile
        var onPaired: ((String, String, String) -> Unit)? = null

        private val owners = HashSet<String>()
        private var running: FamilyServer? = null
        private var discovery: FamilyDiscovery? = null
        private var lingerGeneration = 0

        // Spustí server (ak ešte nebeží) a zaregistruje vlastníka. Vráti port alebo null (napr. bez siete).
        // Blokuje (bind, DB), volať na pozadí.
        @Synchronized
        fun acquire(context: Context, owner: String): Int? {
            lingerGeneration++ // prípadný dobeh po predchádzajúcom release() sa ruší
            running?.let {
                owners.add(owner)
                return it.listeningPort
            }
            val appContext = context.applicationContext
            val store = FamilyStore(appContext)
            val db = FamilyDatabase.getInstance(appContext)
            val server = startOn(appContext, store, db, DEFAULT_PORT)
                ?: startOn(appContext, store, db, 0)
                ?: return null
            running = server
            owners.add(owner)
            discovery = FamilyDiscovery(appContext).also {
                it.register(server.listeningPort, FamilyCrypto.publicTag(server.selfId))
            }
            return server.listeningPort
        }

        // Posledný release() server nezastaví hneď: dobehne, kým druhý telefón posiela požiadavky (pozri hore).
        @Synchronized
        fun release(owner: String) {
            owners.remove(owner)
            if (owners.isNotEmpty()) return
            val server = running ?: return
            server.lastActivity = maxOf(server.lastActivity, System.currentTimeMillis())
            val generation = ++lingerGeneration
            val started = System.currentTimeMillis()
            Thread({ linger(server, generation, started) }, "FamilyServerLinger").apply { isDaemon = true }.start()
        }

        private fun linger(server: FamilyServer, generation: Int, started: Long) {
            while (true) {
                try {
                    Thread.sleep(LINGER_TICK_MS)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                synchronized(this) {
                    if (generation != lingerGeneration || running !== server || owners.isNotEmpty()) return
                    val now = System.currentTimeMillis()
                    if (!server.busy(now) || now - started >= LINGER_MAX_MS || Thread.currentThread().isInterrupted) {
                        stopNow()
                        return
                    }
                }
            }
        }

        @Synchronized
        private fun stopNow() {
            discovery?.unregister()
            discovery = null
            running?.stop()
            running = null
        }

        @Synchronized
        fun port(): Int? = running?.listeningPort

        // Okno pri nabíjaní (FamilySyncJob) pred uvoľnením servera počká, kým druhý telefón dokončí svoje sedenie
        // (najviac do `until`; stop() = systém beh zastavil). Pred čakaním sa „aktivita“ posunie na teraz, aby sa
        // druhý telefón stihol po mojej synchronizácii pripojiť so svojou frontou.
        fun awaitQuiet(until: Long, stopped: () -> Boolean) {
            val server = synchronized(this) { running } ?: return
            server.lastActivity = maxOf(server.lastActivity, System.currentTimeMillis())
            while (!stopped()) {
                val now = System.currentTimeMillis()
                if (now >= until || !server.busy(now)) return
                try {
                    Thread.sleep(LINGER_TICK_MS)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }

        private fun startOn(context: Context, store: FamilyStore, db: FamilyDatabase, port: Int): FamilyServer? = try {
            FamilyServer(context, store, db, port).also { it.start(SOCKET_READ_TIMEOUT, true) }
        } catch (ignored: IOException) {
            null
        }
    }
}
