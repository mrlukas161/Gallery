package org.fossify.gallery.family

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FamilyCryptoTest {
    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // RFC 5869, testovací prípad 1
    @Test
    fun hkdfRfc5869Case1() {
        val okm = FamilyCrypto.hkdf(
            hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"),
            42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            FamilyBytes.toHex(okm),
        )
    }

    // RFC 5869, testovací prípad 3 (prázdna soľ aj info)
    @Test
    fun hkdfRfc5869Case3() {
        val okm = FamilyCrypto.hkdf(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), ByteArray(0), ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            FamilyBytes.toHex(okm),
        )
    }

    @Test
    fun sealOpenRoundTrip() {
        val key = FamilyCrypto.randomBytes(32)
        val aad = "dev|1".toByteArray()
        val plain = "Ahoj, rodina".toByteArray()
        val frame = FamilyCrypto.seal(key, aad, plain)
        assertEquals(12 + plain.size + 16, frame.size)
        assertArrayEquals(plain, FamilyCrypto.open(key, aad, frame))
    }

    @Test
    fun everyChangedByteIsRejected() {
        val key = FamilyCrypto.randomBytes(32)
        val aad = "dev|7".toByteArray()
        val frame = FamilyCrypto.seal(key, aad, ByteArray(40) { it.toByte() })
        for (i in frame.indices) {
            val bad = frame.copyOf()
            bad[i] = (bad[i].toInt() xor 0x01).toByte()
            assertNull("bajt $i", FamilyCrypto.open(key, aad, bad))
        }
    }

    @Test
    fun wrongKeyAadOrShortFrameIsRejected() {
        val key = FamilyCrypto.randomBytes(32)
        val frame = FamilyCrypto.seal(key, "a".toByteArray(), "x".toByteArray())
        assertNull(FamilyCrypto.open(FamilyCrypto.randomBytes(32), "a".toByteArray(), frame))
        assertNull(FamilyCrypto.open(key, "b".toByteArray(), frame))
        assertNull(FamilyCrypto.open(key, "a".toByteArray(), frame.copyOf(27)))
    }

    @Test
    fun ecdhAgreesOnBothSides() {
        val a = FamilyEcdh.generateKeyPair()
        val b = FamilyEcdh.generateKeyPair()
        val bPub = FamilyEcdh.decodePublicKey(FamilyEcdh.encodePublicKey(b.public))
        val aPub = FamilyEcdh.decodePublicKey(FamilyEcdh.encodePublicKey(a.public))
        assertNotNull(bPub)
        assertNotNull(aPub)
        val s1 = FamilyEcdh.agree(a.private, bPub!!)
        val s2 = FamilyEcdh.agree(b.private, aPub!!)
        assertNotNull(s1)
        assertArrayEquals(s1, s2)
    }

    @Test
    fun invalidPublicKeyIsRejected() {
        assertNull(FamilyEcdh.decodePublicKey(ByteArray(10)))
        assertNull(FamilyEcdh.decodePublicKey(ByteArray(0)))
    }

    @Test
    fun base64AndTag() {
        val bytes = FamilyCrypto.randomBytes(32)
        assertArrayEquals(bytes, FamilyBytes.unb64(FamilyBytes.b64(bytes)))
        assertNull(FamilyBytes.unb64("***"))
        assertEquals(16, FamilyCrypto.publicTag("abc").length)
        assertEquals(FamilyCrypto.publicTag("abc"), FamilyCrypto.publicTag("abc"))
        assertEquals("00ff10", FamilyBytes.toHex(byteArrayOf(0, -1, 16)))
    }
}
