package org.fossify.gallery.family

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairUriTest {
    private val token = ByteArray(32) { it.toByte() }
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun valid() = PairUri(id, "Lukášov Xiaomi", token, "192.168.1.23", 8090)

    @Test
    fun roundTrip() {
        val text = valid().toUri()
        org.junit.Assert.assertTrue(text.startsWith("galeriaplus://family/pair?v=1&id="))
        val parsed = PairUri.parse(text)
        assertNotNull(parsed)
        assertEquals(id, parsed!!.deviceId)
        assertEquals("Lukášov Xiaomi", parsed.name)
        assertArrayEquals(token, parsed.token)
        assertEquals("192.168.1.23", parsed.host)
        assertEquals(8090, parsed.port)
        // medzery okolo (skener) nevadia
        assertNotNull(PairUri.parse("  $text\n"))
    }

    @Test
    fun foreignOrBrokenLinksAreRejected() {
        val good = valid().toUri()
        assertNull(PairUri.parse("https://example.com/pair?v=1"))
        assertNull(PairUri.parse("galeriaplus://family/other?v=1"))
        assertNull(PairUri.parse(good.replace("v=1", "v=2")))
        assertNull(PairUri.parse(good.replace("port=8090", "port=70000")))
        assertNull(PairUri.parse(good.replace("port=8090", "port=abc")))
        assertNull(PairUri.parse(good.replace("id=$id", "id=")))
        assertNull(PairUri.parse(good.replace("id=$id", "id=..%2F..")))
        assertNull(PairUri.parse(good.replace("ip=192.168.1.23", "ip=a%20b")))
        val shortToken = PairUri(id, "x", ByteArray(16), "10.0.0.1", 1).toUri()
        assertNull(PairUri.parse(shortToken))
        assertNull(PairUri.parse(good.replace(FamilyBytes.b64(token), "%%%")))
    }

    @Test
    fun nameIsCleaned() {
        assertEquals("?", PairUri.cleanName(" \n "))
        assertEquals("ab", PairUri.cleanName("a\u0000b"))
        assertEquals(PairUri.MAX_NAME, PairUri.cleanName("x".repeat(100)).length)
        val parsed = PairUri.parse(PairUri(id, "a\nb", token, "h", 1).toUri())
        assertEquals("ab", parsed!!.name)
    }
}
