package org.fossify.gallery.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

// Scenáre zaraďovania tvárí do osôb (balík C: C1–C5, A8, váha rodinných vzorov z D7).
class PersonGrouperTest {
    private val dim = 192
    private val rnd = Random(7)
    private var nextFaceId = 1L
    private val th = 0.72f

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

    private fun mix(a: FloatArray, wa: Float, b: FloatArray, wb: Float) =
        normalize(FloatArray(dim) { a[it] * wa + b[it] * wb })

    private fun face(vec: FloatArray?, path: String = "/p/${nextFaceId}.jpg", score: Float = 0.9f, width: Int = 80) =
        FaceEntity(
            id = nextFaceId++, mediaFullPath = path, mediaStoreId = 0, faceIndex = 0,
            bboxLeft = 0, bboxTop = 0, bboxRight = width, bboxBottom = width, score = score,
            embedding = vec?.let { FaceEmbedder.toBytes(it) }, detectedAt = 0,
        )

    private fun manual(f: FaceEntity, personId: Long) = FaceAssignmentEntity(f.id!!, personId, true, 0)

    private fun fromFamily(f: FaceEntity, personId: Long) = FaceAssignmentEntity(f.id!!, personId, false, 0)

    private fun vec(f: FaceEntity) = FaceEmbedder.toFloats(f.embedding!!)

    private fun cos(a: FloatArray, b: FloatArray) = PersonGrouper.cosine(a, b)

    private fun personOf(result: List<Person>, id: Long) = result.first { it.id == id }

    private fun ids(p: Person) = p.faces.mapNotNull { it.id }.toSet()

    private fun auto(
        persons: List<Person>,
        pool: List<FaceEntity>,
        anchors: Map<Long, PersonGrouper.Anchors> = emptyMap(),
        links: List<CannotLinkEntity> = emptyList(),
        ignored: Set<Long> = emptySet(),
    ) = PersonGrouper.withAutoMatches(persons, pool, anchors, links, ignored, th)

    private val jan = PersonEntity(1, "Ján")
    private val peter = PersonEntity(2, "Peter")

    // Ján (1) s tvárami [a] a Peter (2) s tvárami [b]
    private fun twoPersons(a: List<FaceEntity>, b: List<FaceEntity>, secondFromFamily: Boolean = false) =
        PersonGrouper.confirmedPersons(
            a + b, listOf(jan, peter),
            a.map { manual(it, 1) } + b.map { if (secondFromFamily) fromFamily(it, 2) else manual(it, 2) },
        )

    private fun janWith(vararg faces: FaceEntity) =
        PersonGrouper.confirmedPersons(faces.toList(), listOf(jan), faces.map { manual(it, 1) }).single()

    // osoba s n potvrdenými tvárami okolo [base]
    private fun confirmedFaces(base: FloatArray, n: Int, cos: Float = 0.9f) = List(n) { face(around(base, cos)) }

    // --- C3: súrodenci ---

    @Test
    fun siblingsFaceInTheMiddleStaysUnassigned() {
        val baseJ = gaussUnit()
        val baseP = around(baseJ, 0.8f)
        val jf = confirmedFaces(baseJ, 5)
        val pf = confirmedFaces(baseP, 5)
        val persons = twoPersons(jf, pf)
        val cJ = PersonGrouper.centroidOf(jf.map { vec(it) })!!
        val cP = PersonGrouper.centroidOf(pf.map { vec(it) })!!
        // tvár „medzi" súrodencami: obaja nad prahom, rozdiel pod rozostupom
        val mid = mix(cJ, 0.55f, cP, 0.45f)
        val u = face(mid)
        assertTrue(cos(mid, cJ) > th && cos(mid, cP) > th)
        assertTrue(cos(mid, cJ) - cos(mid, cP) < PersonGrouper.AUTO_MARGIN)
        val result = auto(persons, jf + pf + u)
        assertFalse(ids(personOf(result, 1)).contains(u.id))
        assertFalse(ids(personOf(result, 2)).contains(u.id))
    }

    @Test
    fun siblingsClearFaceIsAssigned() {
        val baseJ = gaussUnit()
        val baseP = around(baseJ, 0.8f)
        val jf = confirmedFaces(baseJ, 5)
        val pf = confirmedFaces(baseP, 5)
        val persons = twoPersons(jf, pf)
        val cJ = PersonGrouper.centroidOf(jf.map { vec(it) })!!
        val cP = PersonGrouper.centroidOf(pf.map { vec(it) })!!
        val clear = mix(cJ, 0.9f, cP, 0.1f)
        assertTrue(cos(clear, cJ) - cos(clear, cP) >= PersonGrouper.AUTO_MARGIN)
        val u = face(clear)
        val result = auto(persons, jf + pf + u)
        assertTrue(ids(personOf(result, 1)).contains(u.id))
        assertFalse(ids(personOf(result, 2)).contains(u.id))
    }

    @Test
    fun marginParameterControlsTheDecision() {
        val baseJ = gaussUnit()
        val baseP = around(baseJ, 0.8f)
        val jf = confirmedFaces(baseJ, 5)
        val pf = confirmedFaces(baseP, 5)
        val persons = twoPersons(jf, pf)
        val cJ = PersonGrouper.centroidOf(jf.map { vec(it) })!!
        val cP = PersonGrouper.centroidOf(pf.map { vec(it) })!!
        val u = face(mix(cJ, 0.55f, cP, 0.45f))
        val noMargin =
            PersonGrouper.withAutoMatches(persons, jf + pf + u, emptyMap(), emptyList(), emptySet(), th, margin = 0f)
        assertTrue(ids(personOf(noMargin, 1)).contains(u.id))
    }

    // --- C4: dve tváre na jednej fotke ---

    @Test
    fun twoFacesOnOnePhotoOnlyTheBetterOneIsAssigned() {
        val baseJ = gaussUnit()
        val jf = confirmedFaces(baseJ, 5)
        val persons = PersonGrouper.confirmedPersons(jf, listOf(jan), jf.map { manual(it, 1) })
        val cJ = PersonGrouper.centroidOf(jf.map { vec(it) })!!
        val a = face(around(cJ, 0.80f), path = "/p/group.jpg")
        val b = face(around(cJ, 0.88f), path = "/p/group.jpg")
        val elsewhere = face(around(cJ, 0.80f), path = "/p/other.jpg")
        val result = auto(persons, jf + a + b + elsewhere)
        val got = ids(personOf(result, 1))
        assertTrue(got.contains(b.id))
        assertFalse(got.contains(a.id))
        assertTrue(got.contains(elsewhere.id))
    }

    @Test
    fun manuallyConfirmedFaceBlocksSamePersonOnThePhoto() {
        val baseJ = gaussUnit()
        val jf = confirmedFaces(baseJ, 5)
        val onPhoto = face(around(baseJ, 0.9f), path = "/p/group.jpg")
        val persons = PersonGrouper.confirmedPersons(jf + onPhoto, listOf(jan), (jf + onPhoto).map { manual(it, 1) })
        val cJ = PersonGrouper.centroidOf((jf + onPhoto).map { vec(it) })!!
        val second = face(around(cJ, 0.95f), path = "/p/group.jpg")
        val result = auto(persons, jf + onPhoto + second)
        assertFalse(ids(personOf(result, 1)).contains(second.id))
    }

    @Test
    fun samePhotoConflictsForManualAssignment() {
        val existing = face(gaussUnit(), path = "/p/a.jpg")
        val other = face(gaussUnit(), path = "/p/b.jpg")
        val pickA = face(gaussUnit(), path = "/p/a.jpg")
        val pickC1 = face(gaussUnit(), path = "/p/c.jpg")
        val pickC2 = face(gaussUnit(), path = "/p/c.jpg")
        val pickD = face(gaussUnit(), path = "/p/d.jpg")
        val conflicts = PersonGrouper.samePhotoConflicts(listOf(pickA, pickC1, pickC2, pickD), listOf(existing, other))
        assertEquals(setOf("/p/a.jpg", "/p/c.jpg"), conflicts)
        // tá istá tvár znova (už priradená) nie je konflikt
        assertTrue(PersonGrouper.samePhotoConflicts(listOf(existing), listOf(existing, other)).isEmpty())
    }

    // --- malá osoba vedľa veľkej ---

    @Test
    fun smallPersonNextToBigPersonOnTheSamePhoto() {
        // otec má 30 potvrdených tvárí, dieťa len 3; na spoločnej fotke je otec potvrdený ručne
        val baseFather = gaussUnit()
        val baseChild = around(baseFather, 0.7f)
        val ff = confirmedFaces(baseFather, 30)
        val cf = confirmedFaces(baseChild, 3)
        val cF = PersonGrouper.centroidOf(ff.map { vec(it) })!!
        val cC = PersonGrouper.centroidOf(cf.map { vec(it) })!!
        val fatherOnPhoto = face(around(baseFather, 0.9f), path = "/p/together.jpg")
        // tvár dieťaťa, ktorá je o chlp bližšie otcovi
        val childVec = mix(cF, 0.52f, cC, 0.48f)
        val childOnPhoto = face(childVec, path = "/p/together.jpg")
        assertTrue(cos(childVec, cF) > cos(childVec, cC))
        assertTrue(cos(childVec, cC) > th)
        val persons = PersonGrouper.confirmedPersons(
            ff + cf + fatherOnPhoto, listOf(jan, peter),
            (ff + fatherOnPhoto).map { manual(it, 1) } + cf.map { manual(it, 2) },
        )
        val result = auto(persons, ff + cf + fatherOnPhoto + childOnPhoto)
        val father = personOf(result, 1)
        assertTrue(ids(father).contains(fatherOnPhoto.id))
        // otec je na fotke už ručne potvrdený → tvár ostane dieťaťu (jediný zvyšný kandidát)
        assertFalse(ids(father).contains(childOnPhoto.id))
        assertTrue(ids(personOf(result, 2)).contains(childOnPhoto.id))
    }

    @Test
    fun smallPersonAloneDoesNotGetFatherLookalike() {
        // bez ručne potvrdeného otca na fotke rozostup rozhodne: tvár „medzi" ostane nezaradená
        val baseFather = gaussUnit()
        val baseChild = around(baseFather, 0.7f)
        val ff = confirmedFaces(baseFather, 30)
        val cf = confirmedFaces(baseChild, 3)
        val cF = PersonGrouper.centroidOf(ff.map { vec(it) })!!
        val cC = PersonGrouper.centroidOf(cf.map { vec(it) })!!
        val u = face(mix(cF, 0.52f, cC, 0.48f))
        val persons = twoPersons(ff, cf)
        val result = auto(persons, ff + cf + u)
        assertFalse(ids(personOf(result, 1)).contains(u.id))
        assertFalse(ids(personOf(result, 2)).contains(u.id))
    }

    // --- C1: ignorovaná tvár ---

    @Test
    fun ignoredFaceIsNotAutoMatched() {
        val baseJ = gaussUnit()
        val jf = confirmedFaces(baseJ, 5)
        val persons = PersonGrouper.confirmedPersons(jf, listOf(jan), jf.map { manual(it, 1) })
        val ignored = face(around(baseJ, 0.9f))
        val normal = face(around(baseJ, 0.9f))
        val result = auto(persons, jf + ignored + normal, ignored = setOf(ignored.id!!))
        val got = ids(personOf(result, 1))
        assertFalse(got.contains(ignored.id))
        assertTrue(got.contains(normal.id))
    }

    @Test
    fun cannotLinkBlocksAutoMatch() {
        val baseJ = gaussUnit()
        val jf = confirmedFaces(baseJ, 5)
        val persons = PersonGrouper.confirmedPersons(jf, listOf(jan), jf.map { manual(it, 1) })
        val u = face(around(baseJ, 0.9f))
        val result = auto(persons, jf + u, links = listOf(CannotLinkEntity(u.id!!, 1)))
        assertFalse(ids(personOf(result, 1)).contains(u.id))
    }

    // --- C5: 3 vlastné potvrdené tváre + 20 rodinných vzorov ---

    @Test
    fun familyAnchorsHaveLimitedWeight() {
        val own = gaussUnit()
        val other = around(own, 0.35f)
        val jf = confirmedFaces(own, 3)
        val familyAnchors = List(20) { around(other, 0.9f) }
        val ownAnchors = emptyList<FloatArray>()
        val persons = PersonGrouper.confirmedPersons(jf, listOf(jan), jf.map { manual(it, 1) })
        val anchors = mapOf(1L to PersonGrouper.Anchors(ownAnchors, familyAnchors))
        val ev = PersonGrouper.evidenceOf(persons.single(), anchors[1L]) { FaceEmbedder.toFloats(it) }
        assertEquals(3, ev.own.size)
        assertEquals(20, ev.family.size)
        // váha 0,5 na vzor, ale spolu najviac 3 (= počet vlastných)
        val w = PersonGrouper.familyWeight(3, 20)
        assertEquals(0.15f, w, 1e-6f)
        assertTrue(w * 20 <= 3f + 1e-4f)
        assertEquals(PersonGrouper.FAMILY_WEIGHT, PersonGrouper.familyWeight(10, 4), 1e-6f)

        val ownMean = PersonGrouper.centroidOf(ev.own)!!
        val famMean = PersonGrouper.centroidOf(ev.family)!!
        val oldCentroid = PersonGrouper.centroidOf(ev.own + ev.family)!!
        val newCentroid = PersonGrouper.autoCentroid(ev)!!
        // predtým ťažisko ťahali rodinné vzory, teraz vlastné tváre nie sú prehlasované
        assertTrue(cos(oldCentroid, famMean) > cos(oldCentroid, ownMean))
        assertTrue(cos(newCentroid, ownMean) >= cos(newCentroid, famMean))
        assertTrue(cos(newCentroid, ownMean) > cos(oldCentroid, ownMean) + 0.1f)

        // tvár podobná vlastným tváram sa teraz zaradí (predtým nie), cudzej klesne skóre
        val likeOwn = face(around(ownMean, 0.92f))
        val likeOther = face(around(famMean, 0.92f))
        assertTrue(cos(vec(likeOwn), oldCentroid) < th)
        assertTrue(cos(vec(likeOther), newCentroid) < cos(vec(likeOther), oldCentroid) - 0.1f)
        val result = auto(persons, jf + likeOwn, anchors)
        assertTrue(ids(personOf(result, 1)).contains(likeOwn.id))
    }

    @Test
    fun familyVectorsDoNotFormOwnSubCentroids() {
        val own = gaussUnit()
        val other = around(own, 0.35f)
        val ev = PersonGrouper.Evidence(List(3) { around(own, 0.9f) }, List(20) { around(other, 0.9f) })
        val cents = PersonGrouper.suggestionCentroids(ev)
        assertEquals(1, cents.size)
        // pôvodne (všetko spolu) by rodinné vzory založili vlastné pod-ťažisko
        assertEquals(2, PersonGrouper.subCentroids(ev.own + ev.family).size)
        val likeOther = around(other, 0.95f)
        assertTrue(PersonGrouper.maxCosine(likeOther, cents) < 0.5f)
    }

    @Test
    fun familyVectorsJoinNearbyOwnSubCentroidWithReducedWeight() {
        val own = gaussUnit()
        val ownVecs = List(3) { around(own, 0.9f) }
        val famVecs = List(20) { around(own, 0.85f) }
        val cents = PersonGrouper.suggestionCentroids(PersonGrouper.Evidence(ownVecs, famVecs))
        assertEquals(1, cents.size)
        assertTrue(cos(cents[0], own) > 0.9f)
    }

    @Test
    fun familyOnlyPersonGetsSuggestionsButNoAutoMatches() {
        val base = gaussUnit()
        val ff = confirmedFaces(base, 4)
        val persons = PersonGrouper.confirmedPersons(ff, listOf(jan), ff.map { fromFamily(it, 1) })
        val p = persons.single()
        assertEquals(ids(p), p.familyFaceIds)
        val ev = PersonGrouper.evidenceOf(p, null) { FaceEmbedder.toFloats(it) }
        assertTrue(ev.own.isEmpty())
        assertNull(PersonGrouper.autoCentroid(ev))
        assertEquals(1, PersonGrouper.suggestionCentroids(ev).size)
        val u = face(around(base, 0.95f))
        assertFalse(ids(personOf(auto(persons, ff + u), 1)).contains(u.id))
    }

    @Test
    fun familyOnlyPersonStillCompetesInMargin() {
        // Peter je len z rodiny (nezaradí sa mu nič), ale tvár medzi Jánom a Petrom Jánovi nedá
        val baseJ = gaussUnit()
        val baseP = around(baseJ, 0.8f)
        val jf = confirmedFaces(baseJ, 5)
        val pf = confirmedFaces(baseP, 5)
        val persons = twoPersons(jf, pf, secondFromFamily = true)
        val cJ = PersonGrouper.centroidOf(jf.map { vec(it) })!!
        val cP = PersonGrouper.centroidOf(pf.map { vec(it) })!!
        val u = face(mix(cJ, 0.55f, cP, 0.45f))
        val result = auto(persons, jf + pf + u)
        assertFalse(ids(personOf(result, 1)).contains(u.id))
    }

    @Test
    fun autoMatchedFacesDoNotFeedTheCentroid() {
        val baseJ = gaussUnit()
        val jf = confirmedFaces(baseJ, 3)
        val persons = PersonGrouper.confirmedPersons(jf, listOf(jan), jf.map { manual(it, 1) })
        val extra = face(around(baseJ, 0.9f))
        val withAuto = auto(persons, jf + extra)
        val p = personOf(withAuto, 1)
        assertTrue(ids(p).contains(extra.id))
        val ev = PersonGrouper.evidenceOf(p, null) { FaceEmbedder.toFloats(it) }
        assertEquals(3, ev.own.size)
    }

    @Test
    fun splitAnchorsBySource() {
        val v = gaussUnit()
        val list = listOf(
            AnchorEmbeddingEntity(1, 1, FaceEmbedder.toBytes(v), "picasa", 0),
            AnchorEmbeddingEntity(2, 1, FaceEmbedder.toBytes(v), "family:abc", 0),
            AnchorEmbeddingEntity(3, 2, FaceEmbedder.toBytes(v), "family:xyz", 0),
        )
        val split = PersonGrouper.splitAnchors(list) { FaceEmbedder.toFloats(it) }
        assertEquals(1, split[1L]!!.own.size)
        assertEquals(1, split[1L]!!.family.size)
        assertEquals(0, split[2L]!!.own.size)
        assertEquals(1, split[2L]!!.family.size)
    }

    // --- potvrdené tváre sa nefiltrujú ---

    @Test
    fun confirmedPersonsKeepsEveryAssignedFace() {
        val f1 = face(gaussUnit())
        // malá tvár s nízkym skóre (FaceFilter.isGood by ju vyradil) — ručné potvrdenie má prednosť
        val f2 = face(gaussUnit(), score = 0.3f, width = 8)
        val persons = PersonGrouper.confirmedPersons(listOf(f1, f2), listOf(jan), listOf(manual(f1, 1), manual(f2, 1)))
        assertEquals(setOf(f1.id, f2.id), ids(persons.single()))
        assertEquals(setOf(f1.id!!, f2.id!!), persons.single().manualFaceIds)
        assertTrue(persons.single().familyFaceIds.isEmpty())
    }

    @Test
    fun subCentroidsSplitDistantGroups() {
        val a = gaussUnit()
        val b = around(a, 0.1f)
        val cents = PersonGrouper.subCentroids(List(4) { around(a, 0.9f) } + List(4) { around(b, 0.9f) })
        assertEquals(2, cents.size)
    }

    // --- A8: osoba so skrytými tvárami ---

    @Test
    fun personWithMissingPhotosStaysGrey() {
        val f1 = face(gaussUnit(), path = "/gone/1.jpg")
        val f2 = face(gaussUnit(), path = "/gone/2.jpg")
        val p = janWith(f1, f2)
        val shown = PersonGrouper.visiblePerson(p, isVisible = { false }, isLocked = { false })
        assertNotNull(shown)
        assertTrue(shown!!.photosMissing)
        assertEquals(0, shown.faceCount)
        assertEquals("Ján", shown.name)
    }

    @Test
    fun personWithLockedPhotosStaysHidden() {
        val f1 = face(gaussUnit(), path = "/gone/1.jpg")
        val f2 = face(gaussUnit(), path = "/locked/2.jpg")
        val p = janWith(f1, f2)
        assertNull(PersonGrouper.visiblePerson(p, isVisible = { false }, isLocked = { it.startsWith("/locked/") }))
    }

    @Test
    fun partlyVisiblePersonShowsVisibleFaces() {
        val f1 = face(gaussUnit(), path = "/ok/1.jpg")
        val f2 = face(gaussUnit(), path = "/gone/2.jpg")
        val p = PersonGrouper.confirmedPersons(listOf(f1, f2), listOf(jan), listOf(manual(f1, 1), fromFamily(f2, 1)))
            .single()
        val shown = PersonGrouper.visiblePerson(p, isVisible = { it.startsWith("/ok/") }, isLocked = { false })!!
        assertFalse(shown.photosMissing)
        assertEquals(setOf(f1.id), ids(shown))
        assertEquals(setOf(f1.id!!), shown.manualFaceIds)
        assertTrue(shown.familyFaceIds.isEmpty())
        // osoba bez tvárí (len vzory) sa nemení
        val empty = Person(5, "Vzory", emptyList())
        assertEquals(empty, PersonGrouper.visiblePerson(empty, { false }, { false }))
    }

    // --- výkon: loadPersons beží pri každom otvorení Ľudí ---

    @Test
    fun performance10kFaces100Persons() {
        val bases = List(100) { gaussUnit() }
        val persons = ArrayList<PersonEntity>()
        val faces = ArrayList<FaceEntity>()
        val assignments = ArrayList<FaceAssignmentEntity>()
        bases.forEachIndexed { i, b ->
            val pid = (i + 1).toLong()
            persons.add(PersonEntity(pid, "P$i"))
            repeat(10) {
                val f = face(around(b, 0.85f), path = "/c/${nextFaceId / 3}.jpg")
                faces.add(f)
                assignments.add(if (it < 8) manual(f, pid) else fromFamily(f, pid))
            }
        }
        // 9 000 nezaradených tvárí, po 3 na fotke
        repeat(9000) {
            val b = bases[rnd.nextInt(bases.size)]
            faces.add(face(around(b, 0.6f + rnd.nextFloat() * 0.35f), path = "/u/${it / 3}.jpg"))
        }
        val anchors = (1L..100L).associateWith {
            PersonGrouper.Anchors(List(5) { gaussUnit() }, List(5) { gaussUnit() })
        }
        val links = faces.take(500).map { CannotLinkEntity(it.id!!, 1) }
        val ignored = faces.takeLast(300).mapNotNull { it.id }.toSet()
        var best = Long.MAX_VALUE
        var assignedCount = 0
        repeat(4) {
            val t0 = System.nanoTime()
            val confirmed = PersonGrouper.confirmedPersons(faces, persons, assignments)
            val result = PersonGrouper.withAutoMatches(confirmed, faces, anchors, links, ignored, th)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms < best) best = ms
            assignedCount = result.sumOf { it.faceCount }
        }
        println("PersonGrouper 10 000 tvárí × 100 osôb: najlepší beh $best ms, tvárí v osobách $assignedCount")
        assertTrue(assignedCount > 1000)
        assertTrue("trvalo $best ms", best < 300)
    }
}
