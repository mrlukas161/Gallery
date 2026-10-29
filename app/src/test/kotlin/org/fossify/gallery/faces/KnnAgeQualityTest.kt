package org.fossify.gallery.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

// E1 — vekové prototypy (KnnModel.ageWeight) a brána kvality (PersonGrouper.prototypeFaces, automatika).
class KnnAgeQualityTest {
    private val dim = 8
    private var nextId = 1L
    private val year = 365L * 24 * 60 * 60 * 1000 + 6L * 60 * 60 * 1000
    private val y2020 = 1_577_836_800_000L
    private val y2026 = y2020 + 6 * year

    private fun e(i: Int) = FloatArray(dim).also { it[i] = 1f }

    private fun mix(a: Float, i: Int, j: Int): FloatArray {
        val v = FloatArray(dim)
        v[i] = a
        v[j] = sqrt(1f - a * a)
        return v
    }

    private fun face(vec: FloatArray, takenAt: Long? = null, quality: Float? = null) = FaceEntity(
        id = nextId++, mediaFullPath = "/p/$nextId.jpg", mediaStoreId = 0, faceIndex = 0,
        bboxLeft = 0, bboxTop = 0, bboxRight = 80, bboxBottom = 80, score = 0.9f,
        embedding = FaceEmbedder.toBytes(vec), detectedAt = 0, quality = quality, takenAt = takenAt,
    )

    private fun person(id: Long, faces: List<FaceEntity>): List<Person> = PersonGrouper.confirmedPersons(
        faces, listOf(PersonEntity(id, "P$id")), faces.map { FaceAssignmentEntity(it.id!!, id, true, 0) },
    )

    private fun model(persons: List<Person>) =
        PersonGrouper.buildKnn(persons, emptyMap(), emptyList(), persons.flatMap { it.faces }.associateBy { it.id!! })!!

    @Test
    fun ageWeightHalvesEveryTwoYears() {
        assertEquals(1f, KnnModel.ageWeight(y2020, y2020), 1e-6f)
        assertEquals(0.5f, KnnModel.ageWeight(y2020, y2020 + 2 * year), 0.01f)
        assertEquals(KnnModel.AGE_MIN_WEIGHT, KnnModel.ageWeight(y2020, y2020 + 10 * year), 1e-6f)
        // chýbajúci dátum = váha 1
        assertEquals(1f, KnnModel.ageWeight(0L, y2020), 0f)
        assertEquals(1f, KnnModel.ageWeight(y2020, KnnModel.UNKNOWN_DATE), 0f)
    }

    @Test
    fun childIsComparedMostlyWithPhotosOfSimilarAge() {
        // prototypy: jeden z roku 2020 (kosínus 0,9 ku kandidátovi), dva z roku 2026 (0,6)
        val faces = listOf(face(mix(0.9f, 0, 1), y2020), face(mix(0.6f, 0, 2), y2026), face(mix(0.6f, 0, 3), y2026))
        val m = model(person(1, faces))
        val r = m.newRanker()
        val idx = m.indexOf(1)
        val noDate = r.score(e(0), idx)
        val from2020 = r.score(e(0), idx, takenAt = y2020)
        val from2026 = r.score(e(0), idx, takenAt = y2026)
        assertEquals(0.7f, noDate, 1e-4f)
        // 2020: (0,9 + 0,25·0,6 + 0,25·0,6) / 1,5 = 0,8
        assertEquals(0.8f, from2020, 1e-3f)
        assertTrue("2026 $from2026 < bez dátumu $noDate", from2026 < noDate)
        // rank používa tie isté váhy
        assertEquals(from2020, r.rank(e(0), takenAt = y2020)!!.best, 1e-5f)
    }

    @Test
    fun allPrototypesFarInTimeChangeNothing() {
        val faces = listOf(face(mix(0.9f, 0, 1), y2020), face(mix(0.6f, 0, 2), y2020), face(mix(0.6f, 0, 3), y2020))
        val m = model(person(1, faces))
        val r = m.newRanker()
        // všetky prototypy rovnako ďaleko → relatívne váhy 1 → rovnaké skóre ako bez dátumu
        assertEquals(r.score(e(0), 0), r.score(e(0), 0, takenAt = y2020 + 9 * year), 1e-5f)
    }

    @Test
    fun lowQualityFaceIsNotPrototype() {
        val good = face(mix(0.6f, 0, 2), quality = 0.9f)
        val blurry = face(e(0), quality = 0.1f)
        val m = model(person(1, listOf(good, blurry)))
        assertEquals(1, m.prototypeCount(0))
        assertEquals(0.6f, m.newRanker().score(e(0), 0), 1e-4f)
    }

    @Test
    fun personWithOnlyLowQualityFacesStaysInModel() {
        val m = model(person(1, listOf(face(e(0), quality = 0.1f), face(mix(0.8f, 0, 1), quality = 0.2f))))
        assertEquals(2, m.prototypeCount(0))
    }

    @Test
    fun lowQualityCandidateIsOnlyUncertain() {
        val protos = List(4) { face(mix(0.95f, 0, it + 1), quality = 0.9f) }
        val persons = person(1, protos)
        val sharp = face(e(0), quality = 0.8f)
        val blurry = face(e(0), quality = 0.05f).copy(mediaFullPath = "/p/other.jpg")
        val c = PersonGrouper.classify(persons, protos + sharp + blurry, emptyMap(), emptyList(), emptySet(), 0.8f)
        assertEquals(sharp.id, c.autoOwner.keys.single())
        val uncertain = c.uncertain[1L].orEmpty().map { it.face.id }
        assertTrue(blurry.id in uncertain)
        assertFalse(c.autoOwner.containsKey(blurry.id))
        assertNotNull(c.model)
    }
}
