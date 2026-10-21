package org.fossify.gallery.family

import android.content.Context
import org.json.JSONObject
import java.io.IOException

// Klient rodinného prepojenia: párovanie a synchronizácia so spárovaným telefónom.
// Priebeh synchronizácie: hello → výmena opov oboma smermi (len rozdiel podľa lamport) → odoslanie
// fronty (outbox, balíky z F4) → ack. Po každej dávke sa „have“ (PeerEntity.lastLamportSeen) a stav
// položky fronty hneď uložia, takže prerušené spojenie sa nabudúce obnoví od posledného potvrdeného bodu.
//
// Všetky metódy blokujú (sieť, DB, NSD) → volať len na pozadí. Nič tu nebeží trvalo; kedy sa
// synchronizuje, určujú spúšťače (F5: onResume, okno pri nabíjaní, fronta).
class FamilyClient(context: Context) {
    private val appContext = context.applicationContext
    private val store = FamilyStore(appContext)
    private val db = FamilyDatabase.getInstance(appContext)
    private val sync = OpExchange(db)
    private val locator = FamilyLocator(appContext, store.prefs)

    enum class Status { OK, UNREACHABLE, REJECTED, UNPAIRED, ERROR }

    class PairOutcome(val status: Status, val peerName: String? = null)

    class SyncResult(
        val deviceId: String,
        val status: Status,
        val opsReceived: Int = 0,
        val opsSent: Int = 0,
        val bundlesSent: Int = 0,
    )

    // spojenie počas jednej synchronizácie
    private class Link(val endpoint: FamilyEndpoint, val channel: ClientChannel)

    private class Counters {
        var received = 0
        var sent = 0
        var bundles = 0
    }

    // Spáruje tento telefón s telefónom z naskenovaného QR kódu.
    fun pair(uri: PairUri): PairOutcome {
        val selfId = store.selfId()
        if (uri.deviceId == selfId) return PairOutcome(Status.REJECTED)
        val scanner = PairingScanner(uri, selfId, store.selfName())
        val endpoint = locator.manual() ?: FamilyEndpoint(uri.host, uri.port)
        val reply = post(endpoint, PairingProtocol.PATH, scanner.request()) ?: return PairOutcome(Status.UNREACHABLE)
        val result = reply.takeIf { it.status == FamilyHttp.STATUS_OK }?.let { scanner.finish(it.body) }
            ?: return PairOutcome(Status.REJECTED)
        val saved = store.savePairing(result)
        if (saved) store.prefs.rememberAddress(result.peerId, FamilyEndpoint(uri.host, uri.port))
        return if (saved) PairOutcome(Status.OK, result.peerName) else PairOutcome(Status.ERROR)
    }

    // Synchronizuje so všetkými spárovanými telefónmi (a oznámi zrušené prepojenia).
    fun syncAll(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<SyncResult> {
        notifyUnpairs(timeoutMs)
        return store.peers().map { sync(it, timeoutMs) }
    }

    // timeoutMs = najdlhšie hľadanie telefónu cez NSD (samotný prenos má vlastné časové limity)
    fun sync(peer: PeerEntity, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SyncResult {
        val key = store.pairKey(peer.deviceId) ?: return SyncResult(peer.deviceId, Status.ERROR)
        val handshake = ClientHandshake(key, store.selfId(), peer.deviceId)
        val hello = JSONObject()
            .put("v", OpExchange.VERSION)
            .put("lamport", sync.clock())
            .put("have", peer.lastLamportSeen)
        val opened = openSession(peer.deviceId, peer.publicTag, timeoutMs, handshake, hello)
        val (link, response) = when (opened) {
            is Opened.Ok -> opened.link to opened.response
            is Opened.Failed -> return SyncResult(peer.deviceId, opened.status)
        }
        if (response.optBoolean("unpaired")) {
            store.forget(peer.deviceId)
            return SyncResult(peer.deviceId, Status.UNPAIRED)
        }
        val counters = Counters()
        val serverHave = response.optLong("have", 0)
        // D1: po obnove staršej family.db by nové opy mali lamport ≤ have servera a nikdy by neodišli
        sync.raiseClock(serverHave)
        return try {
            val ok = exchangeOps(peer, link, serverHave, counters) &&
                sendOutbox(peer, link, counters) &&
                finish(peer, link)
            val status = if (ok) Status.OK else Status.REJECTED
            SyncResult(peer.deviceId, status, counters.received, counters.sent, counters.bundles)
        } catch (ignored: IOException) {
            // prerušené: čo sa stihlo, je uložené; nabudúce sa pokračuje od posledného potvrdeného bodu
            SyncResult(peer.deviceId, Status.UNREACHABLE, counters.received, counters.sent, counters.bundles)
        }
    }

    // Oznámi zrušené prepojenia telefónom, ktoré o tom ešte nevedia. Po potvrdení sa zmaže aj kľúč.
    fun notifyUnpairs(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        for (deviceId in store.prefs.pendingUnpairs()) {
            val opened = store.pairKey(deviceId)?.let { key ->
                val hello = JSONObject().put("v", OpExchange.VERSION).put("unpair", true)
                val handshake = ClientHandshake(key, store.selfId(), deviceId)
                openSession(deviceId, FamilyCrypto.publicTag(deviceId), timeoutMs, handshake, hello)
            }
            // bez kľúča, potvrdené, alebo druhá strana nás už nepozná (404) → hotovo
            val done = when (opened) {
                null, is Opened.Ok -> true
                is Opened.Failed -> opened.status == Status.REJECTED
            }
            if (done) store.finishUnpair(deviceId)
        }
    }

    private sealed class Opened {
        class Ok(val link: Link, val response: JSONObject) : Opened()
        class Failed(val status: Status) : Opened()
    }

    // hello na prvú adresu, ktorá odpovie: ručná (debug) → posledná známa → NSD
    private fun openSession(
        deviceId: String,
        publicTag: String,
        timeoutMs: Long,
        handshake: ClientHandshake,
        hello: JSONObject,
    ): Opened {
        val request = handshake.request(OpExchange.bytes(hello))
        var status = Status.UNREACHABLE
        for (endpoint in locator.candidates(deviceId, publicTag, timeoutMs)) {
            val reply = post(endpoint, SecureChannel.HELLO_PATH, request)
            val finished = reply?.takeIf { it.status == FamilyHttp.STATUS_OK }?.let { handshake.finish(it.body) }
            val response = finished?.let { OpExchange.parse(it.second) }
            if (finished != null && response != null) {
                store.prefs.rememberAddress(deviceId, endpoint)
                return Opened.Ok(Link(endpoint, finished.first), response)
            }
            if (reply != null) status = Status.REJECTED
        }
        return Opened.Failed(status)
    }

    // výmena opov po dávkach, kým niektorá strana hlási „more“
    @Throws(IOException::class)
    private fun exchangeOps(peer: PeerEntity, link: Link, serverHaveAtStart: Long, counters: Counters): Boolean {
        val selfId = store.selfId()
        var serverHave = serverHaveAtStart
        var rounds = 0
        var more = true
        while (more && rounds < MAX_ROUNDS) {
            rounds++
            val fresh = store.peer(peer.deviceId) ?: return false
            val push = sync.ownOpsSince(selfId, serverHave, OpExchange.BATCH_LIMIT)
            val request = JSONObject()
                .put("since", fresh.lastLamportSeen)
                .put("limit", OpExchange.BATCH_LIMIT)
                .put("push", OpExchange.opsToJson(push.ops))
            val response = call(link, FamilyServer.PATH_OPS, OpExchange.bytes(request)) ?: return false
            counters.sent += push.ops.size
            val received = OpExchange.opsFromJson(response.optJSONArray("ops"), peer.deviceId)
            counters.received += sync.storeFromPeer(fresh, received)
            val newHave = response.optLong("have", serverHave)
            // server neposunul „have“ napriek odoslaným opom → nezacykliť sa, skúsi sa nabudúce
            val stuck = push.ops.isNotEmpty() && newHave <= serverHave
            serverHave = newHave
            more = !stuck && (push.more || response.optBoolean("more"))
        }
        return true
    }

    // odošle čakajúce položky fronty; stav každej sa uloží hneď po odpovedi
    @Throws(IOException::class)
    private fun sendOutbox(peer: PeerEntity, link: Link, counters: Counters): Boolean {
        val dao = db.outboxDao()
        for (item in dao.getOutbox(peer.deviceId, OutboxEntity.STATE_PENDING)) {
            val attempts = item.attempts + 1
            val data = FamilyHandlers.buildBundle(peer, item)
            val meta = mapOf("kind" to item.kind, "ref" to item.refKey, "id" to item.id.toString())
            val reply = data?.let { call(link, FamilyServer.PATH_BUNDLE, FamilyWire.packBlob(meta, it)) }
            val ok = reply?.optBoolean("ok") == true
            val state = when {
                ok -> OutboxEntity.STATE_SENT
                data == null || attempts >= MAX_ATTEMPTS -> OutboxEntity.STATE_FAILED
                else -> OutboxEntity.STATE_PENDING
            }
            dao.updateOutbox(item.id, state, attempts)
            if (ok) counters.bundles++
        }
        return true
    }

    @Throws(IOException::class)
    private fun finish(peer: PeerEntity, link: Link): Boolean {
        val have = store.peer(peer.deviceId)?.lastLamportSeen ?: return false
        call(link, FamilyServer.PATH_ACK, OpExchange.bytes(JSONObject().put("have", have))) ?: return false
        db.peerDao().markPeerSeen(peer.deviceId, System.currentTimeMillis(), have)
        FamilyHandlers.syncFinished(peer.copy(lastLamportSeen = have))
        return true
    }

    private fun post(endpoint: FamilyEndpoint, path: String, body: ByteArray) =
        FamilyHttp.tryPost(endpoint, path, body, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)

    // jedna požiadavka v sedení; null = server ju odmietol alebo odpoveď neprešla overením
    @Throws(IOException::class)
    private fun call(link: Link, path: String, payload: ByteArray): JSONObject? {
        val request = link.channel.seal(path, payload)
        val reply = FamilyHttp.post(link.endpoint, path, request.bytes, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        if (reply.status != FamilyHttp.STATUS_OK) return null
        return link.channel.open(path, request, reply.body)?.let { OpExchange.parse(it) }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_ROUNDS = 500
        private const val MAX_ATTEMPTS = 5
    }
}
