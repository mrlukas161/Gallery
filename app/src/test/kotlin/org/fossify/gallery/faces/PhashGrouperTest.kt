package org.fossify.gallery.faces

import org.fossify.gallery.faces.PhashGrouper.Kind
import org.fossify.gallery.faces.PhashGrouper.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PhashGrouperTest {
    // „bežná“ fotka: polovica bitov nastavená (nie je to obrázok bez štruktúry)
    private val base = 0x0F0F_3C3C_5A5A_A5A5L

    private fun flip(hash: Long, vararg bits: Int): Long = bits.fold(hash) { h, b -> h xor (1L shl b) }

    private fun photo(path: String, hash: Long, w: Int = 4000, h: Int = 3000, size: Long = 1000L) =
        Photo("/DCIM/Camera/$path", hash, w, h, size)

    private fun groupsOf(photos: List<Photo>) =
        PhashGrouper.group(photos).map { g -> g.kind to g.paths.map { it.substringAfterLast('/') }.toSet() }

    // náhodné „bežné“ hashe ďaleko od seba aj od base
    private fun filler(count: Int, seed: Int): List<Photo> {
        val rnd = Random(seed)
        val out = ArrayList<Photo>()
        while (out.size < count) {
            val h = rnd.nextLong()
            if (PhashGrouper.distance(h, base) > 20 && !PhashGrouper.isLowInfo(h)) out.add(photo("f${out.size}", h))
        }
        return out
    }

    @Test
    fun candidateEdgesMatchBruteForce() {
        val rnd = Random(7)
        val hashes = ArrayList<Long>()
        repeat(150) {
            val h = rnd.nextLong()
            hashes.add(h)
            // zasadené blízke dvojice so vzdialenosťou 0–9 rozhádzanou po celom hashi
            val bits = (0 until 64).shuffled(rnd).take(rnd.nextInt(10))
            hashes.add(flip(h, *bits.toIntArray()))
        }
        val array = hashes.toLongArray()
        for (threshold in listOf(0, 2, 6, 10, 40)) {
            val fast = PhashGrouper.candidateEdges(array, threshold).toList()
            assertEquals("prah $threshold", bruteForceEdges(array, threshold), fast)
        }
    }

    // rovnaké zbalenie ako PhashGrouper: vzdialenosť << 56 | i << 28 | j
    private fun bruteForceEdges(array: LongArray, threshold: Int): List<Long> {
        val brute = ArrayList<Long>()
        for (i in array.indices) {
            for (j in i + 1 until array.size) {
                val d = PhashGrouper.distance(array[i], array[j])
                if (d <= threshold) brute.add((d.toLong() shl 56) or (i.toLong() shl 28) or j.toLong())
            }
        }
        return brute.sorted()
    }

    @Test
    fun differencesSpreadOverAllOldBlocksAreFoundInLargeLibrary() {
        // 4 a 6 rozdielov po jednom v každom 16-bitovom bloku — pôvodné vedierka (od 400 fotiek) ich nenašli
        val four = flip(base, 1, 17, 33, 49)
        val six = flip(base, 2, 18, 34, 50, 60, 12)
        val near = listOf(photo("a", base, size = 900), photo("b", four), photo("c", six, size = 800))
        val photos = filler(500, 1) + near
        val groups = PhashGrouper.group(photos)
        val withA = groups.single { g -> g.paths.any { it.endsWith("/a") } }
        assertTrue(withA.paths.any { it.endsWith("/b") })
    }

    @Test
    fun thresholdBoundary() {
        val six = photo("six", flip(base, 0, 9, 18, 27, 36, 45))
        val seven = photo("seven", flip(base, 0, 9, 18, 27, 36, 45, 54))
        assertEquals(listOf(Kind.SIMILAR to setOf("a", "six")), groupsOf(listOf(photo("a", base), six)))
        assertTrue(groupsOf(listOf(photo("a", base), seven)).isEmpty())
        // presná kópia: 2 bity áno, 3 bity už len podobné
        val two = listOf(photo("a", base), photo("b", flip(base, 3, 40)))
        val three = listOf(photo("a", base), photo("b", flip(base, 3, 40, 50)))
        assertEquals(Kind.EXACT, PhashGrouper.group(two).single().kind)
        assertEquals(Kind.SIMILAR, PhashGrouper.group(three).single().kind)
    }

    @Test
    fun chainingDoesNotJoinDistantPhotos() {
        // A–B 4 bity, B–C 4 bity, A–C 8 bitov: A a C nesmú byť v jednej skupine
        val a = photo("a", base)
        val b = photo("b", flip(base, 1, 2, 3, 4))
        val c = photo("c", flip(base, 1, 2, 3, 4, 5, 6, 7, 8))
        val groups = groupsOf(listOf(a, b, c))
        assertEquals(1, groups.size)
        assertFalse(groups.single().second.containsAll(setOf("a", "c")))
        // dlhá reťaz s krokom 3 bity nevytvorí jednu obriu skupinu
        val chain = (0 until 20).map { i -> photo("p$i", flip(base, *(0 until i * 3).toList().toIntArray())) }
        PhashGrouper.group(chain).forEach { g ->
            val hashes = g.paths.map { p -> chain.first { it.path == p }.hash }
            val diameter = hashes.maxOf { x -> hashes.maxOf { y -> PhashGrouper.distance(x, y) } }
            assertTrue(diameter <= PhashGrouper.SIMILAR_THRESHOLD)
        }
    }

    @Test
    fun resultDoesNotDependOnInputOrder() {
        val near = (0 until 12).map { i -> photo("n$i", flip(base, *(0 until i).toList().toIntArray())) }
        val photos = filler(60, 3) + near
        val expected = PhashGrouper.group(photos).map { it.kind to it.paths.toSet() }.toSet()
        repeat(5) { seed ->
            val shuffled = PhashGrouper.group(photos.shuffled(Random(seed))).map { it.kind to it.paths.toSet() }.toSet()
            assertEquals(expected, shuffled)
        }
    }

    @Test
    fun exactCopiesFirstThenSimilarFromTheRest() {
        val original = photo("orig", base, 4000, 3000, 5_000_000)
        val resized = photo("whatsapp", flip(base, 7), 1600, 1200, 300_000) // rovnaký pomer strán
        val neighbour = photo("next", flip(base, 20, 21, 22, 23), 4000, 3000)
        val other = photo("other", flip(base, 20, 21, 22, 23, 30, 31, 32), 4000, 3000)
        val groups = groupsOf(listOf(original, resized, neighbour, other))
        assertEquals(listOf(Kind.EXACT to setOf("orig", "whatsapp"), Kind.SIMILAR to setOf("next", "other")), groups)
    }

    @Test
    fun exactCopyNeedsSameResolutionOrAspect() {
        val a = photo("a", base, 4000, 3000)
        val cropped = photo("crop", flip(base, 5), 3600, 3000) // pomer 1,2 vs 1,33 — len podobné
        val unknown = Photo("/DCIM/Camera/unknown", flip(base, 6), 0, 0)
        assertEquals(listOf(Kind.SIMILAR to setOf("a", "crop")), groupsOf(listOf(a, cropped)))
        assertEquals(listOf(Kind.SIMILAR to setOf("a", "unknown")), groupsOf(listOf(a, unknown)))
        // výrazne iný pomer strán nie je ani podobná fotka
        assertTrue(groupsOf(listOf(a, photo("pano", flip(base, 5), 8000, 2000))).isEmpty())
    }

    @Test
    fun darkAndBlankPhotosAreOnlyExactCopies() {
        val dark = -1L // takmer jednofarebný obrázok: každý pixel ≥ priemer
        assertTrue(PhashGrouper.isLowInfo(dark))
        assertTrue(PhashGrouper.isLowInfo(flip(0L, 1, 2)))
        assertFalse(PhashGrouper.isLowInfo(base))
        // dve rôzne tmavé fotky (iná veľkosť súboru) sa nezlepia ani ako podobné
        assertTrue(groupsOf(listOf(photo("n1", dark, size = 100), photo("n2", flip(dark, 3), size = 200))).isEmpty())
        // skutočná kópia (rovnaký súbor) áno
        assertEquals(
            listOf(Kind.EXACT to setOf("n1", "copy")),
            groupsOf(listOf(photo("n1", dark, size = 100), photo("copy", dark, size = 100))),
        )
    }

    @Test
    fun screenshotsAreOnlyExactCopies() {
        val s1 = Photo("/Pictures/Screenshots/Screenshot_1.png", base, 1080, 2400, 500)
        val s2 = Photo("/Pictures/Screenshots/Screenshot_2.png", flip(base, 1, 2, 3), 1080, 2400, 600)
        val s3 = Photo("/Download/Screenshot_1.png", base, 1080, 2400, 500)
        assertTrue(PhashGrouper.isScreenshot(s1.path))
        assertTrue(PhashGrouper.group(listOf(s1, s2)).isEmpty())
        val groups = PhashGrouper.group(listOf(s1, s2, s3))
        assertEquals(1, groups.size)
        assertEquals(Kind.EXACT, groups.single().kind)
        assertEquals(setOf(s1.path, s3.path), groups.single().paths.toSet())
    }

    @Test
    fun invalidHashesAndDuplicatePathsAreIgnored() {
        assertTrue(PhashGrouper.group(listOf(photo("a", 0L), photo("b", 0L))).isEmpty())
        assertTrue(PhashGrouper.group(listOf(photo("a", base), photo("a", base))).isEmpty())
    }

    @Test
    fun legacyInterfaceKeepsWorking() {
        val groups = PhashGrouper.groupBySimilarity(
            listOf(PhashEntity("/x/a", base, 0L), PhashEntity("/x/b", flip(base, 1), 0L), PhashEntity("/x/c", 0L, 0L)),
        )
        assertEquals(listOf(listOf("/x/a", "/x/b")), groups)
    }

    @Test
    fun manyIdenticalPhotosStayFast() {
        val photos = (0 until 1500).map { photo("same$it", base) }
        val started = System.nanoTime()
        val groups = PhashGrouper.group(photos)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(1, groups.size)
        assertEquals(1500, groups.single().paths.size)
        assertTrue("trvalo $ms ms", ms < 5_000)
    }
}
