package org.fossify.gallery.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

// C6 (k-NN), C7 (tri stavy), C8 (negatívne prototypy) a C11 (fronta „Pomôž mi rozhodnúť“).
class KnnModelTest {
    private val dim = 192
    private val rnd = Random(11)
    private var nextFaceId = 1L

    private fun gaussUnit(): FloatArray = normalize(FloatArray(dim) { rnd.nextGaussian().toFloat() })

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s)
        return FloatArray(v.size) { v[it] / n }
    }

    // jednotkový vektor s presným kosínusom [cos] k jednotkovému [base]
    private fun around(base: FloatArray, cos: Float): FloatArray {
        val r = gaussUnit()
        var dot = 0f
        for (i in r.indices) dot += r[i] * base[i]
        val orth = normalize(FloatArray(dim) { r[it] - dot * base[it] })
        val sin = sqrt(1f - cos * cos)
        return FloatArray(dim) { cos * base[it] + sin * orth[it] }
    }

    private fun face(vec: FloatArray, path: String = "/p/${nextFaceId}.jpg") = FaceEntity(
        id = nextFaceId++, mediaFullPath = path, mediaStoreId = 0, faceIndex = 0,
        bboxLeft = 0, bboxTop = 0, bboxRight = 80, bboxBottom = 80, score = 0.9f,
        embedding = FaceEmbedder.toBytes(vec), detectedAt = 0,
    )

    private fun vec(f: FaceEntity) = FaceEmbedder.toFloats(f.embedding!!)

    private fun manual(f: FaceEntity, personId: Long) = FaceAssignmentEntity(f.id!!, personId, true, 0)

    private val jan = PersonEntity(1, "Ján")
    private val peter = PersonEntity(2, "Peter")

    private fun persons(byPerson: Map<PersonEntity, List<FaceEntity>>) = PersonGrouper.confirmedPersons(
        byPerson.values.flatten(), byPerson.keys.toList(),
        byPerson.flatMap { (p, fs) -> fs.map { manual(it, p.id) } },
    )

    private fun model(
        persons: List<Person>,
        links: List<CannotLinkEntity> = emptyList(),
        extra: List<FaceEntity> = emptyList(),
    ) =
        PersonGrouper.buildKnn(
            persons, emptyMap(), links,
            (persons.flatMap { it.faces } + extra).associateBy { it.id!! },
        )!!

    private fun scoreOf(m: KnnModel, personId: Long, v: FloatArray) = m.newRanker().score(v, m.indexOf(personId))

    private fun ids(p: Person) = p.faces.mapNotNull { it.id }.toSet()

    // --- C6: dieťa v rôznom veku = viac zhlukov ---

    @Test
    fun childAtDifferentAgesMatchesNearestCluster() {
        val baby = gaussUnit()
        val school = around(baby, 0.3f)
        val teen = around(baby, 0.2f)
        val faces = List(5) { face(around(baby, 0.9f)) } + List(5) { face(around(school, 0.9f)) } +
            List(5) { face(around(teen, 0.9f)) }
        val ps = persons(mapOf(jan to faces))
        val m = model(ps)
        val newTeen = around(teen, 0.95f)
        val knn = scoreOf(m, 1, newTeen)
        val centroid = PersonGrouper.cosine(newTeen, PersonGrouper.centroidOf(faces.map { vec(it) })!!)
        // jeden priemer leží „medzi“ vekmi, kNN porovná s tínedžerskými fotkami
        assertTrue("knn $knn centroid $centroid", knn > centroid + 0.2f)
        val th = (knn + centroid) / 2
        val u = face(newTeen)
        val result = PersonGrouper.withAutoMatches(ps, faces + u, emptyMap(), emptyList(), emptySet(), th)
        assertTrue(ids(result.single()).contains(u.id))
        // Peter leží „v strede“ medzi vekmi dieťaťa: jeho ťažisko je tvári bližšie než ťažisko dieťaťa,
        // rozhodne však kNN (dieťa ostane v predvýbere a vyhrá)
        val middle = PersonGrouper.centroidOf(faces.map { vec(it) })!!
        val peterBase = normalize(FloatArray(dim) { middle[it] * 0.8f + teen[it] * 0.2f })
        val pf = List(5) { face(peterBase) }
        val both = persons(mapOf(jan to faces, peter to pf))
        val m2 = model(both)
        val peterCentroid = PersonGrouper.cosine(newTeen, PersonGrouper.centroidOf(pf.map { vec(it) })!!)
        assertTrue(peterCentroid > centroid)
        val r = m2.newRanker().rank(newTeen)!!
        assertEquals(1L, r.bestId)
        assertTrue(r.margin >= PersonGrouper.AUTO_MARGIN)
    }

    // --- C6: okuliare (malá podskupina vedľa veľkej) ---

    @Test
    fun glassesSubgroupIsRecognised() {
        val plain = gaussUnit()
        val glasses = around(plain, 0.5f)
        val plainFaces = List(12) { face(around(plain, 0.9f)) }
        val glassFaces = List(3) { face(around(glasses, 0.9f)) }
        val m = model(persons(mapOf(jan to plainFaces + glassFaces)))
        val withGlasses = around(glasses, 0.95f)
        val knn = scoreOf(m, 1, withGlasses)
        val centroid = PersonGrouper.cosine(
            withGlasses, PersonGrouper.centroidOf((plainFaces + glassFaces).map { vec(it) })!!,
        )
        assertTrue("knn $knn centroid $centroid", knn > 0.8f)
        assertTrue(knn > centroid + 0.15f)
    }

    @Test
    fun scoreIsMeanOfTopK() {
        val base = gaussUnit()
        val fs = List(6) { face(around(base, 0.8f)) }
        val m = model(persons(mapOf(jan to fs)))
        val v = around(base, 0.9f)
        val sims = fs.map { PersonGrouper.cosine(v, vec(it)) }.sortedDescending()
        assertEquals(sims.take(3).average().toFloat(), scoreOf(m, 1, v), 1e-4f)
    }

    @Test
    fun personWithFewerThanKPrototypesUsesWhatItHas() {
        val base = gaussUnit()
        val fs = List(2) { face(around(base, 0.85f)) }
        val m = model(persons(mapOf(jan to fs)))
        val v = around(base, 0.9f)
        val mean = fs.map { PersonGrouper.cosine(v, vec(it)) }.average().toFloat()
        assertEquals(mean, scoreOf(m, 1, v), 1e-4f)
        assertEquals(2, m.prototypeCount(0))
    }

    @Test
    fun singlePrototypeNeedsStricterThreshold() {
        val base = gaussUnit()
        val only = face(base)
        val ps = persons(mapOf(jan to listOf(only)))
        val m = model(ps)
        assertEquals(0.72f + KnnModel.SINGLE_PROTOTYPE_EXTRA, m.autoThreshold(0, 0.72f), 1e-6f)
        // kosínus 0,74: nad bežným prahom, ale pod prísnejším → len „neisté“
        val u = face(around(base, 0.74f))
        val c = PersonGrouper.classify(ps, listOf(only, u), emptyMap(), emptyList(), emptySet(), 0.72f)
        assertFalse(ids(c.persons.single()).contains(u.id))
        assertEquals(listOf(u.id), c.uncertain[1L]?.map { it.face.id })
        // tá istá podobnosť pri troch prototypoch už stačí
        val three = List(3) { face(base) }
        val ps3 = persons(mapOf(jan to three))
        val u3 = face(around(base, 0.74f))
        val c3 = PersonGrouper.classify(ps3, three + u3, emptyMap(), emptyList(), emptySet(), 0.72f)
        assertTrue(ids(c3.persons.single()).contains(u3.id))
    }

    @Test
    fun picasaAnchorsAreCappedAt50() {
        val base = gaussUnit()
        val anchors = List(120) { around(base, 0.8f) }
        val p = Person(1, "Ján", emptyList())
        val byPerson = mapOf(1L to PersonGrouper.Anchors(anchors, emptyList()))
        val m = PersonGrouper.buildKnn(listOf(p), byPerson, emptyList(), emptyMap())!!
        assertEquals(KnnModel.MAX_ANCHORS_PER_PERSON, m.prototypeCount(0))
    }

    @Test
    fun manualPrototypesAreCapped() {
        val base = gaussUnit()
        val fs = List(260) { face(around(base, 0.8f)) }
        val m = model(persons(mapOf(jan to fs)))
        assertEquals(KnnModel.MAX_MANUAL, m.prototypeCount(0))
    }

    // --- C8: negatívne prototypy ---

    @Test
    fun rejectedFacePenalisesLookalikes() {
        val base = gaussUnit()
        val jf = List(5) { face(around(base, 0.9f)) }
        val ps = persons(mapOf(jan to jf))
        val stranger = around(base, 0.8f)
        val rejected = face(around(stranger, 0.97f))
        val u = face(around(stranger, 0.97f))
        val links = listOf(CannotLinkEntity(rejected.id!!, 1))
        val withNeg = model(ps, links, listOf(rejected))
        val before = scoreOf(model(ps), 1, vec(u))
        val after = scoreOf(withNeg, 1, vec(u))
        val th = before - 0.01f
        val plain = PersonGrouper.classify(ps, jf + u, emptyMap(), emptyList(), emptySet(), th)
        assertTrue("bez odmietnutia sa zaradí", ids(plain.persons.single()).contains(u.id))
        assertTrue(after < th)
        val maxPos = jf.maxOf { PersonGrouper.cosine(vec(u), vec(it)) }
        val expected = KnnModel.NEGATIVE_LAMBDA * maxOf(0f, PersonGrouper.cosine(vec(u), vec(rejected)) - maxPos)
        assertEquals(before - expected, after, 1e-4f)
        val negatives = mapOf(rejected.id!! to rejected)
        val c = PersonGrouper.classify(ps, jf + u, emptyMap(), links, emptySet(), th, negativeFaces = negatives)
        assertFalse(ids(c.persons.single()).contains(u.id))
    }

    @Test
    fun farRejectedFaceDoesNotChangeScore() {
        val base = gaussUnit()
        val jf = List(5) { face(around(base, 0.9f)) }
        val ps = persons(mapOf(jan to jf))
        val far = face(gaussUnit())
        val v = around(base, 0.9f)
        val withNeg = model(ps, listOf(CannotLinkEntity(far.id!!, 1)), listOf(far))
        assertEquals(1, withNeg.negativeCount(0))
        assertEquals(scoreOf(model(ps), 1, v), scoreOf(withNeg, 1, v), 1e-6f)
    }

    // --- C7: tri stavy ---

    @Test
    fun threeStates() {
        val base = gaussUnit()
        val jf = List(5) { face(around(base, 0.9f)) }
        val ps = persons(mapOf(jan to jf))
        val m = model(ps)
        val sure = face(around(base, 0.97f))
        val maybe = face(around(base, 0.82f))
        val no = face(around(base, 0.4f))
        val th = 0.8f
        val sSure = scoreOf(m, 1, vec(sure))
        val sMaybe = scoreOf(m, 1, vec(maybe))
        val sNo = scoreOf(m, 1, vec(no))
        assertTrue(sSure >= th)
        assertTrue(sMaybe < th && sMaybe >= th - PersonGrouper.UNCERTAIN_BAND)
        assertTrue(sNo < th - PersonGrouper.UNCERTAIN_BAND)
        val c = PersonGrouper.classify(ps, jf + sure + maybe + no, emptyMap(), emptyList(), emptySet(), th)
        assertEquals(1L, c.autoOwner[sure.id])
        assertTrue(ids(c.persons.single()).contains(sure.id))
        assertEquals(listOf(maybe.id), c.uncertain[1L]?.map { it.face.id })
        assertNull(c.autoOwner[maybe.id])
        assertNull(c.autoOwner[no.id])
        assertFalse(c.uncertain.values.flatten().any { it.face.id == no.id })
    }

    @Test
    fun aboveThresholdWithSmallMarginIsUncertain() {
        val baseJ = gaussUnit()
        val baseP = around(baseJ, 0.8f)
        val jf = List(5) { face(around(baseJ, 0.95f)) }
        val pf = List(5) { face(around(baseP, 0.95f)) }
        val ps = persons(mapOf(jan to jf, peter to pf))
        val mid = face(normalize(FloatArray(dim) { baseJ[it] * 0.52f + baseP[it] * 0.48f }))
        val c = PersonGrouper.classify(ps, jf + pf + mid, emptyMap(), emptyList(), emptySet(), 0.7f)
        assertNull(c.autoOwner[mid.id])
        val unsure = c.uncertain.values.flatten().single { it.face.id == mid.id }
        assertTrue(unsure.score >= 0.7f)
        assertTrue(unsure.margin < PersonGrouper.AUTO_MARGIN)
        assertTrue(c.contested.any { it.face.id == mid.id })
    }

    @Test
    fun uncertainOnlyForPersonNotYetOnPhoto() {
        val base = gaussUnit()
        val onPhoto = face(around(base, 0.9f), path = "/p/group.jpg")
        val jf = List(4) { face(around(base, 0.9f)) } + onPhoto
        val ps = persons(mapOf(jan to jf))
        val maybe = face(around(base, 0.82f), path = "/p/group.jpg")
        val c = PersonGrouper.classify(ps, jf + maybe, emptyMap(), emptyList(), emptySet(), 0.8f)
        assertTrue(c.uncertain[1L].isNullOrEmpty())
    }

    @Test
    fun autoOffStillOffersUncertain() {
        val base = gaussUnit()
        val jf = List(5) { face(around(base, 0.9f)) }
        val ps = persons(mapOf(jan to jf))
        val u = face(around(base, 0.97f))
        val c = PersonGrouper.classify(ps, jf + u, emptyMap(), emptyList(), emptySet(), 0f)
        assertTrue(c.autoOwner.isEmpty())
        assertEquals(listOf(u.id), c.uncertain[1L]?.map { it.face.id })
        // withAutoMatches pri vypnutej automatike nič nepridá
        assertEquals(ps, PersonGrouper.withAutoMatches(ps, jf + u, emptyMap(), emptyList(), emptySet(), 0f))
    }

    // --- C11: poradie fronty ---

    @Test
    fun decisionQueueOrdersBySmallestMargin() {
        fun s(score: Float, second: Float, p: Long = 1, q: Long = 2) =
            PersonGrouper.Scored(face(gaussUnit()), p, score, q, second)
        // hodnoty presne zobraziteľné vo float (rovnaký rozostup c a d)
        val a = s(0.875f, 0.625f)
        val b = s(0.5f, 0.375f)
        val c = s(0.75f, 0.6875f)
        val d = s(0.625f, 0.5625f, p = 3, q = 1)
        val single = PersonGrouper.Scored(face(gaussUnit()), 1, 0.8f, KnnModel.NO_PERSON, Float.NEGATIVE_INFINITY)
        val q = PersonGrouper.decisionQueue(listOf(a, b, c, d, single))
        // rovnaký rozostup → vyššie skóre skôr
        assertEquals(listOf(c, d, b, a), q)
        assertEquals(listOf(c, b, a), PersonGrouper.decisionQueue(listOf(a, b, c, d), pair = 2L to 1L))
        assertEquals(listOf(b, a), PersonGrouper.decisionQueue(listOf(a, b, c), skipped = setOf(c.face.id!!)))
        assertEquals(listOf(c), PersonGrouper.decisionQueue(listOf(a, b, c), limit = 1))
    }

    @Test
    fun decisionQueueAfterAnswerRecomputes() {
        val baseJ = gaussUnit()
        val baseP = around(baseJ, 0.8f)
        val jf = List(5) { face(around(baseJ, 0.95f)) }
        val pf = List(5) { face(around(baseP, 0.95f)) }
        val mids = List(3) { k ->
            face(normalize(FloatArray(dim) { baseJ[it] * (0.5f + k * 0.01f) + baseP[it] * 0.5f }))
        }
        var ps = persons(mapOf(jan to jf, peter to pf))
        val q1 = PersonGrouper.decisionQueue(
            PersonGrouper.classify(ps, jf + pf + mids, emptyMap(), emptyList(), emptySet(), 0.7f).contested,
        )
        assertEquals(3, q1.size)
        assertNotNull(q1.first())
        // odpoveď „Ján“ na prvú otázku: priradenie + cannot-link na Petra → otázka zmizne z fronty
        val answered = q1.first().face
        ps = persons(mapOf(jan to jf + answered, peter to pf))
        val q2 = PersonGrouper.decisionQueue(
            PersonGrouper.classify(
                ps, jf + pf + mids, emptyMap(), listOf(CannotLinkEntity(answered.id!!, 2)), emptySet(), 0.7f,
            ).contested,
        )
        assertFalse(q2.any { it.face.id == answered.id })
    }

    // výkon pri veľkých osobách: 100 osôb × (60 potvrdených + 50 vzorov), 10 000 nezaradených tvárí
    @Test
    fun performanceWithManyPrototypes() {
        val bases = List(100) { gaussUnit() }
        val entities = bases.indices.map { PersonEntity((it + 1).toLong(), "P$it") }
        val own = bases.map { b -> List(60) { face(around(b, 0.85f)) } }
        val ps = PersonGrouper.confirmedPersons(
            own.flatten(), entities,
            own.flatMapIndexed { i, fs -> fs.map { manual(it, (i + 1).toLong()) } },
        )
        val anchors = bases.indices.associate { i ->
            (i + 1).toLong() to PersonGrouper.Anchors(List(50) { around(bases[i], 0.8f) }, emptyList())
        }
        val pool = own.flatten() + List(10_000) {
            face(around(bases[rnd.nextInt(100)], 0.5f + rnd.nextFloat() * 0.4f), "/u/${it / 3}.jpg")
        }
        var best = Long.MAX_VALUE
        repeat(3) {
            val t0 = System.nanoTime()
            PersonGrouper.classify(ps, pool, anchors, emptyList(), emptySet(), 0.72f)
            best = minOf(best, (System.nanoTime() - t0) / 1_000_000)
        }
        println("kNN 10 000 tvárí × 100 osôb × 110 prototypov: najlepší beh $best ms")
        assertTrue("trvalo $best ms", best < 1000)
    }
}
