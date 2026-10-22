package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Delenie súboru na kúsky a refKey položiek fronty (F4).
class BundleTransferTest {
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val mb = 1024 * 1024

    @Test
    fun chunkCount() {
        val c = BundleTransfer.CHUNK_SIZE
        assertEquals(0, BundleTransfer.chunkCount(0))
        assertEquals(1, BundleTransfer.chunkCount(1))
        assertEquals(1, BundleTransfer.chunkCount(c.toLong()))
        assertEquals(2, BundleTransfer.chunkCount(c + 1L))
        // 2 GB video = 512 kúskov po 4 MB, žiadny sa nenačíta do pamäte naraz
        assertEquals(512, BundleTransfer.chunkCount(2L * 1024 * mb))
    }

    @Test
    fun chunkOffsetsAndLengths() {
        val size = 10L * mb + 123
        val count = BundleTransfer.chunkCount(size, 4 * mb)
        assertEquals(3, count)
        val refs = (0 until count).map { BundleTransfer.Ref(id, it, count, 4 * mb, size, 7L) }
        assertEquals(listOf(0L, 4L * mb, 8L * mb), refs.map { it.offset })
        assertEquals(listOf(4 * mb, 4 * mb, 2 * mb + 123), refs.map { it.length })
        assertEquals(size, refs.sumOf { it.length.toLong() })
    }

    @Test
    fun refRoundTrip() {
        val size = 9L * mb
        val chunk = BundleTransfer.Ref(id, 2, 3, 4 * mb, size, 1_700_000_000_123L)
        assertEquals(chunk, BundleTransfer.decode(BundleTransfer.encode(chunk)))
        val photo = chunk.copy(index = -1)
        assertEquals(photo, BundleTransfer.decode(BundleTransfer.encode(photo)))
        val results = BundleTransfer.Ref(id, -1, 0, 4 * mb, size, 0L)
        assertEquals(results, BundleTransfer.decode(BundleTransfer.encode(results)))
    }

    @Test
    fun refHasNoPath() {
        // refKey odchádza druhému telefónu: cesta k súboru zostáva v meta tabuľke odosielateľa
        val text = BundleTransfer.encode(BundleTransfer.Ref(id, 0, 1, 4 * mb, 10L, 0L))
        assertFalse(text.contains("/"))
        assertFalse(text.contains("storage"))
        assertEquals("f4.path.$id", BundleTransfer.pathKey(id))
    }

    @Test
    fun invalidRefsRejected() {
        fun enc(t: String = id, i: Int = 0, n: Int = 1, c: Int = 4 * mb, s: Long = 10L) =
            BundleTransfer.encode(BundleTransfer.Ref(t, i, n, c, s, 0L))
        assertNull(BundleTransfer.decode("nezmysel"))
        assertNull(BundleTransfer.decode(enc(t = "../../etc")))
        assertNull(BundleTransfer.decode(enc(t = id.uppercase())))
        assertNull(BundleTransfer.decode(enc(i = 1))) // kúsok za koncom súboru
        assertNull(BundleTransfer.decode(enc(i = -2)))
        assertNull(BundleTransfer.decode(enc(n = 2))) // počet nesedí s veľkosťou
        assertNull(BundleTransfer.decode(enc(c = 0)))
        assertNull(BundleTransfer.decode(enc(c = BundleTransfer.MAX_CHUNK_SIZE + 1)))
        assertNull(BundleTransfer.decode(enc(s = -1L)))
        assertNull(BundleTransfer.decode(enc(s = BundleTransfer.MAX_FILE_SIZE + 1, n = 1)))
        // balík bez súboru nemá kúsky
        assertNull(BundleTransfer.decode(enc(i = 0, n = 0, s = 0L)))
    }

    @Test
    fun ledger() {
        val received = BundleTransfer.parseLedger("0\n2\nzlý riadok\n2\n7\n-1\n1\n", 3)
        assertEquals(setOf(0, 1, 2), received)
        assertTrue(BundleTransfer.isComplete(received, 3))
        assertFalse(BundleTransfer.isComplete(setOf(0, 2), 3))
        assertTrue(BundleTransfer.isComplete(emptySet(), 0))
        assertEquals(emptySet<Int>(), BundleTransfer.parseLedger("", 3))
    }

    @Test
    fun displayNameSanitized() {
        assertEquals("IMG_1.jpg", BundleTransfer.displayName("../../IMG_1.jpg"))
        assertEquals("IMG_1.jpg", BundleTransfer.displayName("C:\\fotky\\IMG_1.jpg"))
        assertEquals("tajne.jpg", BundleTransfer.displayName(".tajne.jpg"))
        assertEquals("Galeria_rodina", BundleTransfer.displayName("   "))
        assertEquals("ab.jpg", BundleTransfer.displayName("a\u0000b.jpg"))
    }
}
