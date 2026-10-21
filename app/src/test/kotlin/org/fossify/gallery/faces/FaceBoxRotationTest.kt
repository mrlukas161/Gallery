package org.fossify.gallery.faces

import org.fossify.gallery.family.BundleRules
import org.fossify.gallery.family.RelBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceBoxRotationTest {

    private val new = FaceBoxRotation.UPRIGHT_SINCE_MS + 1
    private val old = FaceBoxRotation.UPRIGHT_SINCE_MS - 1

    // 1000×750 (na šírku) s tvárou vľavo hore
    private val w = 1000
    private val h = 750

    @Test
    fun rotate90MatchesSpecFormula() {
        // (h-b, l, h-t, r)
        val rotated = FaceBoxRotation.rotate(100, 50, 300, 200, w, h, 90)
        assertArrayEquals(intArrayOf(750 - 200, 100, 750 - 50, 300), rotated)
    }

    @Test
    fun rotate180And270() {
        assertArrayEquals(intArrayOf(700, 550, 900, 700), FaceBoxRotation.rotate(100, 50, 300, 200, w, h, 180))
        assertArrayEquals(intArrayOf(50, 700, 200, 900), FaceBoxRotation.rotate(100, 50, 300, 200, w, h, 270))
        // záporné stupne = proti smeru hodín
        assertArrayEquals(
            FaceBoxRotation.rotate(100, 50, 300, 200, w, h, 270),
            FaceBoxRotation.rotate(100, 50, 300, 200, w, h, -90),
        )
    }

    @Test
    fun fourQuarterTurnsReturnOriginal() {
        var box = intArrayOf(100, 50, 300, 200)
        var size = intArrayOf(w, h)
        repeat(4) {
            box = FaceBoxRotation.rotate(box[0], box[1], box[2], box[3], size[0], size[1], 90)
            size = FaceBoxRotation.rotatedSize(size[0], size[1], 90)
        }
        assertArrayEquals(intArrayOf(100, 50, 300, 200), box)
        assertArrayEquals(intArrayOf(w, h), size)
    }

    @Test
    fun rotatedBoxStaysInsideRotatedImage() {
        for (deg in listOf(90, 180, 270)) {
            val b = FaceBoxRotation.rotate(0, 0, w, h, w, h, deg)
            val s = FaceBoxRotation.rotatedSize(w, h, deg)
            assertArrayEquals("deg=$deg", intArrayOf(0, 0, s[0], s[1]), b)
        }
    }

    @Test
    fun agreesWithRelBoxTransform() {
        // rovnaké otočenie ako family.RelBox (relatívne súradnice), ktorým sa rámčeky prenášajú
        for (deg in listOf(90, 180, 270)) {
            val abs = FaceBoxRotation.rotate(100, 50, 300, 200, w, h, deg)
            val s = FaceBoxRotation.rotatedSize(w, h, deg)
            val rel = RelBox.fromAbsolute(100, 50, 300, 200, w, h).transformed(deg, false)
            assertArrayEquals("deg=$deg", rel.toAbsolute(s[0], s[1]), abs)
        }
    }

    @Test
    fun exifRotationOfNormalPhotoRotates() {
        val before = FaceBoxRotation.State(RelBox.EXIF_NORMAL, w, h)
        val after = FaceBoxRotation.State(RelBox.EXIF_ROTATE_90, h, w)
        assertEquals(
            FaceBoxRotation.Decision.Rotate(90, w, h),
            FaceBoxRotation.decide(before, after, 90, pixelsRewritten = false, faceDetectedAt = listOf(old, new)),
        )
    }

    @Test
    fun exifRotationAddsToExistingOrientationForNewFaces() {
        // fotka na výšku (EXIF 6 = 90°) otočená ešte o 90° → EXIF 3 (180°); rámčeky sú upright (nové)
        val before = FaceBoxRotation.State(RelBox.EXIF_ROTATE_90, h, w)
        val after = FaceBoxRotation.State(RelBox.EXIF_ROTATE_180, w, h)
        assertEquals(
            FaceBoxRotation.Decision.Rotate(90, h, w),
            FaceBoxRotation.decide(before, after, 90, pixelsRewritten = false, faceDetectedAt = listOf(new)),
        )
        // otočenie doľava (−90) z EXIF 6 → EXIF 1
        assertEquals(
            FaceBoxRotation.Decision.Rotate(270, h, w),
            FaceBoxRotation.decide(before, FaceBoxRotation.State(RelBox.EXIF_NORMAL, w, h), -90, false, listOf(new)),
        )
    }

    @Test
    fun oldFacesOnExifRotatedPhotoAreRefreshed() {
        // rámčeky pred 0.47 môžu byť v surovom priestore — otáčať sa nesmú
        val before = FaceBoxRotation.State(RelBox.EXIF_ROTATE_90, h, w)
        val after = FaceBoxRotation.State(RelBox.EXIF_ROTATE_180, w, h)
        val decision = FaceBoxRotation.decide(before, after, 90, false, listOf(new, old))
        assertEquals(FaceBoxRotation.Decision.Refresh, decision)
    }

    @Test
    fun mirroredOrientationIsRefreshed() {
        val before = FaceBoxRotation.State(RelBox.EXIF_FLIP_HORIZONTAL, w, h)
        val after = FaceBoxRotation.State(RelBox.EXIF_ROTATE_90, h, w)
        assertEquals(FaceBoxRotation.Decision.Refresh, FaceBoxRotation.decide(before, after, 90, false, listOf(new)))
    }

    @Test
    fun unexpectedResultIsRefreshed() {
        // dvojité otočenie (čiastočne úspešný pokus) — výsledok je 180°, používateľ chcel 90°
        val before = FaceBoxRotation.State(RelBox.EXIF_NORMAL, w, h)
        val after = FaceBoxRotation.State(RelBox.EXIF_ROTATE_180, w, h)
        assertEquals(FaceBoxRotation.Decision.Refresh, FaceBoxRotation.decide(before, after, 90, false, listOf(new)))
        // nečitateľný stav
        assertEquals(FaceBoxRotation.Decision.Refresh, FaceBoxRotation.decide(null, after, 90, false, listOf(new)))
        assertEquals(FaceBoxRotation.Decision.Refresh, FaceBoxRotation.decide(before, null, 90, false, listOf(new)))
        // nesedia rozmery
        val wrongSize = FaceBoxRotation.State(RelBox.EXIF_ROTATE_90, w, h)
        val sizeDecision = FaceBoxRotation.decide(before, wrongSize, 90, false, listOf(new))
        assertEquals(FaceBoxRotation.Decision.Refresh, sizeDecision)
        // nie násobok 90°
        assertEquals(FaceBoxRotation.Decision.Refresh, FaceBoxRotation.decide(before, after, 45, false, listOf(new)))
    }

    @Test
    fun pixelRewriteOfPhotoWithoutExif() {
        val before = FaceBoxRotation.State(RelBox.EXIF_UNDEFINED, w, h)
        val after = FaceBoxRotation.State(RelBox.EXIF_UNDEFINED, h, w)
        val decision = FaceBoxRotation.decide(before, after, -90, true, listOf(old))
        assertEquals(FaceBoxRotation.Decision.Rotate(270, w, h), decision)
        // pri EXIF otočení pred prepisom sa otáča surový obrázok, nie upright → prepracovať
        val rotated = FaceBoxRotation.State(RelBox.EXIF_ROTATE_90, h, w)
        assertEquals(
            FaceBoxRotation.Decision.Refresh,
            FaceBoxRotation.decide(rotated, FaceBoxRotation.State(RelBox.EXIF_NORMAL, w, h), 90, true, listOf(new)),
        )
    }

    @Test
    fun noFacesOrNoTurnMeansUnchanged() {
        val before = FaceBoxRotation.State(RelBox.EXIF_NORMAL, w, h)
        assertEquals(FaceBoxRotation.Decision.Unchanged, FaceBoxRotation.decide(before, before, 90, false, emptyList()))
        val fullTurn = FaceBoxRotation.decide(before, before, 360, false, listOf(new))
        assertEquals(FaceBoxRotation.Decision.Unchanged, fullTurn)
    }

    @Test
    fun cutoffMatchesBundleRules() {
        assertEquals(BundleRules.UPRIGHT_SINCE_MS, FaceBoxRotation.UPRIGHT_SINCE_MS)
    }
}
