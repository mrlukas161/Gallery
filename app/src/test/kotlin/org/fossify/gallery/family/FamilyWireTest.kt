package org.fossify.gallery.family

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FamilyWireTest {
    private val key = FamilyCrypto.randomBytes(32)
    private val id = "11111111-2222-3333-4444-555555555555"

    @Test
    fun headerRoundTrip() {
        for (seq in listOf(0L, 1L, 255L, 256L, 1L shl 40, Long.MAX_VALUE)) {
            val h = FamilyWire.readHeader(FamilyWire.header(id, seq))
            assertNotNull(h)
            assertEquals(id, h!!.deviceId)
            assertEquals(seq, h.seq)
        }
    }

    @Test
    fun badHeadersAreRejected() {
        assertNull(FamilyWire.readHeader(ByteArray(0)))
        assertNull(FamilyWire.readHeader("XX".toByteArray()))
        val good = FamilyWire.header(id, 5)
        assertNull(FamilyWire.readHeader(good.copyOf(good.size - 1)))
        val wrongMagic = good.copyOf().also { it[0] = 'Q'.code.toByte() }
        assertNull(FamilyWire.readHeader(wrongMagic))
    }

    @Test
    fun sealOpenWithLabel() {
        val env = FamilyWire.seal(key, id, 3, FamilyWire.requestLabel("/f/ops"), "data".toByteArray())
        val h = FamilyWire.readHeader(env)!!
        assertArrayEquals("data".toByteArray(), FamilyWire.open(key, h, env, FamilyWire.requestLabel("/f/ops")))
        // iný endpoint, opačný smer alebo iná väzba → zamietnuté
        assertNull(FamilyWire.open(key, h, env, FamilyWire.requestLabel("/f/ack")))
        assertNull(FamilyWire.open(key, h, env, FamilyWire.responseLabel("/f/ops")))
        assertNull(FamilyWire.open(key, h, env, FamilyWire.requestLabel("/f/ops"), byteArrayOf(1)))
    }

    @Test
    fun changedSeqOrDeviceIdInHeaderIsRejected() {
        val env = FamilyWire.seal(key, id, 3, "req:/f/ops", "data".toByteArray())
        val head = FamilyWire.header(id, 3)
        val otherSeq = FamilyWire.header(id, 4) + env.copyOfRange(head.size, env.size)
        val h1 = FamilyWire.readHeader(otherSeq)!!
        assertNull(FamilyWire.open(key, h1, otherSeq, "req:/f/ops"))
        val otherId = FamilyWire.header("x$id".take(36), 3) + env.copyOfRange(head.size, env.size)
        val h2 = FamilyWire.readHeader(otherId)!!
        assertNull(FamilyWire.open(key, h2, otherId, "req:/f/ops"))
    }

    @Test
    fun blobRoundTrip() {
        val data = ByteArray(1000) { (it * 7).toByte() }
        val packed = FamilyWire.packBlob(mapOf("kind" to "photo", "ref" to "a&b=c ž"), data)
        val (meta, out) = FamilyWire.unpackBlob(packed)!!
        assertEquals("photo", meta["kind"])
        assertEquals("a&b=c ž", meta["ref"])
        assertArrayEquals(data, out)
        assertNull(FamilyWire.unpackBlob(byteArrayOf(0, 0, 1)))
        assertNull(FamilyWire.unpackBlob(byteArrayOf(0x7f, 0, 0, 0, 1)))
    }

    @Test
    fun kvCodec() {
        val map = linkedMapOf("name" to "Lukášov „Xiaomi“ & spol.", "empty" to "", "x" to "=")
        assertEquals(map, KvCodec.decode(KvCodec.encode(map)))
        assertNull(KvCodec.decode("a=%zz"))
        assertEquals(emptyMap<String, String>(), KvCodec.decode(""))
    }
}
