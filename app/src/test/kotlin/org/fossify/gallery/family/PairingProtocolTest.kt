package org.fossify.gallery.family

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingProtocolTest {
    private var now = 5_000_000L
    private val idA = "aaaaaaaa-0000-0000-0000-000000000001"
    private val idB = "bbbbbbbb-0000-0000-0000-000000000002"
    private val tokens = PairingTokens(clock = { now })
    private val responder = PairingResponder(idA, { "Telefón A" }, tokens)

    private fun qr(token: ByteArray = tokens.currentOrIssue()) = PairUri(idA, "Telefón A", token, "10.0.0.2", 8090)

    @Test
    fun bothSidesDeriveSameKey() {
        val scanner = PairingScanner(qr(), idB, "Telefón B")
        val accepted = responder.handle(scanner.request())
        assertNotNull(accepted)
        assertEquals(idB, accepted!!.result.peerId)
        assertEquals("Telefón B", accepted.result.peerName)
        val result = scanner.finish(accepted.response)
        assertNotNull(result)
        assertEquals(idA, result!!.peerId)
        assertEquals("Telefón A", result.peerName)
        assertEquals(32, result.pairKey.size)
        assertArrayEquals(accepted.result.pairKey, result.pairKey)
        // trvalý kľúč nie je token z QR
        assertFalse(result.pairKey.contentEquals(qr().token))
    }

    @Test
    fun usedTokenCannotPairAgain() {
        val q = qr()
        assertNotNull(responder.handle(PairingScanner(q, idB, "B").request()))
        // ten istý QR (napr. z histórie skenera) už nefunguje
        assertNull(responder.handle(PairingScanner(q, "cccccccc-0000-0000-0000-000000000003", "C").request()))
    }

    @Test
    fun expiredTokenIsRejected() {
        val q = qr()
        now += PairingTokens.TTL_MS + 1
        assertNull(responder.handle(PairingScanner(q, idB, "B").request()))
    }

    @Test
    fun wrongTokenIsRejected() {
        tokens.issue()
        val forged = qr(FamilyCrypto.randomBytes(32))
        assertNull(responder.handle(PairingScanner(forged, idB, "B").request()))
    }

    @Test
    fun tamperedRequestOrResponseIsRejected() {
        val scanner = PairingScanner(qr(), idB, "B")
        val request = scanner.request()
        val bad = request.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(responder.handle(bad))
        val accepted = responder.handle(request)!!
        val badResponse = accepted.response.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        assertNull(scanner.finish(badResponse))
    }

    @Test
    fun responseFromOtherPairingIsRejected() {
        val scanner1 = PairingScanner(qr(), idB, "B")
        val scanner2 = PairingScanner(qr(), idB, "B")
        val accepted = responder.handle(scanner2.request())!!
        // odpoveď patrí k inému ECDH kľúču B → HMAC nesedí
        assertNull(scanner1.finish(accepted.response))
    }

    @Test
    fun ownIdIsRejected() {
        assertNull(responder.handle(PairingScanner(qr(), idA, "A").request()))
    }
}
