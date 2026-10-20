package org.fossify.gallery.family

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

// Obsahový kľúč fotky: SHA-256( veľkosť (8 B, big-endian) ‖ prvých 64 kB ‖ posledných 64 kB ) → hex.
//
// Rýchly aj pre 10 000 fotiek (číta najviac 128 kB na súbor), plný hash by bol príliš ťažký. Kľúč
// nezávisí od cesty ani mtime, takže prežije presun fotky a nájde aj kópiu poslanú inou cestou.
// Pri súbore ≤ 128 kB sa hlava a chvost prekrývajú, takže kľúč pokryje každý bajt súboru.
// Čistá logika bez Androidu (testovaná v ContentKeyTest).
object ContentKey {
    const val CHUNK_SIZE = 64 * 1024

    private const val LONG_BYTES = 8
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F

    @Throws(IOException::class)
    fun compute(file: File): String = RandomAccessFile(file, "r").use { raf ->
        val size = raf.length()
        val headLen = minOf(size, CHUNK_SIZE.toLong()).toInt()
        val head = ByteArray(headLen)
        raf.seek(0)
        raf.readFully(head)
        val tail = ByteArray(headLen)
        raf.seek(size - headLen)
        raf.readFully(tail)
        compute(size, head, tail)
    }

    // size = celková veľkosť súboru, head/tail = prvých/posledných min(size, 64 kB) bajtov
    fun compute(size: Long, head: ByteArray, tail: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(longToBytes(size))
        md.update(head)
        md.update(tail)
        return toHex(md.digest())
    }

    private fun longToBytes(value: Long): ByteArray {
        val out = ByteArray(LONG_BYTES)
        for (i in 0 until LONG_BYTES) {
            out[i] = (value ushr (BITS_PER_BYTE * (LONG_BYTES - 1 - i))).toByte()
        }
        return out
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and BYTE_MASK
            sb.append(HEX_DIGITS[v ushr NIBBLE_BITS])
            sb.append(HEX_DIGITS[v and NIBBLE_MASK])
        }
        return sb.toString()
    }
}
