package org.fossify.gallery.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

// C10 — leave-one-out na syntetických dátach.
class AccuracyEstimateTest {
    private val dim = 192
    private val rnd = Random(5)
    private var nextFaceId = 1L

    private fun gaussUnit(): FloatArray = normalize(FloatArray(dim) { rnd.nextGaussian().toFloat() })

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s)
        return FloatArray(v.size) { v[it] / n }
    }

    private fun around(base: FloatArray, cos: Float): FloatArray {
        val r = gaussUnit()
        var dot = 0f
        for (i in r.indices) dot += r[i] * base[i]
        val orth = normalize(FloatArray(dim) { r[it] - dot * base[it] })
        val sin = sqrt(1f - cos * cos)
        return FloatArray(dim) { cos * base[it] + sin * orth[it] }
    }

    private fun face(vec: FloatArray) = FaceEntity(
        id = nextFaceId++, mediaFullPath = "/p/${nextFaceId}.jpg", mediaStoreId = 0, faceIndex = 0,
        bboxLeft = 0, bboxTop = 0, bboxRight = 80, bboxBottom = 80, score = 0.9f,
        embedding = FaceEmbedder.toBytes(vec), detectedAt = 0,
    )

    // osoby s tvárami okolo daných základov → (osoby, vzorky na LOO)
    private fun setup(
        bases: List<FloatArray>,
        perPerson: Int,
        cos: Float,
    ): Pair<List<Person>, List<AccuracyEstimate.Sample>> {
        val entities = bases.indices.map { PersonEntity((it + 1).toLong(), "P$it") }
        val faces = bases.map { b -> List(perPerson) { face(around(b, cos)) } }
        val persons = PersonGrouper.confirmedPersons(
            faces.flatten(), entities,
            faces.flatMapIndexed { i, fs -> fs.map { FaceAssignmentEntity(it.id!!, (i + 1).toLong(), true, 0) } },
        )
        val samples = faces.flatMapIndexed { i, fs ->
            fs.map { AccuracyEstimate.Sample(it.id!!, (i + 1).toLong(), FaceEmbedder.toFloats(it.embedding!!)) }
        }
        return persons to samples
    }

    private fun model(persons: List<Person>) =
        PersonGrouper.buildKnn(persons, emptyMap(), emptyList(), persons.flatMap { it.faces }.associateBy { it.id!! })!!

    @Test
    fun separatedPersonsArePerfect() {
        val (persons, samples) = setup(List(4) { gaussUnit() }, 12, 0.9f)
        val r = AccuracyEstimate.run(model(persons), samples, 0.7f)
        assertEquals(48, r.evaluated)
        assertTrue(r.autoCount > 40)
        assertEquals(1f, r.precision!!, 1e-6f)
        assertNull(r.worstPair())
        assertNotNull(r.recommended)
    }

    @Test
    fun leaveOneOutDoesNotSeeTheFaceItself() {
        // každá tvár je od ostatných ďaleko (cos 0,5 k základu → medzi sebou ~0,25): s vlastnou tvárou
        // medzi prototypmi by skóre bolo ~0,5 (1 + 0,25 + 0,25)/3, bez nej ~0,25 → nič automaticky
        val (persons, samples) = setup(listOf(gaussUnit()), 10, 0.5f)
        val r = AccuracyEstimate.run(model(persons), samples, 0.45f)
        assertEquals(0, r.autoCount)
    }

    @Test
    fun siblingsAreReportedAsConfused() {
        val baseJ = gaussUnit()
        val baseP = around(baseJ, 0.93f)
        val others = List(3) { gaussUnit() }
        val (persons, samples) = setup(listOf(baseJ, baseP) + others, 10, 0.85f)
        val r = AccuracyEstimate.run(model(persons), samples, 0.6f)
        val worst = r.worstPair()!!
        assertEquals(setOf(1L, 2L), setOf(worst.personId, worst.otherId))
        assertTrue(r.uncertainCount > 0)
        // ostatní sa nepletú s nikým
        assertTrue(r.confusions.none { it.personId > 2 })
    }

    @Test
    fun recommendedThresholdReachesTargetPrecision() {
        val baseJ = gaussUnit()
        val baseP = around(baseJ, 0.7f)
        val (persons, samples) = setup(listOf(baseJ, baseP) + List(4) { gaussUnit() }, 15, 0.8f)
        val m = model(persons)
        val r = AccuracyEstimate.run(m, samples, 0.5f)
        val rec = r.recommended ?: return
        val atRec = AccuracyEstimate.run(m, samples, rec)
        assertTrue(atRec.precision!! >= AccuracyEstimate.TARGET_PRECISION)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val r = AccuracyEstimate.Result(
            1234, 900, 870, 120, 0.72f, 0.76f,
            listOf(AccuracyEstimate.Confusion(1, 2, 7, 40), AccuracyEstimate.Confusion(2, 1, 5, 30)), 99L,
        )
        assertEquals(r, AccuracyEstimate.decode(AccuracyEstimate.encode(r)))
        val noRec = r.copy(recommended = null, confusions = emptyList())
        assertEquals(noRec, AccuracyEstimate.decode(AccuracyEstimate.encode(noRec)))
        assertNull(AccuracyEstimate.decode("2;x"))
        assertNull(AccuracyEstimate.decode("1;a;b;c;d;e;f;g;"))
        assertNull(AccuracyEstimate.decode(null))
        assertEquals(0.9667f, r.precision!!, 1e-3f)
    }

    @Test
    fun sampleIsCappedAndDeterministic() {
        val all = List(5000) { AccuracyEstimate.Sample(it.toLong(), (it % 7).toLong(), FloatArray(0)) }
        val s1 = AccuracyEstimate.sample(all, 2000)
        assertEquals(2000, s1.size)
        assertEquals(s1.map { it.faceId }, AccuracyEstimate.sample(all, 2000).map { it.faceId })
        assertEquals(7, s1.map { it.personId }.distinct().size)
    }
}
