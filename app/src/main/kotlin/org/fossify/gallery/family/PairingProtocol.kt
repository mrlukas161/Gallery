package org.fossify.gallery.family

import java.security.PublicKey

// Párovanie dvoch telefónov (čistá logika). Telefón A zobrazí QR (PairUri s jednorazovým tokenom),
// telefón B ho naskenuje a pošle A požiadavku na /f/pair.
//
//  B → A: obálka (deviceId B, poradie 0) zašifrovaná kľúčom z tokenu; obsah: názov B, verejný ECDH kľúč B
//         a HMAC(token, „req|A|B|pubB“).
//  A → B: obálka (deviceId A, poradie 0) tým istým kľúčom; obsah: názov A, verejný ECDH kľúč A
//         a HMAC(token, „res|A|B|pubA|pubB“).
//  Obaja: kľúč páru = HKDF-SHA256(ECDH(A, B), soľ = token, info = „galeriaf pair key v1|A|B“).
//
// Kľúč páru teda nikdy nebol v QR. Kto pozná len QR (história skenera) a token je už použitý alebo
// vypršaný, nič nezíska; kto nepozná token, nevie vyrobiť platný HMAC ani rámec.
object PairingProtocol {
    const val PATH = "/f/pair"

    private const val FIELD_VERSION = "v"
    private const val FIELD_NAME = "name"
    private const val FIELD_PUB = "pub"
    private const val FIELD_MAC = "mac"

    // výsledok úspešného párovania na ktorejkoľvek strane
    class Result(val peerId: String, val peerName: String, val pairKey: ByteArray)

    // výsledok na strane A: čo uložiť + odpoveď pre B
    class Accepted(val result: Result, val response: ByteArray)

    // overená druhá strana: názov a verejný ECDH kľúč (text aj kľúč)
    class PeerKey(val name: String, val pub: String, val key: PublicKey)

    fun tokenKey(token: ByteArray): ByteArray =
        FamilyCrypto.hkdf(token, ByteArray(0), "galeriaf pair v1".utf8(), FamilyCrypto.KEY_BYTES)

    fun pairKey(shared: ByteArray, token: ByteArray, showerId: String, scannerId: String): ByteArray =
        FamilyCrypto.hkdf(shared, token, "galeriaf pair key v1|$showerId|$scannerId".utf8(), FamilyCrypto.KEY_BYTES)

    internal fun requestMac(token: ByteArray, showerId: String, scannerId: String, scannerPub: String): ByteArray =
        FamilyCrypto.hmacSha256(token, "req|$showerId|$scannerId|$scannerPub".utf8())

    internal fun responseMac(
        token: ByteArray,
        showerId: String,
        scannerId: String,
        showerPub: String,
        scannerPub: String,
    ): ByteArray = FamilyCrypto.hmacSha256(token, "res|$showerId|$scannerId|$showerPub|$scannerPub".utf8())

    internal fun message(name: String, pub: String, mac: ByteArray): ByteArray = KvCodec.encode(
        linkedMapOf(
            FIELD_VERSION to PairUri.VERSION,
            FIELD_NAME to PairUri.cleanName(name),
            FIELD_PUB to pub,
            FIELD_MAC to FamilyBytes.b64(mac),
        ),
    ).utf8()

    // rozbalená správa: (názov, verejný kľúč ako text, mac) alebo null
    internal fun parseMessage(plain: ByteArray): Triple<String, String, ByteArray>? {
        val fields = KvCodec.decode(String(plain, Charsets.UTF_8)) ?: return null
        if (fields[FIELD_VERSION] != PairUri.VERSION) return null
        val pub = fields[FIELD_PUB] ?: return null
        val mac = fields[FIELD_MAC]?.let { FamilyBytes.unb64(it) } ?: return null
        return Triple(PairUri.cleanName(fields[FIELD_NAME].orEmpty()), pub, mac)
    }
}

// Strana B (skenuje QR). Jedna inštancia = jeden pokus o párovanie.
class PairingScanner(private val qr: PairUri, private val selfId: String, selfName: String) {
    private val keyPair = FamilyEcdh.generateKeyPair()
    private val pub = FamilyBytes.b64(FamilyEcdh.encodePublicKey(keyPair.public))
    private val tokenKey = PairingProtocol.tokenKey(qr.token)
    private val name = PairUri.cleanName(selfName)

    fun request(): ByteArray {
        val mac = PairingProtocol.requestMac(qr.token, qr.deviceId, selfId, pub)
        return FamilyWire.seal(
            tokenKey, selfId, 0, FamilyWire.requestLabel(PairingProtocol.PATH), PairingProtocol.message(name, pub, mac),
        )
    }

    // null = odpoveď neprešla overením (iný telefón, podvrhnutá odpoveď, zmenený bajt)
    fun finish(response: ByteArray): PairingProtocol.Result? {
        val header = FamilyWire.readHeader(response)?.takeIf { it.deviceId == qr.deviceId && it.seq == 0L }
        val label = FamilyWire.responseLabel(PairingProtocol.PATH)
        val plain = header?.let { FamilyWire.open(tokenKey, it, response, label) }
        val (peerName, peerPub, mac) = plain?.let { PairingProtocol.parseMessage(it) } ?: return null
        val expected = PairingProtocol.responseMac(qr.token, qr.deviceId, selfId, peerPub, pub)
        val peerKey = FamilyBytes.unb64(peerPub)?.let { FamilyEcdh.decodePublicKey(it) }
        if (peerKey == null || !FamilyCrypto.constantTimeEquals(mac, expected)) return null
        val shared = FamilyEcdh.agree(keyPair.private, peerKey) ?: return null
        val pairKey = PairingProtocol.pairKey(shared, qr.token, qr.deviceId, selfId)
        return PairingProtocol.Result(qr.deviceId, peerName, pairKey)
    }
}

// Strana A (zobrazuje QR). Overí požiadavku proti aktuálnemu tokenu a token pri úspechu spotrebuje.
class PairingResponder(
    private val selfId: String,
    private val selfName: () -> String,
    private val tokens: PairingTokens,
) {
    // null = neplatná požiadavka (žiadny/vypršaný/použitý token, zlý rámec alebo HMAC) → server odpovie 404
    fun handle(envelope: ByteArray): PairingProtocol.Accepted? {
        val header = FamilyWire.readHeader(envelope) ?: return null
        if (header.seq != 0L || header.deviceId == selfId) return null
        return tokens.consume { token -> tryPair(token, header, envelope) }
    }

    private fun tryPair(token: ByteArray, header: FamilyWire.Header, envelope: ByteArray): PairingProtocol.Accepted? {
        val tokenKey = PairingProtocol.tokenKey(token)
        val scannerId = header.deviceId
        val peer = verify(token, tokenKey, header, envelope) ?: return null
        val keyPair = FamilyEcdh.generateKeyPair()
        val shared = FamilyEcdh.agree(keyPair.private, peer.key) ?: return null
        val pub = FamilyBytes.b64(FamilyEcdh.encodePublicKey(keyPair.public))
        val responseMac = PairingProtocol.responseMac(token, selfId, scannerId, pub, peer.pub)
        val response = FamilyWire.seal(
            tokenKey,
            selfId,
            0,
            FamilyWire.responseLabel(PairingProtocol.PATH),
            PairingProtocol.message(selfName(), pub, responseMac),
        )
        val pairKey = PairingProtocol.pairKey(shared, token, selfId, scannerId)
        return PairingProtocol.Accepted(PairingProtocol.Result(scannerId, peer.name, pairKey), response)
    }

    // rozbalí a overí požiadavku B (rámec kľúčom z tokenu + HMAC tokenu nad jeho verejným kľúčom)
    private fun verify(
        token: ByteArray,
        tokenKey: ByteArray,
        header: FamilyWire.Header,
        envelope: ByteArray,
    ): PairingProtocol.PeerKey? {
        val plain = FamilyWire.open(tokenKey, header, envelope, FamilyWire.requestLabel(PairingProtocol.PATH))
        val (peerName, peerPub, mac) = plain?.let { PairingProtocol.parseMessage(it) } ?: return null
        val expected = PairingProtocol.requestMac(token, selfId, header.deviceId, peerPub)
        val key = FamilyBytes.unb64(peerPub)?.let { FamilyEcdh.decodePublicKey(it) }
        if (key == null || !FamilyCrypto.constantTimeEquals(mac, expected)) return null
        return PairingProtocol.PeerKey(peerName, peerPub, key)
    }
}
