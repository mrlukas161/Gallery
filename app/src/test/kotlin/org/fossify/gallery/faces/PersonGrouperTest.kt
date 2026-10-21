package org.fossify.gallery.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

// Scenáre zaraďovania tvárí do osôb (súčasné správanie pred balíkom C).
class PersonGrouperTest {
    private val dim = 192
    private val rnd = Random(7)
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

    private fun face(vec: FloatArray?, path: String = "/p/${nextFaceId}.jpg", score: Float = 0.9f, width: Int = 80) =
        FaceEntity(
            id = nextFaceId++, mediaFullPath = path, mediaStoreId = 0, faceIndex = 0,
            bboxLeft = 0, bboxTop = 0, bboxRight = width, bboxBottom = width, score = score,
            embedding = vec?.let { FaceEmbedder.toBytes(it) }, detectedAt = 0,
        )

    private fun manual(f: FaceEntity, personId: Long) = FaceAssignmentEntity(f.id!!, personId, true, 0)

    private fun cos(a: FloatArray, b: FloatArray) = PersonGrouper.cosine(a, b)

    private fun personOf(result: List<Person>, id: Long) = result.first { it.id == id }

    private fun ids(p: Person) = p.faces.mapNotNull { it.id }.toSet()

    private val jan = PersonEntity(1, "Ján")
    private val peter = PersonEntity(2, "Peter")

    // osoba s n potvrdenými tvárami okolo [base]
    private fun confirmedFaces(base: FloatArray, n: Int, cos: Float = 0.9f) = List(n) { face(around(base, cos)) }

    @Test
    fun siblingsCloseToEachOtherGoToTheSlightlyBetterOne() {
        val baseJ = gaussUnit()
        val baseP = around(baseJ, 0.8f)
        val jf = confirmedFaces(baseJ, 5)
        val pf = confirmedFaces(baseP, 5)
        val persons = PersonGrouper.confirmedPersons(jf + pf, listOf(jan, peter), jf.map { manual(it, 1) } + pf.map { manual(it, 2) })
        val cJ = PersonGrouper.centroidOf(jf.map { FaceEmbedder.toFloats(it.embedding!!) })!!
        val cP = PersonGrouper.centroidOf(pf.map { FaceEmbedder.toFloats(it.embedding!!) })!!
        // tvár „medzi" súrodencami
        val mid = normalize(FloatArray(dim) { cJ[it] * 0.55f + cP[it] * 0.45f })
        val u = face(mid)
        assertTrue(cos(mid, cJ) > 0.72f && cos(mid, cP) > 0.72f)
        assertTrue(cos(mid, cJ) - cos(mid, cP) < 0.06f)
        val result = PersonGrouper.withAutoMatches(persons, jf + pf + u, emptyMap(), emptyList(), 0.72f)
        // DNES: zaradí sa k Jánovi napriek tomu, že Peter je takmer rovnako blízko
        assertTrue(ids(personOf(result, 1)).contains(u.id))
        assertFalse(ids(personOf(result, 2)).contains(u.id))
    }

    @Test
    fun twoFacesOnOnePhotoBothGoToTheSamePerson() {
        val baseJ = gaussUnit()
        val jf = confirmedFaces(baseJ, 5)
        val persons = PersonGrouper.confirmedPersons(jf, listOf(jan), jf.map { manual(it, 1) })
        val a = face(around(baseJ, 0.88f), path = "/p/group.jpg")
        val b = face(around(baseJ, 0.84f), path = "/p/group.jpg")
        val result = PersonGrouper.withAutoMatches(persons, jf + a + b, emptyMap(), emptyList(), 0.72f)
        // DNES: Ján je na jednej fotke dvakrát
        assertTrue(ids(personOf(result, 1)).containsAll(listOf(a.id, b.id)))
    }

    @Test
    fun smallPersonNextToBigPersonOnTheSamePhoto() {
        // otec má veľa potvrdených tvárí, dieťa len 3; na spoločnej fotke je otec potvrdený ručne
        val baseFather = gaussUnit()
        val baseChild = around(baseFather, 0.7f)
        val ff = confirmedFaces(baseFather, 30)
        val cf = confirmedFaces(baseChild, 3)
        val cF = PersonGrouper.centroidOf(ff.map { FaceEmbedder.toFloats(it.embedding!!) })!!
        val cC = PersonGrouper.centroidOf(cf.map { FaceEmbedder.toFloats(it.embedding!!) })!!
        val fatherOnPhoto = face(around(baseFather, 0.9f), path = "/p/together.jpg")
        // tvár dieťaťa, ktorá je o chlp bližšie otcovi
        val childVec = normalize(FloatArray(dim) { cF[it] * 0.52f + cC[it] * 0.48f })
        val childOnPhoto = face(childVec, path = "/p/together.jpg")
        assertTrue(cos(childVec, cF) > cos(childVec, cC))
        assertTrue(cos(childVec, cC) > 0.72f)
        val persons = PersonGrouper.confirmedPersons(
            ff + cf + fatherOnPhoto, listOf(jan, peter),
            (ff + fatherOnPhoto).map { manual(it, 1) } + cf.map { manual(it, 2) },
        )
        val result = PersonGrouper.withAutoMatches(persons, ff + cf + fatherOnPhoto + childOnPhoto, emptyMap(), emptyList(), 0.72f)
        val father = personOf(result, 1)
        // ručne potvrdený otec ostáva
        assertTrue(ids(father).contains(fatherOnPhoto.id))
        // DNES: tvár dieťaťa dostane otec, ktorý je na fotke už ručne potvrdený
        assertTrue(ids(father).contains(childOnPhoto.id))
        assertFalse(ids(personOf(result, 2)).contains(childOnPhoto.id))
    }

    @Test
    fun ignoredFaceIsStillAutoMatchedToday() {
        val baseJ = gaussUnit()
        val jf = confirmedFaces(baseJ, 5)
        val persons = PersonGrouper.confirmedPersons(jf, listOf(jan), jf.map { manual(it, 1) })
        val ignored = face(around(baseJ, 0.9f))
        val result = PersonGrouper.withAutoMatches(persons, jf + ignored, emptyMap(), emptyList(), 0.72f)
        // DNES: withAutoMatches extras.db nepozná, ignorovaná tvár sa zaradí
        assertTrue(ids(personOf(result, 1)).contains(ignored.id))
    }

    @Test
    fun cannotLinkBlocksAutoMatch() {
        val baseJ = gaussUnit()
        val jf = confirmedFaces(baseJ, 5)
        val persons = PersonGrouper.confirmedPersons(jf, listOf(jan), jf.map { manual(it, 1) })
        val u = face(around(baseJ, 0.9f))
        val result = PersonGrouper.withAutoMatches(persons, jf + u, emptyMap(), listOf(CannotLinkEntity(u.id!!, 1)), 0.72f)
        assertFalse(ids(personOf(result, 1)).contains(u.id))
    }

    @Test
    fun familyAnchorsOutweighOwnFacesToday() {
        // 3 vlastné potvrdené tváre + 20 rodinných vzorov, ktoré ukazujú trochu inam (chyba z druhého telefónu)
        val own = gaussUnit()
        val other = around(own, 0.35f)
        val jf = confirmedFaces(own, 3)
        val familyAnchors = List(20) { around(other, 0.9f) }
        val persons = PersonGrouper.confirmedPersons(jf, listOf(jan), jf.map { manual(it, 1) })
        val likeOwn = face(around(own, 0.85f))
        val likeOther = face(around(other, 0.9f))
        val result = PersonGrouper.withAutoMatches(
            persons, jf + likeOwn + likeOther, mapOf(1L to familyAnchors), emptyList(), 0.72f,
        )
        val got = ids(personOf(result, 1))
        // DNES: rodinné vzory majú plnú váhu — cudzia tvár sa zaradí, vlastná nie
        assertTrue(got.contains(likeOther.id))
        assertFalse(got.contains(likeOwn.id))
    }

    @Test
    fun confirmedPersonsKeepsEveryAssignedFace() {
        val f1 = face(gaussUnit())
        val f2 = face(gaussUnit(), score = 0.3f, width = 8)
        val persons = PersonGrouper.confirmedPersons(listOf(f1, f2), listOf(jan), listOf(manual(f1, 1), manual(f2, 1)))
        assertEquals(setOf(f1.id, f2.id), ids(persons.single()))
        assertEquals(setOf(f1.id!!, f2.id!!), persons.single().manualFaceIds)
    }

    @Test
    fun subCentroidsSplitDistantGroups() {
        val a = gaussUnit()
        val b = around(a, 0.1f)
        val cents = PersonGrouper.subCentroids(List(4) { around(a, 0.9f) } + List(4) { around(b, 0.9f) })
        assertEquals(2, cents.size)
    }
}
