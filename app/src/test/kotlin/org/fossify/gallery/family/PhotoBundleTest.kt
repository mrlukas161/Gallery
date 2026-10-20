package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Serializácia balíka k fotke (F4). org.json je v unit testoch skutočná knižnica (testImplementation).
class PhotoBundleTest {
    private val full = PhotoBundle(
        contentKey = "ab".repeat(32),
        name = "IMG_20261020_140305.jpg",
        mime = "image/jpeg",
        size = 3_456_789L,
        takenAtMs = 1_792_500_000_000L,
        faceModelId = "mobilefacenet-112-l2-v1",
        faces = listOf(
            BundleFace(RelBox(0.1f, 0.2f, 0.3f, 0.45f), 0.93f, "AAEC", "uuid-1", manual = true),
            BundleFace(RelBox(0.5f, 0.5f, 0.75f, 0.8f), 0.71f, null, null, ignored = true),
        ),
        ocr = "Faktúra „č. 12“\nSpolu: 12,50 €",
        geo = BundleGeo(48.1486, 17.1077, true),
        phash = -0x0123_4567_89AB_CDEFL,
        clip = BundleClip(FamilyModels.CLIP_MODEL_ID, "Zm9vYmFy"),
        qr = "https://example.org/?a=1&b=2",
    )

    @Test
    fun roundTripFull() {
        val decoded = PhotoBundleCodec.decode(PhotoBundleCodec.encode(full))
        assertNotNull(decoded)
        decoded!!
        assertEquals(full.copy(faces = null), decoded.copy(faces = null))
        val faces = decoded.faces!!
        assertEquals(2, faces.size)
        for ((a, b) in full.faces!!.zip(faces)) {
            assertEquals(a.box.left, b.box.left, EPS)
            assertEquals(a.box.top, b.box.top, EPS)
            assertEquals(a.box.right, b.box.right, EPS)
            assertEquals(a.box.bottom, b.box.bottom, EPS)
            assertEquals(a.score, b.score, EPS)
            assertEquals(a.copy(box = b.box, score = b.score), b)
        }
    }

    @Test
    fun missingPartsStayNull() {
        // nespracované časti (null) a spracované bez výsledku (prázdne) sa musia dať rozlíšiť
        val minimal = PhotoBundle("k", "a.mp4", "video/mp4", 10L, faces = emptyList(), ocr = "")
        val decoded = PhotoBundleCodec.decode(PhotoBundleCodec.encode(minimal))!!
        assertEquals(minimal, decoded)
        assertEquals(emptyList<BundleFace>(), decoded.faces)
        assertEquals("", decoded.ocr)
        assertNull(decoded.geo)
        assertNull(decoded.phash)
        assertNull(decoded.clip)
        assertNull(decoded.qr)
        assertNull(decoded.takenAtMs)
        assertNull(decoded.faceModelId)
    }

    @Test
    fun unknownKeysIgnored() {
        val json = """{"v":7,"ck":"k","name":"a.jpg","mime":"image/jpeg","size":5,"novinka":{"x":1}}"""
        val decoded = PhotoBundleCodec.decode(json)
        assertEquals(PhotoBundle("k", "a.jpg", "image/jpeg", 5L), decoded)
    }

    @Test
    fun damagedRejected() {
        assertNull(PhotoBundleCodec.decode("nie je json"))
        assertNull(PhotoBundleCodec.decode("""{"name":"a.jpg","size":5}"""))
        assertNull(PhotoBundleCodec.decode("""{"ck":"","size":5}"""))
        assertNull(PhotoBundleCodec.decode("""{"ck":"k","size":"veľa"}"""))
        // rámček mimo 0–1 alebo s nulovou plochou sa neprijme
        assertNull(PhotoBundleCodec.decode("""{"ck":"k","size":5,"faces":[{"b":[0.1,0.1,1.5,0.2],"s":1}]}"""))
        assertNull(PhotoBundleCodec.decode("""{"ck":"k","size":5,"faces":[{"b":[0.3,0.1,0.3,0.2],"s":1}]}"""))
        assertNull(PhotoBundleCodec.decode("""{"ck":"k","size":5,"faces":[{"b":[0.1,0.1,0.2],"s":1}]}"""))
    }

    @Test
    fun nanScoreEncodable() {
        val face = BundleFace(RelBox(0f, 0f, 1f, 1f), Float.NaN)
        val b = PhotoBundle("k", "a.jpg", "image/jpeg", 1L, faces = listOf(face))
        val decoded = PhotoBundleCodec.decode(PhotoBundleCodec.encode(b))!!
        assertEquals(0f, decoded.faces!!.single().score, 0f)
    }

    @Test
    fun formatKeysAreStable() {
        // formát je súčasťou prenosu medzi telefónmi: kľúče sa nesmú premenovať
        val json = PhotoBundleCodec.encode(full)
        for (key in listOf("v", "ck", "fm", "faces", "ocr", "geo", "ph", "clip", "qr")) {
            assertTrue(key, json.contains("\"$key\":"))
        }
    }

    private companion object {
        const val EPS = 1e-6f
    }
}
