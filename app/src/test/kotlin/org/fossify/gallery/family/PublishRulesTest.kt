package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Revízia 11/2026 (P1 súkromie): osoba a skupina odídu do druhého telefónu až s tvárou z povoleného priečinka.
class PublishRulesTest {
    private val locked = "/storage/emulated/0/Doklady"
    private val allows: (String) -> Boolean = { !it.startsWith("$locked/") }

    @Test
    fun personOnlyInLockedFolderIsNotPublished() {
        assertFalse(PublishRules.hasAllowedFace(sequenceOf("$locked/a.jpg", "$locked/b.jpg"), allows))
        assertFalse(PublishRules.hasAllowedFace(emptySequence(), allows))
        assertTrue(PublishRules.hasAllowedFace(sequenceOf("$locked/a.jpg", "/storage/emulated/0/DCIM/c.jpg"), allows))
    }

    @Test
    fun allowedFaceStopsTheSearchEarly() {
        var checked = 0
        val paths = generateSequence(0) { it + 1 }.map { "/DCIM/$it.jpg" }
        assertTrue(PublishRules.hasAllowedFace(paths) { checked++; true })
        assertEquals(1, checked)
    }

    @Test
    fun groupGoesOnlyWithPublishedMembers() {
        val published = mapOf(1L to "u1", 3L to "u3")
        assertEquals(listOf("u1", "u3"), PublishRules.groupMembers(listOf(1L, 2L, 3L, 1L)) { published[it] })
        // skupina len s osobami zo zamknutého priečinka sa neposiela vôbec (ani jej názov)
        assertNull(PublishRules.groupMembers(listOf(2L, 4L)) { published[it] })
        assertNull(PublishRules.groupMembers(emptyList()) { published[it] })
    }

    @Test
    fun removedGroupMembersAreTrackedUntilAddedBack() {
        // Jano bol v skupine (GroupSet), teraz nie je → odobratý
        val removed = PublishRules.removedMembers(setOf("lukas", "jano"), setOf("lukas"), emptySet())
        assertEquals(setOf("jano"), removed)
        // ďalšia zmena skupiny ho nezabudne
        val stillRemoved = PublishRules.removedMembers(setOf("lukas", "maria"), setOf("lukas", "maria"), removed)
        assertEquals(setOf("jano"), stillRemoved)
        // opätovne pridaný vypadne zo zoznamu
        val back = PublishRules.removedMembers(setOf("lukas", "jano"), setOf("lukas", "jano"), removed)
        assertEquals(emptySet<String>(), back)
    }

    @Test
    fun setEncodingRoundTrip() {
        val set = setOf("b-uuid", "a-uuid")
        assertEquals("a-uuid,b-uuid", PublishRules.encodeSet(set))
        assertEquals(set, PublishRules.decodeSet(PublishRules.encodeSet(set)))
        assertEquals(emptySet<String>(), PublishRules.decodeSet(null))
        assertEquals(emptySet<String>(), PublishRules.decodeSet(" , "))
    }
}
