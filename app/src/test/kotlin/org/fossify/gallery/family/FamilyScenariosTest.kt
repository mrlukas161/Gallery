package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Scenáre prenosu z analýzy (balík D) na dvoch napodobených telefónoch.
class FamilyScenariosTest {
    private val a = FamilyTestPhone("A").also { it.paired.add("B") }
    private val b = FamilyTestPhone("B").also { it.paired.add("A") }

    private fun ref(key: String) = FaceRef(key, listOf(RelBox(0.1f, 0.1f, 0.3f, 0.3f)))

    // D6: chybné ručné priradenie sa prenesie a jeho oprava z toho istého telefónu príde potichu (nie ako návrh)
    @Test
    fun wrongAssignmentAndItsCorrectionFromSamePhoneIsSilent() {
        a.person("Ján", "jan")
        a.person("Peter", "peter")
        val bJan = b.person("Ján", "jan")
        val bPeter = b.person("Peter", "peter")
        b.faces["k1"] = 7L
        a.record(AssignFace(ref("k1"), "jan", manual = true)) // omyl: je to Peter
        exchange(a, b)
        assertEquals(LocalAssignment(bJan, true), b.assignment(7L))
        assertEquals("A", b.assignmentOrigin(7L))

        // A opraví: preradí na Petra
        a.record(AssignFace(ref("k1"), "peter", manual = true))
        exchange(a, b)
        assertEquals(LocalAssignment(bPeter, true), b.assignment(7L))
        assertTrue(b.proposals.isEmpty())
    }

    @Test
    fun wrongAssignmentRemovedBySamePhoneIsSilent() {
        a.person("Ján", "jan")
        b.person("Ján", "jan")
        b.faces["k1"] = 7L
        a.record(AssignFace(ref("k1"), "jan", manual = true))
        exchange(a, b)
        a.record(UnassignFace(ref("k1"), "jan"))
        exchange(a, b)
        assertNull(b.assignment(7L))
        assertTrue(b.proposals.isEmpty())
    }

    @Test
    fun correctionBecomesProposalWhenUserConfirmedItHere() {
        a.person("Ján", "jan")
        val bJan = b.person("Ján", "jan")
        b.faces["k1"] = 7L
        a.record(AssignFace(ref("k1"), "jan", manual = true))
        exchange(a, b)
        b.assignLocally(7L, bJan) // používateľ na B tvár potvrdil → už je vlastná
        assertNull(b.assignmentOrigin(7L))
        a.record(UnassignFace(ref("k1"), "jan"))
        exchange(a, b)
        assertEquals(LocalAssignment(bJan, true), b.assignment(7L))
        assertEquals(1, b.proposals.size)
    }

    @Test
    fun correctionFromOtherPhoneStaysProposal() {
        val c = FamilyTestPhone("C")
        a.person("Ján", "jan")
        val bJan = b.person("Ján", "jan")
        b.faces["k1"] = 7L
        a.record(AssignFace(ref("k1"), "jan", manual = true))
        exchange(a, b)
        c.person("Ján", "jan")
        c.record(UnassignFace(ref("k1"), "jan"))
        b.receive(c.own)
        assertEquals(LocalAssignment(bJan, true), b.assignment(7L))
        assertEquals(1, b.proposals.size)
    }

    // D9: priradenie k fotke, ktorá tu ešte nie je, čaká na jej obsahový kľúč
    @Test
    fun opWaitsForPhotoKey() {
        a.person("Ján", "jan")
        b.person("Ján", "jan")
        a.record(AssignFace(ref("whatsapp-original"), "jan", manual = true))
        val r = b.receive(a.own)
        assertEquals(1, r.deferred)
        b.faces["whatsapp-original"] = 9L // D4: kópia sa spárovala cez bundle_inbox.applied_path
        b.receive(emptyList())
        assertEquals(b.uuidToLocal["jan"], b.assignment(9L)?.personId)
    }
}
