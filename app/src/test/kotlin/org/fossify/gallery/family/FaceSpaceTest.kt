package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// D2 + D3: priestor rámčeka tváre na otočenej fotke — overenie odtlačkom, geometria, dátum ako posledná záloha.
class FaceSpaceTest {
    private val rot90 = RelBox.Transform(RelBox.DEG_90, false)
    private val none = RelBox.Transform(0, false)
    private val old = 0L
    private val fresh = BundleRules.UPRIGHT_SINCE_MS

    // 4000×4000 s otočením: oba priestory majú 1000×1000, geometria nikdy nerozhodne
    private fun square(box: StoredBox, cached: BoxSpace? = null, verify: (() -> BoxSpace?)? = null) =
        FaceSpaceRules.resolve(box, 4000, 4000, rot90, cached, verify)

    @Test
    fun overflowAboveTwoPercentIsUnclear() {
        // neotočená 4000×3000 → 1000×750
        assertEquals(BoxSpace.UPRIGHT, FaceSpaceRules.resolve(StoredBox(900, 600, 1010, 760, old), 4000, 3000, none))
        assertNull(FaceSpaceRules.resolve(StoredBox(900, 600, 1050, 740, old), 4000, 3000, none))
        assertNull(FaceSpaceRules.resolve(StoredBox(10, 10, 5, 20, old), 4000, 3000, none)) // prázdny rámček
    }

    @Test
    fun geometryDecidesWhenBoxFitsOnlyOneSpace() {
        // otočená 4000×3000: upright 750×1000, surová 1000×750
        assertEquals(BoxSpace.UPRIGHT, FaceSpaceRules.resolve(StoredBox(100, 800, 200, 900, old), 4000, 3000, rot90))
        assertEquals(BoxSpace.RAW, FaceSpaceRules.resolve(StoredBox(800, 100, 900, 200, old), 4000, 3000, rot90))
        assertNull(FaceSpaceRules.resolve(StoredBox(800, 800, 900, 900, old), 4000, 3000, rot90))
    }

    @Test
    fun verificationBeatsDateWhenGeometryCannotDecide() {
        val box = StoredBox(100, 100, 200, 200, fresh)
        // overenie odtlačkom má prednosť pred heuristikou podľa dátumu
        assertEquals(BoxSpace.RAW, square(box) { BoxSpace.RAW })
        // pokazený odtlačok = neposiela sa, aj keď by dátum hovoril „upright“
        assertEquals(BoxSpace.BROKEN, square(box) { BoxSpace.BROKEN })
        // overiť sa nedá → posledná záloha: dátum
        assertEquals(BoxSpace.UPRIGHT, square(box) { null })
        assertNull(square(box.copy(detectedAt = old)) { null })
    }

    @Test
    fun cachedVerificationIsUsedWithoutRecomputing() {
        var computed = 0
        val box = StoredBox(100, 100, 200, 200, old)
        assertEquals(BoxSpace.RAW, square(box, BoxSpace.RAW) { computed++; BoxSpace.UPRIGHT })
        assertEquals(0, computed)
        // uložené overenie, ktoré odporuje rozmerom, sa ignoruje
        assertEquals(
            BoxSpace.UPRIGHT,
            FaceSpaceRules.resolve(StoredBox(100, 800, 200, 900, old), 4000, 3000, rot90, BoxSpace.RAW),
        )
    }

    @Test
    fun cosinesDecideSpace() {
        assertEquals(BoxSpace.UPRIGHT, FaceSpaceRules.fromCosines(0.99f, 0.4f))
        assertEquals(BoxSpace.RAW, FaceSpaceRules.fromCosines(0.3f, 0.97f))
        assertEquals(BoxSpace.BROKEN, FaceSpaceRules.fromCosines(0.8f, 0.7f))
        assertNull(FaceSpaceRules.fromCosines(0.99f, 0.985f)) // obe rovnako dobré
        assertNull(FaceSpaceRules.fromCosines(null, null))
        assertEquals(BoxSpace.UPRIGHT, FaceSpaceRules.fromCosines(0.96f, null))
    }

    @Test
    fun rawSpaceBoxIsRotatedIntoUprightCoordinates() {
        val raw = StoredBox(800, 100, 900, 200, old)
        val rel = FaceSpaceRules.relBox(raw, 4000, 3000, rot90, BoxSpace.RAW)!!
        val expected = RelBox.fromAbsolute(800, 100, 900, 200, 1000, 750).transformed(RelBox.DEG_90, false)
        assertEquals(expected, rel)
        assertNull(FaceSpaceRules.relBox(raw, 4000, 3000, rot90, BoxSpace.BROKEN))
    }

    @Test
    fun unclearFaceIsSkippedAloneInRefButWholePhotoInBundle() {
        val clear = StoredBox(100, 800, 200, 900, old)
        val unclear = StoredBox(100, 100, 200, 200, old)
        // op-log: každá tvár sama (refFor) — jasná prejde, nejasná nie
        val space = FaceSpaceRules.resolve(clear, 4000, 3000, rot90)
        assertTrue(FaceSpaceRules.relBox(clear, 4000, 3000, rot90, space) != null)
        assertNull(FaceSpaceRules.resolve(unclear, 4000, 3000, rot90))
        // balík k fotke: tváre fotky sa pošlú len všetky (inak by vynechaná tvár u prijímateľa chýbala navždy)
        assertNull(BundleRules.uprightBoxes(listOf(clear, unclear), 4000, 3000, rot90))
        // po overení odtlačkom už balík odíde, s rámčekom v správnom priestore
        val boxes = BundleRules.uprightBoxes(listOf(clear, unclear), 4000, 3000, rot90, verify = { BoxSpace.RAW })!!
        assertEquals(FaceSpaceRules.relBox(unclear, 4000, 3000, rot90, BoxSpace.RAW), boxes[1])
    }

    @Test
    fun receiverUsesOneHypothesisWhenCertainOtherwiseBoth() {
        val box = StoredBox(100, 100, 200, 200, old)
        assertEquals(2, FaceSpaceRules.hypotheses(box, 4000, 4000, rot90, null).size)
        assertEquals(1, FaceSpaceRules.hypotheses(box, 4000, 4000, rot90, BoxSpace.UPRIGHT).size)
        assertTrue(FaceSpaceRules.hypotheses(box, 4000, 4000, rot90, BoxSpace.BROKEN).isEmpty())
    }

    @Test
    fun stampChangesWithBoxOrEmbedding() {
        val box = StoredBox(1, 2, 3, 4, old)
        val a = FaceSpaceRules.stamp(box, byteArrayOf(1, 2))
        assertEquals(a, FaceSpaceRules.stamp(box, byteArrayOf(1, 2)))
        assertTrue(a != FaceSpaceRules.stamp(box, byteArrayOf(1, 3)))
        assertTrue(a != FaceSpaceRules.stamp(box.copy(right = 5), byteArrayOf(1, 2)))
    }
}
