package org.fossify.gallery.faces

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceSelectTest {
    private fun box(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, b)

    @Test
    fun iouBasics() {
        assertEquals(1f, FaceSelect.iou(box(0f, 0f, 10f, 10f), box(0f, 0f, 10f, 10f)), 1e-6f)
        assertEquals(0f, FaceSelect.iou(box(0f, 0f, 10f, 10f), box(10f, 0f, 20f, 10f)), 1e-6f)
        assertEquals(25f / 175f, FaceSelect.iou(box(0f, 0f, 10f, 10f), box(5f, 5f, 15f, 15f)), 1e-6f)
    }

    @Test
    fun groupPhotoPicksFaceOverlappingDetectionNotNeighbour() {
        // výrez s okrajom: hľadaná tvár v strede, sused vľavo zasahuje do výrezu a je väčší
        val target = box(60f, 60f, 160f, 180f)
        val neighbour = box(-40f, 50f, 70f, 190f)
        val wanted = box(70f, 55f, 165f, 175f)
        val farAway = box(200f, 0f, 260f, 60f)
        assertEquals(1, FaceSelect.bestMatch(listOf(neighbour, wanted, farAway), target))
        assertEquals(0, FaceSelect.bestMatch(listOf(wanted, neighbour), target))
    }

    @Test
    fun onlyNeighbourFoundGivesNoFace() {
        // landmarker našiel len suseda na okraji výrezu, mimo rámčeka detekcie → žiadna tvár
        // (náhradná cesta zarovnania), nie odtlačok suseda
        val target = box(60f, 60f, 160f, 180f)
        assertEquals(-1, FaceSelect.bestMatch(listOf(box(-60f, 40f, 30f, 170f)), target))
    }

    @Test
    fun equalOverlapPrefersCloserCenter() {
        val target = box(0f, 0f, 100f, 100f)
        val a = box(50f, 0f, 150f, 100f)
        val b = box(-50f, 0f, 50f, 100f)
        assertEquals(0, FaceSelect.bestMatch(listOf(a, b), target))
        val c = box(10f, 0f, 110f, 100f)
        assertEquals(1, FaceSelect.bestMatch(listOf(a, c), target))
    }

    @Test
    fun withoutOverlapTakesNearCenterOnly() {
        val target = box(100f, 100f, 200f, 200f)
        // malý rámček tesne za okrajom (stred 56 px od stredu cieľa, limit = polovica uhlopriečky ≈ 70)
        assertEquals(0, FaceSelect.bestMatch(listOf(box(201f, 130f, 211f, 140f)), target))
        // vedľajšia tvár rovnakej veľkosti (stred 141 px) → nič
        assertEquals(-1, FaceSelect.bestMatch(listOf(box(241f, 100f, 341f, 200f)), target))
        assertEquals(-1, FaceSelect.bestMatch(emptyList(), target))
    }

    @Test
    fun landmarkBoundsAndCentralBox() {
        val xs = floatArrayOf(10f, 30f, 20f)
        val ys = floatArrayOf(5f, 7f, 40f)
        assertArrayEquals(box(10f, 5f, 30f, 40f), FaceSelect.boundsOf(xs, ys), 1e-6f)
        assertArrayEquals(box(20f, 40f, 80f, 160f), FaceSelect.centralBox(100, 200), 1e-6f)
    }
}
