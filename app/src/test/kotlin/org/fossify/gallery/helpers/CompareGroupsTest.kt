package org.fossify.gallery.helpers

import org.fossify.gallery.helpers.CompareGroups.Info
import org.fossify.gallery.helpers.CompareGroups.Sort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareGroupsTest {
    private val infos = mapOf(
        "/a" to Info("/a", 3_000_000, 4000, 3000, 100),
        "/b" to Info("/b", 400_000, 1600, 1200, 300),
        "/c" to Info("/c", 2_000_000, 4000, 3000, 200),
        "/d" to Info("/d", 1_000_000, 2000, 1500, 50),
    )

    private fun group(kind: CompareKind, vararg paths: String) =
        CompareGroups.build(kind, paths.toList()) { infos[it] }!!

    @Test
    fun keeperIsLargestResolutionAndReclaimableIsTheRest() {
        val g = group(CompareKind.EXACT, "/b", "/c", "/a")
        // /a aj /c majú 12 Mpx — rozhodne väčší súbor
        assertEquals("/a", g.keeper)
        assertEquals(listOf("/a", "/c", "/b"), g.paths)
        assertEquals(2_400_000L, g.reclaimableBytes)
        assertEquals(300L, g.newestMs)
    }

    @Test
    fun burstKeepsChronologicalOrder() {
        val g = group(CompareKind.BURST, "/d", "/a", "/c")
        assertEquals(listOf("/d", "/a", "/c"), g.paths)
        assertEquals("/a", g.keeper)
    }

    @Test
    fun missingFilesAreDroppedAndTooSmallGroupsVanish() {
        assertNull(CompareGroups.build(CompareKind.SIMILAR, listOf("/a", "/gone")) { infos[it] })
        val g = CompareGroups.build(CompareKind.SIMILAR, listOf("/a", "/gone", "/d")) { infos[it] }!!
        assertEquals(listOf("/a", "/d"), g.paths)
    }

    @Test
    fun sortOrders() {
        val g1 = group(CompareKind.SIMILAR, "/a", "/b") // uvoľní 0,4 MB, najnovšia 300
        val g2 = group(CompareKind.SIMILAR, "/c", "/d", "/b") // uvoľní 1,4 MB, 3 fotky, najnovšia 300
        val g3 = group(CompareKind.SIMILAR, "/a", "/d") // uvoľní 1 MB, najnovšia 100
        val all = listOf(g3, g1, g2)
        val newest = CompareGroups.sort(all, Sort.NEWEST).map { it.key }
        assertEquals(setOf(g1.key, g2.key), newest.take(2).toSet())
        assertEquals(g3.key, CompareGroups.sort(all, Sort.NEWEST).last().key)
        assertEquals(listOf(g2, g3, g1).map { it.key }, CompareGroups.sort(all, Sort.SPACE).map { it.key })
        assertEquals(g2.key, CompareGroups.sort(all, Sort.LARGEST).first().key)
    }

    @Test
    fun reclaimableTotalCountsEachPhotoOnce() {
        val burst = group(CompareKind.BURST, "/a", "/b", "/d") // ponechá /a
        val copies = group(CompareKind.EXACT, "/c", "/b") // ponechá /c
        val total = CompareGroups.reclaimableTotal(listOf(burst, copies)) { infos.getValue(it).sizeBytes }
        assertEquals(400_000L + 1_000_000L, total)
    }

    @Test
    fun preferredTabHasMostGroups() {
        val burst = group(CompareKind.BURST, "/a", "/b")
        val burst2 = group(CompareKind.BURST, "/c", "/d")
        val similar = group(CompareKind.SIMILAR, "/a", "/d")
        assertEquals(CompareGroups.TAB_BURSTS, CompareGroups.preferredTab(listOf(burst, burst2, similar)))
        assertEquals(CompareGroups.TAB_SIMILAR, CompareGroups.preferredTab(listOf(burst, similar)))
        assertEquals(CompareGroups.TAB_EXACT, CompareGroups.preferredTab(emptyList()))
    }

    @Test
    fun hiddenGroupsUseSubsetRule() {
        val hidden = HiddenGroups(emptyList()).plus(listOf(listOf("/a", "/b", "/c")))
        assertTrue(hidden.isHidden(listOf("/c", "/a", "/b")))
        // po zmazaní jednej fotky ostáva skrytá
        assertTrue(hidden.isHidden(listOf("/a", "/c")))
        // pribudla nová podobná fotka → ukáže sa znova
        assertFalse(hidden.isHidden(listOf("/a", "/b", "/d")))
        // vrátenie podmnožiny zruší uloženú množinu
        val back = hidden.minus(listOf(listOf("/a", "/b")))
        assertTrue(back.isEmpty)
        assertFalse(back.isHidden(listOf("/a", "/b", "/c")))
        // jednoprvkové a už skryté sa neukladajú
        assertEquals(1, hidden.plus(listOf(listOf("/x"), listOf("/a", "/b"))).sets.size)
    }

    @Test
    fun nextGroupSkipsVanishedGroups() {
        val g1 = group(CompareKind.SIMILAR, "/a", "/b")
        val g2 = group(CompareKind.SIMILAR, "/c", "/d")
        val g3 = group(CompareKind.SIMILAR, "/a", "/d")
        val queue = listOf(g2.key, g3.key)
        assertEquals(g2.key, CompareGroups.nextGroup(queue, listOf(g1, g2, g3))?.key)
        // g2 medzitým zmizla (fotky zmazané) → g3
        assertEquals(g3.key, CompareGroups.nextGroup(queue, listOf(g1, g3))?.key)
        assertNull(CompareGroups.nextGroup(queue, listOf(g1)))
    }
}
