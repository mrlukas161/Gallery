package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// D8: mená podľa „posledná zmena vyhráva“ (nameVersionLamport); návrh len pri súbežnej zmene;
// rozhodnutie o návrhu sa zapíše ako PersonRename, takže obe strany skončia s rovnakým menom.
class NameConvergenceTest {
    private val a = FamilyTestPhone("A").also { it.paired.add("B") }
    private val b = FamilyTestPhone("B").also { it.paired.add("A") }

    private fun bothHave(name: String?, uuid: String = "u1"): Pair<Long, Long> {
        val pa = a.person(name, uuid)
        val pb = b.person(name, uuid)
        return pa to pb
    }

    // „Prevziať“ ako FamilyProposals.accept: lokálne premenovanie + operácia z RenameResolution
    private fun accept(phone: FamilyTestPhone) {
        val p = phone.proposals.removeAt(0) as RenameProposal
        phone.persons[p.localId] = p.theirName
        phone.record(RenameResolution.onAccept(p))
    }

    private fun keep(phone: FamilyTestPhone) {
        val p = phone.proposals.removeAt(0) as RenameProposal
        RenameResolution.onKeep(p)?.let { phone.record(it) }
    }

    @Test
    fun sequentialRenameIsTakenSilently() {
        val (pa, pb) = bothHave("Babka")
        a.renameLocally("u1", "Stará mama")
        exchange(a, b)
        assertEquals("Stará mama", b.persons[pb])
        b.renameLocally("u1", "Starká") // B videl meno od A → jeho zmena je neskoršia
        exchange(a, b)
        assertEquals("Starká", a.persons[pa])
        assertTrue(a.proposals.isEmpty() && b.proposals.isEmpty())
    }

    @Test
    fun concurrentRenameMakesOneProposalOnOlderSide() {
        val (pa, pb) = bothHave("Babka")
        a.renameLocally("u1", "Stará mama") // lamport 1
        b.clock = 5
        b.renameLocally("u1", "Anka") // lamport 6: novšia, ale B meno od A nevidel
        exchange(a, b)
        // A (staršia zmena) dostane návrh; B novšiu cudziu zmenu nemá → staršiu od A preskočí
        assertEquals(1, a.proposals.size)
        assertTrue(b.proposals.isEmpty())
        assertEquals("Stará mama", a.persons[pa])
        assertEquals("Anka", b.persons[pb])
        keep(a) // A si ponechá svoje → zapíše sa ako novšia zmena
        exchange(a, b)
        assertEquals("Stará mama", a.persons[pa])
        assertEquals("Stará mama", b.persons[pb])
    }

    @Test
    fun concurrentRenameAcceptOnOlderSideConverges() {
        val (pa, pb) = bothHave("Babka")
        a.renameLocally("u1", "Stará mama")
        b.clock = 5
        b.renameLocally("u1", "Anka")
        exchange(a, b)
        accept(a)
        exchange(a, b)
        assertEquals("Anka", a.persons[pa])
        assertEquals("Anka", b.persons[pb])
        assertTrue(b.proposals.isEmpty())
    }

    @Test
    fun bothChooseTakeTheirsAndStillEndWithSameName() {
        // obe strany majú meno bez zapísanej verzie (napr. premenované pred spárovaním) → súbežná zmena na
        // OBOCH stranách vytvorí návrh
        val pa = a.person("Mama", "u1")
        val pb = b.person("Mamina", "u1")
        a.record(PersonRename("u1", "Mama", "Maminka"))
        b.record(PersonRename("u1", "Mamina", "Maminka"))
        a.nameVersions.remove("u1")
        b.nameVersions.remove("u1")
        exchange(a, b)
        assertEquals(1, a.proposals.size)
        assertEquals(1, b.proposals.size)
        // obaja zvolia „prevziať“ → bez D8 by si mená vymenili (A = Mamina, B = Mama)
        accept(a)
        accept(b)
        exchange(a, b)
        assertEquals(a.persons[pa], b.persons[pb])
        assertTrue(a.proposals.isEmpty() && b.proposals.isEmpty())
        // ďalšia výmena už nič nemení
        exchange(a, b)
        assertEquals(a.persons[pa], b.persons[pb])
    }

    @Test
    fun renamesDeliveredInReverseOrderEndWithLatest() {
        val (_, pb) = bothHave("Babka")
        a.renameLocally("u1", "Stará")
        a.renameLocally("u1", "Starká")
        b.receive(a.own.reversed())
        assertEquals("Starká", b.persons[pb])
        assertTrue(b.proposals.isEmpty())
    }
}
