package org.fossify.gallery.family

// Zabezpečené sedenie medzi dvoma spárovanými telefónmi (čistá logika).
//
// 1. hello (/f/hello, poradie 0) je zašifrované trvalým kľúčom páru. Klient doň vloží náhodný nonce,
//    server odpovie vlastným nonce a odpoveď viaže na nonce klienta (AAD), takže starú odpoveď nejde
//    podstrčiť. Zopakovaný hello server spozná podľa NonceCache.
// 2. Kľúč sedenia = HKDF-SHA256(kľúč páru, soľ = nonce klienta ‖ nonce servera,
//    info = „galeriaf session v1|klient|server“). Každé sedenie má teda nový kľúč a rámce zo starého
//    sedenia (aj po reštarte appky) sa v novom neoveria.
// 3. Ďalšie požiadavky majú poradové čísla 1, 2, 3…; server prijme len rastúce (ReplayGuard).
//    Odpoveď nesie to isté číslo, ale deviceId servera a označenie „res:“.
object SecureChannel {
    const val HELLO_PATH = "/f/hello"
    const val NONCE_BYTES = 16

    fun sessionKey(
        pairKey: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        clientId: String,
        serverId: String,
    ): ByteArray = FamilyCrypto.hkdf(
        pairKey, clientNonce + serverNonce, "galeriaf session v1|$clientId|$serverId".utf8(), FamilyCrypto.KEY_BYTES,
    )

    // Server: overí hello. keyFor vráti kľúč páru pre deviceId (null = neznámy telefón).
    // null = neplatný alebo zopakovaný hello → server odpovie 404.
    fun openHello(
        envelope: ByteArray,
        selfId: String,
        nonces: NonceCache,
        keyFor: (String) -> ByteArray?,
    ): HelloRequest? {
        val header = FamilyWire.readHeader(envelope) ?: return null
        if (header.seq != 0L || header.deviceId == selfId) return null
        val pairKey = keyFor(header.deviceId) ?: return null
        val plain = FamilyWire.open(pairKey, header, envelope, FamilyWire.requestLabel(HELLO_PATH)) ?: return null
        if (plain.size < NONCE_BYTES) return null
        val clientNonce = plain.copyOfRange(0, NONCE_BYTES)
        if (!nonces.firstSeen(clientNonce)) return null
        return HelloRequest(header.deviceId, plain.copyOfRange(NONCE_BYTES, plain.size), pairKey, clientNonce, selfId)
    }
}

// Server: overený hello od peera. response() zostaví odpoveď, channel je nové sedenie.
class HelloRequest internal constructor(
    val peerId: String,
    val payload: ByteArray,
    private val pairKey: ByteArray,
    private val clientNonce: ByteArray,
    private val selfId: String,
) {
    private val serverNonce = FamilyCrypto.randomBytes(SecureChannel.NONCE_BYTES)

    val channel = ServerChannel(
        SecureChannel.sessionKey(pairKey, clientNonce, serverNonce, peerId, selfId), selfId, peerId,
    )

    fun response(payload: ByteArray): ByteArray = FamilyWire.seal(
        pairKey, selfId, 0, FamilyWire.responseLabel(SecureChannel.HELLO_PATH), serverNonce + payload, clientNonce,
    )
}

// Klient: začiatok sedenia s peerom.
class ClientHandshake(private val pairKey: ByteArray, private val selfId: String, private val peerId: String) {
    private val clientNonce = FamilyCrypto.randomBytes(SecureChannel.NONCE_BYTES)

    fun request(payload: ByteArray): ByteArray = FamilyWire.seal(
        pairKey, selfId, 0, FamilyWire.requestLabel(SecureChannel.HELLO_PATH), clientNonce + payload,
    )

    // null = odpoveď sa neoverila (podvrhnutá, stará, od iného telefónu)
    fun finish(response: ByteArray): Pair<ClientChannel, ByteArray>? {
        val header = FamilyWire.readHeader(response) ?: return null
        if (header.deviceId != peerId || header.seq != 0L) return null
        val plain = FamilyWire.open(
            pairKey, header, response, FamilyWire.responseLabel(SecureChannel.HELLO_PATH), clientNonce,
        ) ?: return null
        if (plain.size < SecureChannel.NONCE_BYTES) return null
        val serverNonce = plain.copyOfRange(0, SecureChannel.NONCE_BYTES)
        val key = SecureChannel.sessionKey(pairKey, clientNonce, serverNonce, selfId, peerId)
        return ClientChannel(key, selfId, peerId) to plain.copyOfRange(SecureChannel.NONCE_BYTES, plain.size)
    }
}

// Klientská strana sedenia: číslo požiadavky rastie, odpoveď musí niesť to isté číslo a deviceId servera.
class ClientChannel(private val key: ByteArray, private val selfId: String, private val peerId: String) {
    private var seq = 0L

    class Request(val seq: Long, val bytes: ByteArray)

    @Synchronized
    fun seal(path: String, plaintext: ByteArray): Request {
        seq++
        return Request(seq, FamilyWire.seal(key, selfId, seq, FamilyWire.requestLabel(path), plaintext))
    }

    fun open(path: String, request: Request, response: ByteArray): ByteArray? {
        val header = FamilyWire.readHeader(response) ?: return null
        if (header.deviceId != peerId || header.seq != request.seq) return null
        return FamilyWire.open(key, header, response, FamilyWire.responseLabel(path))
    }
}

// Serverová strana sedenia.
class ServerChannel(private val key: ByteArray, private val selfId: String, val peerId: String) {
    private val guard = ReplayGuard()

    class Request(val seq: Long, val payload: ByteArray)

    // null = cudzí, zmenený alebo zopakovaný rámec
    fun open(path: String, envelope: ByteArray): Request? {
        val header = FamilyWire.readHeader(envelope) ?: return null
        if (header.deviceId != peerId || header.seq <= 0L) return null
        val plain = FamilyWire.open(key, header, envelope, FamilyWire.requestLabel(path)) ?: return null
        if (!guard.accept(header.seq)) return null
        return Request(header.seq, plain)
    }

    fun seal(path: String, request: Request, plaintext: ByteArray): ByteArray =
        FamilyWire.seal(key, selfId, request.seq, FamilyWire.responseLabel(path), plaintext)
}
