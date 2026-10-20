package org.fossify.gallery.family

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

// Rozhodovanie F4: čo sa z balíka prevezme, prepočet súradníc a rámčeky s nejasným priestorom.
class BundleRulesTest {
    private val faceModel = "mobilefacenet-112-l2-v1"
    private val bundle = PhotoBundle(
        contentKey = "k",
        name = "a.jpg",
        mime = "image/jpeg",
        size = 1L,
        faceModelId = faceModel,
        faces = listOf(BundleFace(RelBox(0.1f, 0.1f, 0.2f, 0.2f), 0.9f)),
        ocr = "text",
        geo = BundleGeo(1.0, 2.0, true),
        phash = 42L,
        clip = BundleClip(FamilyModels.CLIP_MODEL_ID, "AA"),
        qr = "qr",
    )

    private fun plan(
        b: PhotoBundle = bundle,
        match: BundleMatch = BundleMatch.EXACT,
        have: LocalResults = LocalResults(),
        image: Boolean = true,
        localFaceModel: String = faceModel,
        localClip: String = FamilyModels.CLIP_MODEL_ID,
    ) = BundleRules.plan(b, match, have, image, localFaceModel, localClip)

    @Test
    fun exactMatchTakesEverything() {
        assertEquals(TakePlan(faces = true, ocr = true, geo = true, phash = true, clip = true, qr = true), plan())
    }

    @Test
    fun fuzzyMatchSkipsOcrAndPhash() {
        // spec 2.1: zhoda len cez zálohu neprenesie OCR; pHash prekomprimovanej kópie je jej vlastný
        val p = plan(match = BundleMatch.FUZZY)
        assertFalse(p.ocr)
        assertFalse(p.phash)
        assertTrue(p.faces)
        assertTrue(p.geo)
        assertTrue(p.clip)
        assertTrue(p.qr)
    }

    @Test
    fun otherFaceModelSkipsFaces() {
        assertFalse(plan(localFaceModel = "iny-model").faces)
        assertFalse(plan(b = bundle.copy(faceModelId = null)).faces)
        assertTrue(plan(localFaceModel = "iny-model").ocr)
    }

    @Test
    fun otherClipModelSkipsClip() {
        assertFalse(plan(localClip = "iny-clip").clip)
        assertFalse(plan(b = bundle.copy(clip = BundleClip("iny-clip", "AA"))).clip)
    }

    @Test
    fun ownResultsNeverOverwritten() {
        val have = LocalResults(faces = true, ocr = true, geo = true, phash = true, clip = true, qr = true)
        assertFalse(plan(have = have).any())
        val partial = plan(have = LocalResults(faces = true, ocr = true))
        assertEquals(TakePlan(geo = true, phash = true, clip = true, qr = true), partial)
    }

    @Test
    fun missingPartsNotTaken() {
        val empty = PhotoBundle("k", "a.jpg", "image/jpeg", 1L)
        assertFalse(plan(b = empty).any())
        // spracované bez výsledku (žiadne tváre, žiadny text) sa prevezme ako značka „spracovaná“
        val nothingFound = empty.copy(faceModelId = faceModel, faces = emptyList(), ocr = "")
        assertEquals(TakePlan(faces = true, ocr = true), plan(b = nothingFound))
    }

    @Test
    fun videoTakesOnlyLocation() {
        assertEquals(TakePlan(geo = true), plan(image = false))
    }

    @Test
    fun relativeBoxFitsDownscaledCopy() {
        // odosielateľ: 4000×3000, rámčeky v bitmape 1000×750 (UprightDecoder.decode(path, 1024))
        val senderSize = RelBox.decodedUprightSize(4000, 3000, FaceMatch.DECODE_MAX_SIZE, 0)
        assertArrayEquals(intArrayOf(1000, 750), senderSize)
        val stored = listOf(StoredBox(100, 150, 300, 450, 0L))
        val boxes = BundleRules.uprightBoxes(stored, 4000, 3000, RelBox.Transform(0, false))
        val rel = boxes!!.single()
        // prijímateľ: kópia z WhatsAppu 1600×1200 → bitmapa 800×600 (inSampleSize 2)
        val receiverSize = RelBox.decodedUprightSize(1600, 1200, FaceMatch.DECODE_MAX_SIZE, 0)
        assertArrayEquals(intArrayOf(800, 600), receiverSize)
        assertArrayEquals(intArrayOf(80, 120, 240, 360), BundleRules.absoluteBox(rel, receiverSize[0], receiverSize[1]))
        // tá istá fotka v plnej veľkosti → rovnaké absolútne súradnice ako u odosielateľa
        assertArrayEquals(intArrayOf(100, 150, 300, 450), BundleRules.absoluteBox(rel, senderSize[0], senderSize[1]))
    }

    @Test
    fun rotatedPhotoNewFacesAreUpright() {
        // fotka na výšku (EXIF 6): surová 4000×3000, upright bitmapa 750×1000
        val rot = RelBox.Transform(RelBox.DEG_90, false)
        val box = StoredBox(100, 100, 200, 200, BundleRules.UPRIGHT_SINCE_MS)
        val rel = BundleRules.uprightBoxes(listOf(box), 4000, 3000, rot)!!.single()
        assertEquals(RelBox.fromAbsolute(100, 100, 200, 200, 750, 1000), rel)
    }

    @Test
    fun rotatedPhotoAmbiguousOldFaceNotSent() {
        // starý rámček, ktorý sa zmestí do upright (750×1000) aj surového (1000×750) priestoru → nejasný
        val rot = RelBox.Transform(RelBox.DEG_90, false)
        val old = StoredBox(100, 100, 200, 200, BundleRules.UPRIGHT_SINCE_MS - 1)
        assertNull(BundleRules.uprightBoxes(listOf(old), 4000, 3000, rot))
        // jedna nejasná tvár = žiadne tváre z tejto fotky (prijímateľ si ju spracuje celú sám)
        val fresh = StoredBox(10, 10, 50, 50, BundleRules.UPRIGHT_SINCE_MS)
        assertNull(BundleRules.uprightBoxes(listOf(fresh, old), 4000, 3000, rot))
    }

    @Test
    fun rotatedPhotoOldFaceDecidedBySize() {
        val rot = RelBox.Transform(RelBox.DEG_90, false)
        // bottom 900 > 750: zmestí sa len do upright 750×1000
        val upright = StoredBox(100, 800, 200, 900, 0L)
        assertEquals(
            RelBox.fromAbsolute(100, 800, 200, 900, 750, 1000),
            BundleRules.uprightBoxes(listOf(upright), 4000, 3000, rot)!!.single(),
        )
        // right 900 > 750: zmestí sa len do surového 1000×750 → prepočíta sa do upright
        val raw = StoredBox(800, 100, 900, 200, 0L)
        val expected = RelBox.fromAbsolute(800, 100, 900, 200, 1000, 750).transformed(RelBox.DEG_90, false)
        val rel = BundleRules.uprightBoxes(listOf(raw), 4000, 3000, rot)!!.single()
        assertEquals(expected, rel)
        // overenie proti UprightDecoder.mapBox (surový → upright v pixeloch)
        assertArrayEquals(intArrayOf(550, 800, 650, 900), rel.toAbsolute(750, 1000))
    }

    @Test
    fun rotated180AndMirrorAlwaysAmbiguousForOldFaces() {
        val old = StoredBox(10, 10, 20, 20, 0L)
        assertNull(BundleRules.uprightBoxes(listOf(old), 4000, 3000, RelBox.Transform(RelBox.DEG_180, false)))
        assertNull(BundleRules.uprightBoxes(listOf(old), 4000, 3000, RelBox.Transform(0, true)))
        assertNotNull(BundleRules.uprightBoxes(listOf(old), 4000, 3000, RelBox.Transform(0, false)))
    }

    @Test
    fun noFacesIsNotAmbiguous() {
        val rot = RelBox.Transform(RelBox.DEG_90, false)
        assertEquals(emptyList<RelBox>(), BundleRules.uprightBoxes(emptyList(), 4000, 3000, rot))
        assertNull(BundleRules.uprightBoxes(emptyList(), 0, 3000, RelBox.Transform(0, false)))
    }

    @Test
    fun sendablePaths() {
        val bin = "/data/user/0/org.fossify.gallery/files/.trash"
        assertTrue(BundleRules.isSendablePath("/storage/emulated/0/DCIM/Camera/IMG_1.jpg", bin))
        assertFalse(BundleRules.isSendablePath("$bin/IMG_1.jpg", bin))
        assertFalse(BundleRules.isSendablePath("/storage/emulated/0/DCIM/.skryte/IMG_1.jpg", bin))
        assertFalse(BundleRules.isSendablePath("/storage/emulated/0/DCIM/Camera/.IMG_1.jpg", bin))
        assertFalse(BundleRules.isSendablePath("", null))
        assertTrue(BundleRules.isSendablePath("/storage/emulated/0/a.b/IMG_1.jpg", null))
    }

    @Test
    fun exifDate() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(1_792_505_985_000L, BundleRules.parseExifDate("2026:10:20 14:19:45", utc))
        assertEquals(1_792_505_985_000L, BundleRules.parseExifDate(" 2026:10:20 14:19:45 ", utc))
        assertNull(BundleRules.parseExifDate(null, utc))
        assertNull(BundleRules.parseExifDate("", utc))
        assertNull(BundleRules.parseExifDate("0000:00:00 00:00:00", utc))
        assertNull(BundleRules.parseExifDate("2026:13:45 99:00:00", utc))
        assertNull(BundleRules.parseExifDate("včera", utc))
    }

    @Test
    fun windows() {
        val received = 1_792_505_985_000L
        assertTrue(BundleRules.inFuzzyWindow(received - BundleRules.FUZZY_WINDOW_MS, received))
        assertFalse(BundleRules.inFuzzyWindow(received + BundleRules.FUZZY_WINDOW_MS + 1, received))
        assertFalse(BundleRules.isExpired(received, received + BundleRules.WAIT_EXPIRY_MS))
        assertTrue(BundleRules.isExpired(received, received + BundleRules.WAIT_EXPIRY_MS + 1))
    }
}
