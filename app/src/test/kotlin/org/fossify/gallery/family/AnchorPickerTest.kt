package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AnchorPickerTest {

    private fun unit(angle: Double) = floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat())

    @Test
    fun smallSetIsReturnedWhole() {
        val v = listOf(unit(0.0), unit(1.0), unit(1.0))
        assertEquals(2, AnchorPicker.pick(v).size) // duplikát vypadne
    }

    @Test
    fun pickIsCappedAtTwenty() {
        val v = (0 until 100).map { unit(it / 100.0) }
        assertEquals(AnchorsSet.MAX_ANCHORS, AnchorPicker.pick(v).size)
    }

    @Test
    fun pickPrefersDiverseFaces() {
        // 50 takmer rovnakých záberov (uhol ~0) a 2 odlišné (uhol 1 a 2): oba odlišné musia byť vo výbere
        val same = (0 until 50).map { unit(it / 10_000.0) }
        val odd1 = unit(1.0)
        val odd2 = unit(2.0)
        val picked = AnchorPicker.pick(same + odd1 + odd2, max = 3)
        assertTrue(picked.any { it.contentEquals(odd1) })
        assertTrue(picked.any { it.contentEquals(odd2) })
    }

    @Test
    fun mismatchedDimensionsAreIgnored() {
        val picked = AnchorPicker.pick(listOf(unit(0.0), floatArrayOf(1f, 0f, 0f)))
        assertEquals(1, picked.size)
    }

    @Test
    fun pickIsDeterministic() {
        val v = (0 until 60).map { unit(it * 0.37) }
        assertEquals(AnchorPicker.pick(v).map { it.toList() }, AnchorPicker.pick(v).map { it.toList() })
    }
}
