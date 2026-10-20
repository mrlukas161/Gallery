package org.fossify.gallery.family

import java.util.Base64

// Prevody bajtov (Base64url bez paddingu, hex). Čistá logika bez Android tried.
object FamilyBytes {
    private const val HEX = "0123456789abcdef"
    private const val BYTE_MASK = 0xff
    private const val NIBBLE_MASK = 0x0f
    private const val NIBBLE_BITS = 4

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    // null pri neplatnom Base64
    fun unb64(text: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(text)
    } catch (ignored: IllegalArgumentException) {
        null
    }

    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and BYTE_MASK
            out.append(HEX[v ushr NIBBLE_BITS]).append(HEX[v and NIBBLE_MASK])
        }
        return out.toString()
    }
}

internal fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
