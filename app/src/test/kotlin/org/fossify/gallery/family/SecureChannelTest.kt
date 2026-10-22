package org.fossify.gallery.family

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SecureChannelTest {
    private val client = "cccccccc-0000-0000-0000-000000000001"
    private val server = "ssssssss-0000-0000-0000-000000000002"
    private val pairKey = FamilyCrypto.randomBytes(32)
    private val nonces = NonceCache()
    private val keys = mapOf(client to pairKey)

    private fun connect(): Pair<ClientChannel, ServerChannel> {
        val hs = ClientHandshake(pairKey, client, server)
        val hello = SecureChannel.openHello(hs.request("hi".toByteArray()), server, nonces) { keys[it] }
        assertNotNull(hello)
        assertEquals(client, hello!!.peerId)
        assertArrayEquals("hi".toByteArray(), hello.payload)
        val (channel, payload) = hs.finish(hello.response("hey".toByteArray()))!!
        assertArrayEquals("hey".toByteArray(), payload)
        return channel to hello.channel
    }

    @Test
    fun requestAndResponseRoundTrip() {
        val (c, s) = connect()
        for (i in 1..3) {
            val req = c.seal("/f/ops", "batch $i".toByteArray())
            assertEquals(i.toLong(), req.seq)
            val got = s.open("/f/ops", req.bytes)!!
            assertArrayEquals("batch $i".toByteArray(), got.payload)
            val res = s.seal("/f/ops", got, "ok $i".toByteArray())
            assertArrayEquals("ok $i".toByteArray(), c.open("/f/ops", req, res))
        }
    }

    @Test
    fun replayedOrOlderRequestIsRejected() {
        val (c, s) = connect()
        val first = c.seal("/f/ops", "a".toByteArray())
        val second = c.seal("/f/ops", "b".toByteArray())
        assertNotNull(s.open("/f/ops", second.bytes))
        assertNull(s.open("/f/ops", second.bytes))
        // staršie poradové číslo prišlo neskôr → zamietnuté
        assertNull(s.open("/f/ops", first.bytes))
    }

    @Test
    fun frameForOtherEndpointIsRejected() {
        val (c, s) = connect()
        val req = c.seal("/f/ops", "a".toByteArray())
        assertNull(s.open("/f/ack", req.bytes))
    }

    @Test
    fun responseMustMatchRequestSeq() {
        val (c, s) = connect()
        val r1 = c.seal("/f/ops", "a".toByteArray())
        val r2 = c.seal("/f/ops", "b".toByteArray())
        val got1 = s.open("/f/ops", r1.bytes)!!
        val res1 = s.seal("/f/ops", got1, "x".toByteArray())
        assertNull(c.open("/f/ops", r2, res1))
    }

    @Test
    fun replayedHelloIsRejected() {
        val hs = ClientHandshake(pairKey, client, server)
        val request = hs.request(ByteArray(0))
        assertNotNull(SecureChannel.openHello(request, server, nonces) { keys[it] })
        assertNull(SecureChannel.openHello(request, server, nonces) { keys[it] })
    }

    @Test
    fun unknownDeviceOrWrongKeyIsRejected() {
        val hs = ClientHandshake(FamilyCrypto.randomBytes(32), client, server)
        assertNull(SecureChannel.openHello(hs.request(ByteArray(0)), server, nonces) { keys[it] })
        val stranger = ClientHandshake(pairKey, "unknown-device", server)
        assertNull(SecureChannel.openHello(stranger.request(ByteArray(0)), server, nonces) { keys[it] })
    }

    @Test
    fun oldHelloResponseIsRejected() {
        val hs1 = ClientHandshake(pairKey, client, server)
        val hello1 = SecureChannel.openHello(hs1.request(ByteArray(0)), server, nonces) { keys[it] }!!
        val hs2 = ClientHandshake(pairKey, client, server)
        // odpoveď viazaná na nonce prvého hello sa k druhému nehodí
        assertNull(hs2.finish(hello1.response(ByteArray(0))))
    }

    @Test
    fun framesFromOldSessionAreRejectedInNewOne() {
        val (c1, _) = connect()
        val old = c1.seal("/f/ops", "a".toByteArray())
        val (_, s2) = connect()
        assertNull(s2.open("/f/ops", old.bytes))
    }
}
