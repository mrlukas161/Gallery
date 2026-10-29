package org.fossify.gallery.faces

import org.fossify.gallery.family.BoxSpace
import org.fossify.gallery.family.BundleRules
import org.fossify.gallery.family.FaceMatch
import org.fossify.gallery.family.FaceSpaceRules
import org.fossify.gallery.family.RelBox
import org.fossify.gallery.family.StoredBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// E1 — priestor rámčeka (box_space) a oprava EXIF 5/7
class FaceBoxSpaceTest {
    private val eps = 1e-5f
    private val t5 = RelBox.exifTransform(RelBox.EXIF_TRANSPOSE)
    private val t7 = RelBox.exifTransform(RelBox.EXIF_TRANSVERSE)
    private val t6 = RelBox.exifTransform(RelBox.EXIF_ROTATE_90)
    private val none = RelBox.exifTransform(RelBox.EXIF_NORMAL)
    private val old = 1_700_000_000_000L // pred 0.47

    @Test
    fun transposedOnlyFor5And7() {
        assertTrue(FaceBoxSpace.isTransposed(t5))
        assertTrue(FaceBoxSpace.isTransposed(t7))
        for (o in intArrayOf(0, 1, 2, 3, 4, 6, 8)) {
            assertFalse("$o", FaceBoxSpace.isTransposed(RelBox.exifTransform(o)))
        }
        assertTrue(FaceBoxSpace.isTransposedExif(5) && FaceBoxSpace.isTransposedExif(7))
    }

    @Test
    fun v1BoxOnTransposedPhotoRotates180ToV2() {
        val box = intArrayOf(10, 20, 110, 220)
        // upright 768×1024 (5/7 vymieňajú strany): v1 → v2 = otočenie o 180°
        assertArrayEquals(intArrayOf(658, 804, 758, 1004), FaceBoxSpace.toV2(box, 1, true, 768, 1024))
        // dvakrát = pôvodný
        assertArrayEquals(box, FaceBoxSpace.rotate180(FaceBoxSpace.rotate180(box, 768, 1024), 768, 1024))
        // v2 a nepreklopené orientácie bez zmeny
        assertArrayEquals(box, FaceBoxSpace.toV2(box, 2, true, 768, 1024))
        assertArrayEquals(box, FaceBoxSpace.toV2(box, 1, false, 768, 1024))
    }

    @Test
    fun v1MappingWasOff180ComparedToGlide() {
        // surový bod rámčeka → upright starým (5 = zrkadlenie + 90°) a novým (zrkadlenie + 270°) mapovaním
        val raw = RelBox(0.1f, 0.2f, 0.3f, 0.5f)
        val v1 = raw.transformed(90, true)
        val v2 = raw.transformed(t5.rotationDegrees, t5.mirrored)
        val fixed = v1.transformed(180, false)
        assertEquals(v2.left, fixed.left, eps)
        assertEquals(v2.top, fixed.top, eps)
        assertEquals(v2.right, fixed.right, eps)
        assertEquals(v2.bottom, fixed.bottom, eps)
    }

    @Test
    fun deriveSpaceForOldFaces() {
        // bez otočenia je priestor vždy istý
        assertEquals(FaceBoxSpace.Derived(2, false), FaceBoxSpace.derive(none, null))
        // otočená fotka: len keď je upright istý; na 5/7 s otočením o 180°
        assertEquals(FaceBoxSpace.Derived(2, false), FaceBoxSpace.derive(t6, true))
        assertEquals(FaceBoxSpace.Derived(2, true), FaceBoxSpace.derive(t5, true))
        assertEquals(FaceBoxSpace.Derived(2, true), FaceBoxSpace.derive(t7, true))
        assertNull(FaceBoxSpace.derive(t6, null))
        assertNull(FaceBoxSpace.derive(t5, false)) // surový ostane neznámy (dve hypotézy)
    }

    @Test
    fun knownSpaceGivesSingleHypothesis() {
        // 4000×3000 s EXIF 6, starý rámček, ktorý sa zmestí do oboch priestorov
        val unknown = FaceMatch.relBoxes(100, 100, 200, 200, 4000, 3000, t6)
        assertEquals(2, unknown.size)
        val known = FaceMatch.relBoxes(100, 100, 200, 200, 4000, 3000, t6, FaceBoxSpace.UPRIGHT_V2)
        assertEquals(listOf(unknown[0]), known)
        // FaceSpaceRules: známy priestor rozhodne aj bez dátumu a overenia
        val stored = StoredBox(100, 100, 200, 200, old, FaceBoxSpace.UPRIGHT_V2)
        assertEquals(BoxSpace.UPRIGHT, FaceSpaceRules.resolve(stored, 4000, 3000, t6))
        assertNull(FaceSpaceRules.resolve(stored.copy(boxSpace = 0), 4000, 3000, t6))
        // balík k fotke: starý rámček s box_space sa pošle (predtým by sa celá fotka vynechala)
        assertNotNull(BundleRules.uprightBoxes(listOf(stored), 4000, 3000, t6))
        assertNull(BundleRules.uprightBoxes(listOf(stored.copy(boxSpace = 0)), 4000, 3000, t6))
    }

    @Test
    fun v1BoxOnTransposedPhotoSendsCorrectedRelBox() {
        // 4000×3000 s EXIF 5 → dekódované 1000×750 → upright 750×1000; rámček v1 a ten istý preklopený na v2
        val v1 = intArrayOf(10, 20, 110, 220)
        val v2 = FaceBoxSpace.rotate180(v1, 750, 1000)
        val boxV1 = StoredBox(v1[0], v1[1], v1[2], v1[3], old, 1)
        val boxV2 = StoredBox(v2[0], v2[1], v2[2], v2[3], old, 2)
        val fromV1 = FaceSpaceRules.relBox(boxV1, 4000, 3000, t5, BoxSpace.UPRIGHT)
        val fromV2 = FaceSpaceRules.relBox(boxV2, 4000, 3000, t5, BoxSpace.UPRIGHT)
        assertEquals(fromV2, fromV1)
        // FaceMatch so známym v1 priestorom dá tú istú (opravenú) jedinú hypotézu
        assertEquals(listOf(fromV2), FaceMatch.relBoxes(v1[0], v1[1], v1[2], v1[3], 4000, 3000, t5, 1))
    }
}
