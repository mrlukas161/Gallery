package org.fossify.gallery.faces

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// E3 — dlaždice, prepočet do kanonického priestoru 1024 px a NMS
class FaceTilesTest {
    @Test
    fun twoByTwoTilesCoverImageWithOverlap() {
        val tiles = FaceTiles.tiles(2048, 1536)
        assertEquals(4, tiles.size)
        // krajné dlaždice sú pri okrajoch, každá má ~60 % strany (polovica + polovica prekryvu)
        assertEquals(0, tiles[0].left)
        assertEquals(0, tiles[0].top)
        assertEquals(2048, tiles[3].right)
        assertEquals(1536, tiles[3].bottom)
        assertEquals(1229, tiles[0].width)
        assertEquals(922, tiles[0].height)
        // prekryv: ľavá dlaždica končí za začiatkom pravej
        assertTrue(tiles[0].right > tiles[1].left)
        assertEquals(tiles[0].right - tiles[1].left, (2 * 1229 - 2048))
        // každý pixel je v niektorej dlaždici
        for (x in intArrayOf(0, 1023, 1024, 2047)) for (y in intArrayOf(0, 767, 768, 1535)) {
            assertTrue("$x,$y", tiles.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom })
        }
        assertTrue(FaceTiles.tiles(0, 10).isEmpty())
        assertEquals(listOf(FaceTiles.Tile(0, 0, 10, 20)), FaceTiles.tiles(10, 20, grid = 1))
    }

    @Test
    fun tileBoxMapsToCanonicalSpace() {
        // veľká bitmapa 2048×1536, kanonická 1024×768 → presná mierka 0,5
        val tile = FaceTiles.Tile(819, 614, 2048, 1536)
        val box = FaceTiles.toCanonical(floatArrayOf(10f, 20f, 50f, 70f), tile, 0.5f, 0.5f)
        assertArrayEquals(floatArrayOf(414.5f, 317f, 434.5f, 342f), box, 1e-4f)
        // nedeliteľné rozmery: kanonická 1000×750 z veľkej 2001×1501 (mierka po osiach zvlášť)
        val sx = 1000f / 2001
        val sy = 750f / 1501
        val b = FaceTiles.toCanonical(floatArrayOf(0f, 0f, 2001f, 1501f), FaceTiles.Tile(0, 0, 2001, 1501), sx, sy)
        assertArrayEquals(floatArrayOf(0f, 0f, 1000f, 750f), b, 1e-3f)
    }

    @Test
    fun nmsKeepsBaseAndDropsDuplicates() {
        val base = listOf(floatArrayOf(100f, 100f, 200f, 200f))
        val extra = listOf(
            floatArrayOf(102f, 98f, 203f, 201f) to 0.9f, // tá istá tvár ako z celej fotky
            floatArrayOf(500f, 500f, 520f, 520f) to 0.7f, // nová malá tvár
            floatArrayOf(501f, 499f, 521f, 521f) to 0.8f, // tá istá malá tvár z prekryvu dlaždíc (vyššie skóre)
            floatArrayOf(150f, 100f, 200f, 150f) to 0.95f, // polovica tváre z base na hrane dlaždice
            floatArrayOf(700f, 100f, 740f, 140f) to 0.6f, // ďalšia nová tvár
        )
        val kept = FaceTiles.mergeExtras(base, extra)
        assertEquals(listOf(2, 4), kept)
    }

    @Test
    fun iouAndContainment() {
        val a = floatArrayOf(0f, 0f, 10f, 10f)
        val b = floatArrayOf(5f, 0f, 15f, 10f)
        assertEquals(50f / 150f, FaceTiles.iou(a, b), 1e-6f)
        assertEquals(0.5f, FaceTiles.containment(a, b), 1e-6f)
        val inner = floatArrayOf(2f, 2f, 6f, 6f)
        assertEquals(1f, FaceTiles.containment(a, inner), 1e-6f)
        assertTrue(FaceTiles.isDuplicate(a, inner))
        assertFalse(FaceTiles.isDuplicate(a, floatArrayOf(20f, 20f, 30f, 30f)))
        assertEquals(0f, FaceTiles.iou(a, floatArrayOf(10f, 10f, 20f, 20f)), 0f)
    }
}
