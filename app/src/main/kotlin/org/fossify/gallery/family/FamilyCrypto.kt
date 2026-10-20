package org.fossify.gallery.family

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Kryptografické primitíva rodinného prepojenia. Čistá logika (len java.security / javax.crypto),
// bez Android tried, takže beží aj v JVM unit testoch.
//
// - HKDF-SHA256 podľa RFC 5869 (ručne cez HMAC-SHA256, javax.crypto ho nemá);
// - ECDH na krivke P-256 je vo FamilyEcdh, prevody bajtov vo FamilyBytes;
// - AES-256-GCM rámec [nonce12][ciphertext][tag16], nonce je vždy náhodný.
object FamilyCrypto {
    const val KEY_BYTES = 32
    const val TOKEN_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    private const val TAG_BITS = TAG_BYTES * 8
    private const val HASH_BYTES = 32
    private const val MAX_HKDF_BLOCKS = 255
    private const val PUBLIC_TAG_BYTES = 8
    private const val HMAC = "HmacSHA256"
    private const val AES_GCM = "AES/GCM/NoPadding"

    internal val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    // HKDF-SHA256 (extract + expand). Prázdna soľ = HashLen nulových bajtov (RFC 5869, 2.2).
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..MAX_HKDF_BLOCKS * HASH_BYTES) { "HKDF length" }
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, ikm)
        val out = ByteArrayOutputStream(length)
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmacSha256(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block, 0, minOf(block.size, length - out.size()))
            counter++
        }
        return out.toByteArray()
    }

    // Zašifruje a overí: výstup je [nonce12][ciphertext][tag16].
    fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    // Overí a dešifruje rámec začínajúci na `offset`. null = zmenený bajt, iný kľúč, iné AAD alebo krátky rámec.
    fun open(key: ByteArray, aad: ByteArray, frame: ByteArray, offset: Int = 0): ByteArray? {
        if (offset < 0 || frame.size - offset < NONCE_BYTES + TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            val spec = GCMParameterSpec(TAG_BITS, frame, offset, NONCE_BYTES)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            cipher.updateAAD(aad)
            cipher.doFinal(frame, offset + NONCE_BYTES, frame.size - offset - NONCE_BYTES)
        } catch (ignored: GeneralSecurityException) {
            null
        }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // Verejná značka zariadenia pre NSD (TXT id=…): odvodená z deviceId, samotné deviceId sa na sieti neohlasuje.
    fun publicTag(deviceId: String): String =
        FamilyBytes.toHex(sha256("galeriaf-tag|$deviceId".utf8()).copyOf(PUBLIC_TAG_BYTES))
}
