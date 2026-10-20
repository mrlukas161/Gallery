package org.fossify.gallery.family

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class ContentKeyTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("contentkey").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun write(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    private fun randomBytes(size: Int, seed: Int = 1) = Random(seed).nextBytes(size)

    @Test
    fun stableForSameContent() {
        val data = randomBytes(300_000)
        val a = write("a.jpg", data)
        val b = write("b.jpg", data.copyOf())
        val key = ContentKey.compute(a)
        assertEquals(key, ContentKey.compute(a))
        assertEquals(key, ContentKey.compute(b))
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun knownValueForEmptyFile() {
        // SHA-256 z ôsmich nulových bajtov (veľkosť 0, prázdna hlava aj chvost)
        val expected = "af5570f5a1810b7af78caf4bc70a660f0df51e42baf91d4de5b2328de0e83dfc"
        assertEquals(expected, ContentKey.compute(write("empty", ByteArray(0))))
    }

    @Test
    fun lastByteChangeChangesKey() {
        val data = randomBytes(500_000)
        val original = ContentKey.compute(write("a", data))
        data[data.size - 1] = (data[data.size - 1] + 1).toByte()
        assertNotEquals(original, ContentKey.compute(write("b", data)))
    }

    @Test
    fun firstByteChangeChangesKey() {
        val data = randomBytes(500_000)
        val original = ContentKey.compute(write("a", data))
        data[0] = (data[0] + 1).toByte()
        assertNotEquals(original, ContentKey.compute(write("b", data)))
    }

    @Test
    fun smallFileEveryByteCounts() {
        // < 128 kB: hlava a chvost sa prekrývajú, takže zmena v strede sa prejaví
        val size = 100_000
        val data = randomBytes(size)
        val original = ContentKey.compute(write("a", data))
        data[size / 2] = (data[size / 2] + 1).toByte()
        assertNotEquals(original, ContentKey.compute(write("b", data)))
    }

    @Test
    fun tinyFileWorks() {
        val a = ContentKey.compute(write("a", byteArrayOf(1, 2, 3)))
        val b = ContentKey.compute(write("b", byteArrayOf(1, 2, 4)))
        assertNotEquals(a, b)
        assertEquals(a, ContentKey.compute(write("c", byteArrayOf(1, 2, 3))))
    }

    @Test
    fun sizeIsPartOfKey() {
        // rovnaká hlava aj chvost, iná dĺžka stredu → iná veľkosť → iný kľúč
        val chunk = ContentKey.CHUNK_SIZE
        val head = randomBytes(chunk, seed = 2)
        val tail = randomBytes(chunk, seed = 3)
        val shorter = head + ByteArray(10) + tail
        val longer = head + ByteArray(20) + tail
        assertNotEquals(ContentKey.compute(write("s", shorter)), ContentKey.compute(write("l", longer)))
    }

    @Test
    fun middleOfLargeFileIsIgnored() {
        // zámerné obmedzenie: stred veľkého súboru sa nečíta (rýchlosť)
        val data = randomBytes(400_000)
        val original = ContentKey.compute(write("a", data))
        data[200_000] = (data[200_000] + 1).toByte()
        assertEquals(original, ContentKey.compute(write("b", data)))
    }

    @Test
    fun fileMatchesExplicitParts() {
        val data = randomBytes(200_000)
        val chunk = ContentKey.CHUNK_SIZE
        val expected = ContentKey.compute(
            data.size.toLong(),
            data.copyOfRange(0, chunk),
            data.copyOfRange(data.size - chunk, data.size),
        )
        assertEquals(expected, ContentKey.compute(write("a", data)))
    }

    @Test
    fun storeComputesLazily() {
        val dao = FakeContentKeyDao()
        var now = 1_000L
        val store = ContentKeyStore(dao) { now }
        val file = write("photo.jpg", randomBytes(150_000))
        val path = file.path

        val first = store.keyFor(path)
        assertEquals(ContentKey.compute(file), first)
        assertEquals(1, dao.upserts)

        now = 2_000L
        assertEquals(first, store.keyFor(path))
        assertEquals("bez zmeny size/mtime sa nepočíta znova", 1, dao.upserts)
        assertEquals(first, store.cachedKeyFor(path, file.length(), file.lastModified()))

        file.writeBytes(randomBytes(150_001, seed = 9))
        file.setLastModified(file.lastModified() + 10_000L)
        val second = store.keyFor(path)
        assertNotEquals(first, second)
        assertEquals(2, dao.upserts)
        assertEquals(2_000L, dao.get(path)?.computedAt)
        assertEquals(listOf(path), store.pathsFor(second!!))
    }

    @Test
    fun storeReturnsNullForMissingFile() {
        val dao = FakeContentKeyDao()
        assertNull(ContentKeyStore(dao).keyFor(File(dir, "missing.jpg").path))
        assertEquals(0, dao.upserts)
    }

    @Test
    fun movedFileKeepsKey() {
        val dao = FakeContentKeyDao()
        val store = ContentKeyStore(dao)
        val data = randomBytes(90_000)
        val a = write("a.jpg", data)
        val keyA = store.keyFor(a.path)
        val moved = File(dir, "sub").apply { mkdirs() }.resolve("a.jpg")
        assertTrue(a.renameTo(moved))
        assertEquals(keyA, store.keyFor(moved.path))
    }

    private class FakeContentKeyDao : ContentKeyDao {
        val rows = HashMap<String, ContentKeyEntity>()
        var upserts = 0

        override fun get(path: String): ContentKeyEntity? = rows[path]

        override fun upsert(entity: ContentKeyEntity) {
            upserts++
            rows[entity.path] = entity
        }

        override fun findByContentKey(contentKey: String): List<ContentKeyEntity> =
            rows.values.filter { it.contentKey == contentKey }

        override fun delete(path: String) {
            rows.remove(path)
        }
    }
}
