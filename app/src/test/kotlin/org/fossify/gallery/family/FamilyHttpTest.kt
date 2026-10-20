package org.fossify.gallery.family

import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

// Celé sedenie cez skutočný socket: FamilyHttp (klient) ↔ NanoHTTPD so SecureChannel (ako FamilyServer).
class FamilyHttpTest {
    private val clientId = "cccccccc-0000-0000-0000-000000000001"
    private val serverId = "ssssssss-0000-0000-0000-000000000002"
    private val pairKey = FamilyCrypto.randomBytes(32)
    private lateinit var server: TestServer
    private lateinit var endpoint: FamilyEndpoint

    private inner class TestServer : NanoHTTPD("127.0.0.1", 0) {
        private val nonces = NonceCache()

        @Volatile
        var channel: ServerChannel? = null

        override fun serve(session: IHTTPSession): Response {
            val length = session.headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) read += session.inputStream.read(body, read, length - read)
            val reply = if (session.method == Method.POST) handle(session.uri, body) else null
            return if (reply == null) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            } else {
                val stream = ByteArrayInputStream(reply)
                newFixedLengthResponse(Response.Status.OK, "application/octet-stream", stream, reply.size.toLong())
            }
        }

        private fun handle(path: String, body: ByteArray): ByteArray? {
            if (path == SecureChannel.HELLO_PATH) {
                val hello = SecureChannel.openHello(body, serverId, nonces) { if (it == clientId) pairKey else null }
                    ?: return null
                channel = hello.channel
                return hello.response(hello.payload.reversedArray())
            }
            val ch = channel ?: return null
            val request = ch.open(path, body) ?: return null
            return ch.seal(path, request, request.payload.reversedArray())
        }
    }

    @Before
    fun start() {
        server = TestServer()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        endpoint = FamilyEndpoint("127.0.0.1", server.listeningPort)
    }

    @After
    fun stop() = server.stop()

    private fun post(path: String, body: ByteArray) = FamilyHttp.post(endpoint, path, body, 2000, 5000)

    @Test
    fun fullSessionOverSocket() {
        val handshake = ClientHandshake(pairKey, clientId, serverId)
        val reply = post(SecureChannel.HELLO_PATH, handshake.request(byteArrayOf(1, 2, 3)))
        assertEquals(200, reply.status)
        val (channel, payload) = handshake.finish(reply.body)!!
        assertArrayEquals(byteArrayOf(3, 2, 1), payload)
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val request = channel.seal("/f/ops", big)
        val response = post("/f/ops", request.bytes)
        assertEquals(200, response.status)
        assertArrayEquals(big.reversedArray(), channel.open("/f/ops", request, response.body))
        // zopakovaný rámec → 404 bez detailov
        val replay = post("/f/ops", request.bytes)
        assertEquals(404, replay.status)
        assertEquals(0, replay.body.size)
    }

    @Test
    fun garbageAndUnknownDeviceGet404() {
        assertEquals(404, post(SecureChannel.HELLO_PATH, "hello".toByteArray()).status)
        val stranger = ClientHandshake(pairKey, "unknown-device", serverId)
        assertEquals(404, post(SecureChannel.HELLO_PATH, stranger.request(ByteArray(0))).status)
        assertNull(server.channel)
    }

    @Test(expected = IOException::class)
    fun closedPortIsUnreachable() {
        server.stop()
        post(SecureChannel.HELLO_PATH, ByteArray(1))
    }

    @Test
    fun tryPostReturnsNullWhenUnreachable() {
        server.stop()
        assertNull(FamilyHttp.tryPost(endpoint, "/f/hello", ByteArray(1), 500, 500))
    }

    @Test
    fun replyParsing() {
        val withLength = "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nX: y\r\n\r\nabcdef".toByteArray()
        val r1 = FamilyHttp.readReply(ByteArrayInputStream(withLength))
        assertEquals(200, r1.status)
        assertArrayEquals("abc".toByteArray(), r1.body)
        val noLength = "HTTP/1.1 404 Not Found\r\n\r\nxyz".toByteArray()
        val r2 = FamilyHttp.readReply(ByteArrayInputStream(noLength))
        assertEquals(404, r2.status)
        assertNotNull(r2.body)
        assertArrayEquals("xyz".toByteArray(), r2.body)
    }

    @Test(expected = IOException::class)
    fun truncatedBodyIsError() {
        FamilyHttp.readReply(ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc".toByteArray()))
    }

    @Test(expected = IOException::class)
    fun badStatusIsError() {
        FamilyHttp.readReply(ByteArrayInputStream("garbage\r\n\r\n".toByteArray()))
    }
}
