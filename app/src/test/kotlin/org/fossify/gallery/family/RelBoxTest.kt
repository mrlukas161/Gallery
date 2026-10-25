package org.fossify.gallery.family

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RelBoxTest {
    private val eps = 1e-6f

    private fun assertBox(expected: RelBox, actual: RelBox) {
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
    }

    @Test
    fun absoluteRoundTrip() {
        val rel = RelBox.fromAbsolute(100, 50, 300, 250, 1000, 800)
        assertBox(RelBox(0.1f, 0.0625f, 0.3f, 0.3125f), rel)
        assertArrayEquals(intArrayOf(100, 50, 300, 250), rel.toAbsolute(1000, 800))
    }

    @Test
    fun scaledCopyKeepsPosition() {
        // rámček z originálu 4000×3000 sedí aj na zmenšenej kópii 1600×1200 (WhatsApp)
        val rel = RelBox.fromAbsolute(1000, 600, 1500, 1200, 4000, 3000)
        assertArrayEquals(intArrayOf(400, 240, 600, 480), rel.toAbsolute(1600, 1200))
    }

    @Test
    fun rounding() {
        val rel = RelBox(0.3333333f, 0.5f, 0.6666667f, 0.999f)
        // 0.3333333 * 10 = 3.33 → 3; 0.5 * 3 = 1.5 → 2; 0.6666667 * 10 = 6.67 → 7; 0.999 * 3 = 2.997 → 3
        assertArrayEquals(intArrayOf(3, 2, 7, 3), rel.toAbsolute(10, 3))
    }

    @Test
    fun clampsOutOfRange() {
        val rel = RelBox.fromAbsolute(-20, -5, 1200, 900, 1000, 800)
        assertBox(RelBox(0f, 0f, 1f, 1f), rel)
        assertArrayEquals(intArrayOf(0, 0, 50, 40), RelBox(-0.1f, -0.2f, 1.5f, 2f).toAbsolute(50, 40))
    }

    @Test
    fun exifMappingMatchesUprightDecoder() {
        assertEquals(RelBox.Transform(0, false), RelBox.exifTransform(RelBox.EXIF_NORMAL))
        assertEquals(RelBox.Transform(0, false), RelBox.exifTransform(RelBox.EXIF_UNDEFINED))
        assertEquals(RelBox.Transform(0, true), RelBox.exifTransform(RelBox.EXIF_FLIP_HORIZONTAL))
        assertEquals(RelBox.Transform(180, false), RelBox.exifTransform(RelBox.EXIF_ROTATE_180))
        assertEquals(RelBox.Transform(180, true), RelBox.exifTransform(RelBox.EXIF_FLIP_VERTICAL))
        assertEquals(RelBox.Transform(270, true), RelBox.exifTransform(RelBox.EXIF_TRANSPOSE))
        assertEquals(RelBox.Transform(90, false), RelBox.exifTransform(RelBox.EXIF_ROTATE_90))
        assertEquals(RelBox.Transform(90, true), RelBox.exifTransform(RelBox.EXIF_TRANSVERSE))
        assertEquals(RelBox.Transform(270, false), RelBox.exifTransform(RelBox.EXIF_ROTATE_270))
        assertEquals(RelBox.Transform(0, false), RelBox.exifTransform(99))
    }

    // E1: EXIF 5 (TRANSPOSE) = preklopenie podľa hlavnej uhlopriečky (x, y) → (y, x), presne ako Glide
    // (TransformationUtils: setRotate(90) + postScale(-1, 1)); EXIF 7 = podľa vedľajšej uhlopriečky
    @Test
    fun transposeAndTransverseMatchGlide() {
        val src = RelBox(0.1f, 0.2f, 0.3f, 0.6f)
        val t5 = RelBox.exifTransform(RelBox.EXIF_TRANSPOSE)
        assertBox(RelBox(0.2f, 0.1f, 0.6f, 0.3f), src.transformed(t5.rotationDegrees, t5.mirrored))
        val t7 = RelBox.exifTransform(RelBox.EXIF_TRANSVERSE)
        assertBox(RelBox(0.4f, 0.7f, 0.8f, 0.9f), src.transformed(t7.rotationDegrees, t7.mirrored))
        // stará (chybná) hypotéza v1 je o 180° otočená oproti správnej
        val v1For5 = src.transformed(90, true)
        assertBox(src.transformed(t5.rotationDegrees, t5.mirrored), v1For5.transformed(180, false))
    }

    // pixelový prepočet ako UprightDecoder.mapBox: surový bod (x, y) skončí pri EXIF 5 na (y, x)
    @Test
    fun transposePixelsLikeGlide() {
        val rawW = 400
        val rawH = 300
        val t5 = RelBox.exifTransform(RelBox.EXIF_TRANSPOSE)
        val mapped = mapBox(10, 20, 50, 80, rawW, rawH, t5.rotationDegrees, t5.mirrored)
        assertArrayEquals(intArrayOf(20, 10, 80, 50), mapped)
        val t7 = RelBox.exifTransform(RelBox.EXIF_TRANSVERSE)
        // anti-transpozícia: (x, y) → (H − y, W − x)
        assertArrayEquals(
            intArrayOf(rawH - 80, rawW - 50, rawH - 20, rawW - 10),
            mapBox(10, 20, 50, 80, rawW, rawH, t7.rotationDegrees, t7.mirrored),
        )
    }

    // referenčný prepočet v pixeloch, prepísaný 1:1 z UprightDecoder.mapBox
    @Suppress("LongParameterList")
    private fun mapBox(l0: Int, t: Int, r0: Int, b: Int, rawW: Int, rawH: Int, deg: Int, mirrored: Boolean): IntArray {
        val l = if (mirrored) rawW - r0 else l0
        val r = if (mirrored) rawW - l0 else r0
        return when (deg) {
            90 -> intArrayOf(rawH - b, l, rawH - t, r)
            180 -> intArrayOf(rawW - r, rawH - b, rawW - l, rawH - t)
            270 -> intArrayOf(t, rawW - r, b, rawW - l)
            else -> intArrayOf(l, t, r, b)
        }
    }

    @Test
    fun rotationsMatchMapBox() {
        val rawW = 4000
        val rawH = 3000
        val box = intArrayOf(400, 300, 1200, 1500)
        for (deg in intArrayOf(0, 90, 180, 270)) {
            for (mirrored in booleanArrayOf(false, true)) {
                val expected = mapBox(box[0], box[1], box[2], box[3], rawW, rawH, deg, mirrored)
                val size = RelBox.uprightSize(rawW, rawH, deg)
                val actual = RelBox.fromAbsolute(box[0], box[1], box[2], box[3], rawW, rawH)
                    .transformed(deg, mirrored)
                    .toAbsolute(size[0], size[1])
                assertArrayEquals("deg=$deg mirrored=$mirrored", expected, actual)
            }
        }
    }

    @Test
    fun rotate90Concrete() {
        // na výšku (EXIF 6): ľavý horný roh surového obrázka je po otočení v pravom hornom rohu
        val rel = RelBox(0f, 0f, 0.25f, 0.5f).transformed(90, false)
        assertBox(RelBox(0.5f, 0f, 1f, 0.25f), rel)
    }

    @Test
    fun rotate180And270Concrete() {
        val src = RelBox(0.1f, 0.2f, 0.3f, 0.4f)
        assertBox(RelBox(0.7f, 0.6f, 0.9f, 0.8f), src.transformed(180, false))
        assertBox(RelBox(0.2f, 0.7f, 0.4f, 0.9f), src.transformed(270, false))
    }

    @Test
    fun untransformIsInverse() {
        val src = RelBox(0.1f, 0.2f, 0.35f, 0.6f)
        for (deg in intArrayOf(0, 90, 180, 270)) {
            for (mirrored in booleanArrayOf(false, true)) {
                assertBox(src, src.transformed(deg, mirrored).untransformed(deg, mirrored))
            }
        }
        // štyri otočenia o 90° = pôvodný rámček
        assertBox(src, src.transformed(90, false).transformed(90, false).transformed(90, false).transformed(90, false))
    }

    @Test
    fun uprightSizes() {
        assertArrayEquals(intArrayOf(4000, 3000), RelBox.uprightSize(4000, 3000, 0))
        assertArrayEquals(intArrayOf(3000, 4000), RelBox.uprightSize(4000, 3000, 90))
        assertArrayEquals(intArrayOf(4000, 3000), RelBox.uprightSize(4000, 3000, 180))
        assertArrayEquals(intArrayOf(3000, 4000), RelBox.uprightSize(4000, 3000, 270))
        assertArrayEquals(intArrayOf(3000, 4000), RelBox.uprightSize(4000, 3000, -90))
    }

    @Test
    fun sampleSizeLikeDecoder() {
        assertEquals(1, RelBox.sampleSize(1000, 800, 1600))
        assertEquals(1, RelBox.sampleSize(1600, 1200, 1600))
        assertEquals(2, RelBox.sampleSize(1601, 1200, 1600))
        assertEquals(4, RelBox.sampleSize(4000, 3000, 1600))
        assertEquals(8, RelBox.sampleSize(3000, 8000, 1600))
    }

    @Test
    fun decodedUprightSize() {
        // 4000×3000 na výšku (EXIF 6), maxSize 1600 → sample 4 → 1000×750 → otočené 750×1000
        assertArrayEquals(intArrayOf(750, 1000), RelBox.decodedUprightSize(4000, 3000, 1600, 90))
        assertArrayEquals(intArrayOf(1000, 750), RelBox.decodedUprightSize(4000, 3000, 1600, 180))
    }

    @Test
    fun portraitPhotoAcrossDevicesWithDifferentDecodeSize() {
        // odosielateľ: originál 4000×3000 s EXIF 6, detekcia nad upright bitmapou 750×1000
        val senderSize = RelBox.decodedUprightSize(4000, 3000, 1600, 90)
        val faceOnSender = intArrayOf(300, 200, 450, 400)
        val rel = RelBox.fromAbsolute(
            faceOnSender[0], faceOnSender[1], faceOnSender[2], faceOnSender[3], senderSize[0], senderSize[1],
        )
        // prijímateľ: WhatsApp kópia 1600×1200 (raw) s EXIF 6 → upright 1200×1600 pri maxSize 2048
        val receiverSize = RelBox.decodedUprightSize(1600, 1200, 2048, 90)
        assertArrayEquals(intArrayOf(1200, 1600), receiverSize)
        assertArrayEquals(intArrayOf(480, 320, 720, 640), rel.toAbsolute(receiverSize[0], receiverSize[1]))
    }
}
