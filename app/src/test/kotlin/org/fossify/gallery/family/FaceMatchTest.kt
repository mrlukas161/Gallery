package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceMatchTest {

    private val identity = RelBox.exifTransform(RelBox.EXIF_NORMAL)
    private val rot90 = RelBox.exifTransform(RelBox.EXIF_ROTATE_90)

    @Test
    fun unrotatedPhotoHasSingleHypothesis() {
        // 4000×3000 → dekódované 1000×750
        val boxes = FaceMatch.relBoxes(100, 75, 200, 150, 4000, 3000, identity)
        assertEquals(listOf(RelBox(0.1f, 0.1f, 0.2f, 0.2f)), boxes)
    }

    @Test
    fun rotatedPhotoHasUprightAndRawHypothesis() {
        // surový 4000×3000, EXIF 90° → upright 750×1000
        val boxes = FaceMatch.relBoxes(75, 100, 150, 200, 4000, 3000, rot90)
        assertEquals(2, boxes.size)
        assertEquals(RelBox(0.1f, 0.1f, 0.2f, 0.2f), boxes[0])
        // ten istý rámček chápaný ako neotočený (1000×750) a otočený do upright priestoru
        val raw = RelBox(75 / 1000f, 100 / 750f, 150 / 1000f, 200 / 750f)
        assertEquals(raw.transformed(RelBox.DEG_90, false), boxes[1])
    }

    @Test
    fun iouOfSameAndDisjointBoxes() {
        val a = RelBox(0f, 0f, 0.5f, 0.5f)
        assertEquals(1f, FaceMatch.iou(a, a), 1e-6f)
        assertEquals(0f, FaceMatch.iou(a, RelBox(0.6f, 0.6f, 0.9f, 0.9f)), 1e-6f)
        assertEquals(1f / 7f, FaceMatch.iou(a, RelBox(0.25f, 0.25f, 0.75f, 0.75f)), 1e-5f)
    }

    @Test
    fun bestPicksOverlappingFaceOnly() {
        val remote = listOf(RelBox(0.1f, 0.1f, 0.3f, 0.3f))
        val candidates = listOf(
            1L to listOf(RelBox(0.6f, 0.1f, 0.8f, 0.3f)),
            2L to listOf(RelBox(0.11f, 0.1f, 0.31f, 0.3f)),
        )
        assertEquals(2L, FaceMatch.best(remote, candidates))
        assertNull(FaceMatch.best(remote, candidates.take(1)))
    }

    @Test
    fun ambiguousDuplicatesGiveNoMatch() {
        val remote = listOf(RelBox(0.1f, 0.1f, 0.3f, 0.3f))
        val dup = listOf(RelBox(0.1f, 0.1f, 0.3f, 0.3f))
        assertNull(FaceMatch.best(remote, listOf(1L to dup, 2L to dup)))
    }

    @Test
    fun oldRawSpaceBoxMatchesThroughSecondHypothesis() {
        // odosielateľ má nový upright rámček, prijímateľ starý surový rámček tej istej tváre
        val upright = RelBox(0.1f, 0.2f, 0.3f, 0.4f)
        val receiverRaw = upright.untransformed(RelBox.DEG_90, false)
        // prijímateľov rámček v pixeloch neotočenej bitmapy 1000×750
        val b = receiverRaw.toAbsolute(1000, 750)
        val local = FaceMatch.relBoxes(b[0], b[1], b[2], b[3], 4000, 3000, rot90)
        assertEquals(1L, FaceMatch.best(listOf(upright), listOf(1L to local)))
    }
}
