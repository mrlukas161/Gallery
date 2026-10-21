package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

// D7 + D10: odtlačky osôb — nahradenie sady, odľahlé vektory, rovnaká sada dvakrát, preinštalovanie.
class AnchorRulesTest {
    private fun unit(angle: Double) = floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat())

    private val set1 = (0 until 5).map { unit(it * 0.05) }
    private val set2 = (0 until 5).map { unit(1.0 + it * 0.05) }

    @Test
    fun outliersAreDroppedBeforePicking() {
        // 10 tvárí osoby (uhol ~0) a jedna chybne priradená (opačný smer)
        val own = (0 until 10).map { unit(it * 0.02) }
        val wrong = unit(Math.PI)
        val picked = AnchorPicker.pick(own + wrong)
        assertFalse(picked.any { it.contentEquals(wrong) })
        assertEquals(10, picked.size)
    }

    @Test
    fun diversePersonKeepsMostFaces() {
        // rôznorodá osoba (deti, roky): prah sa zníži na 25. percentil, vypadne najviac štvrtina
        val wide = (0 until 20).map { unit(it * 0.15) }
        assertTrue(AnchorPicker.withoutOutliers(wide).size >= 15)
    }

    @Test
    fun sameSetHasSameHashRegardlessOfOrder() {
        assertEquals(AnchorPicker.setHash(set1), AnchorPicker.setHash(set1.reversed()))
        assertTrue(AnchorPicker.setHash(set1) != AnchorPicker.setHash(set2))
    }

    @Test
    fun reinstalledPhoneWithNewDeviceIdReplacesItsOldSet() {
        // B má od telefónu A (staré deviceId) sadu; A sa preinštaloval (A2), obnovil zálohu F6 a pošle sadu znova
        val b = FamilyTestPhone("B").also { it.paired.add("A2") } // staré A už nie je spárované
        val p = b.person("Mama", "u1")
        b.anchors[p to "family:A"] = set1
        b.anchors[p to "picasa"] = set2
        val a2 = FamilyTestPhone("A2").also { it.person("Mama", "u1") }
        a2.record(AnchorsSet("u1", "m1", set1))
        b.receive(a2.own)
        assertEquals(setOf("family:A2", "picasa"), b.anchorSources(p)) // žiadna duplicitná sada, Picasa ostala
    }

    @Test
    fun reinstalledPhoneStillPairedUnderOldIdIsRecognisedByOverlap() {
        val b = FamilyTestPhone("B").also { it.paired.addAll(listOf("A", "A2")) }
        val p = b.person("Mama", "u1")
        b.anchors[p to "family:A"] = set1
        val a2 = FamilyTestPhone("A2").also { it.person("Mama", "u1") }
        a2.record(AnchorsSet("u1", "m1", set1.take(3) + set2.take(2)))
        b.receive(a2.own)
        assertEquals(setOf("family:A2"), b.anchorSources(p))
    }

    @Test
    fun otherPairedPhoneWithDifferentFacesKeepsItsSet() {
        val b = FamilyTestPhone("B").also { it.paired.addAll(listOf("A", "C")) }
        val p = b.person("Mama", "u1")
        b.anchors[p to "family:C"] = set2
        val a = FamilyTestPhone("A").also { it.person("Mama", "u1") }
        a.record(AnchorsSet("u1", "m1", set1))
        b.receive(a.own)
        assertEquals(setOf("family:A", "family:C"), b.anchorSources(p))
    }

    @Test
    fun restoredBackupAnchorsAreOwn() {
        val phone = FamilyTestPhone("A2")
        val p = phone.person("Mama", "u1")
        val backup = LoggedOp("b1:1", PeopleBackupFormat.deviceIdFor("b1"), 1, AnchorsSet("u1", "m1", set1))
        phone.applier.process(listOf(backup), phone, phone, phone)
        assertEquals(setOf(OpApplier.RESTORED_ANCHOR_SOURCE), phone.anchorSources(p))
        assertFalse(OpApplier.RESTORED_ANCHOR_SOURCE.startsWith(OpApplier.ANCHOR_SOURCE_PREFIX))
        assertTrue(OpApplier.isReplaceableSource(OpApplier.RESTORED_ANCHOR_SOURCE))
        assertFalse(OpApplier.isReplaceableSource("picasa"))
    }

    @Test
    fun supersededAnchorSetIsReported() {
        val superseded = ArrayList<String>()
        val phone = object : OpStore {
            val ops = LinkedHashMap<String, LoggedOp>()
            override fun store(op: LoggedOp) = ops.put(op.opId, op) == null
            override fun pending() = ops.values.toList()
            override fun markApplied(opId: String) {
                ops.remove(opId)
            }

            override fun supersede(op: LoggedOp) {
                superseded.add(op.opId)
            }
        }
        val world = FamilyTestPhone("B").also { it.person("Mama", "u1") }
        OpApplier("B", "m1").process(listOf(LoggedOp("x", "A", 3, AnchorsSet("u1", "m1", set1))), phone, world, world)
        assertEquals(listOf("x"), superseded)
    }
}
