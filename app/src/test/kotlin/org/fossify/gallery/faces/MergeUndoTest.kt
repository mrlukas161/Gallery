package org.fossify.gallery.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// E2 — formát merge_log a plán vrátenia zlúčenia
class MergeUndoTest {
    private val assigns = listOf(
        FaceAssignmentEntity(42, 9, true, 13, AssignmentSource.MANUAL, null),
        FaceAssignmentEntity(43, 9, true, 14, AssignmentSource.ACCEPTED_SUGGESTION, 0.83f),
        FaceAssignmentEntity(44, 9, false, 15, AssignmentSource.FAMILY, null),
        FaceAssignmentEntity(45, 9, true, 16),
    )

    private fun log(undoneAt: Long? = null) = MergeLogEntity(
        id = 1,
        fromPersonId = 9,
        toPersonId = 3,
        faceIds = MergeLogCodec.encodeAssignments(assigns),
        cannotLinks = MergeLogCodec.encodeLinks(MergeLogCodec.Links(listOf(99, 100), listOf(99), listOf(43))),
        createdAt = 5000,
        fromName = "Janko",
        fromCreatedAt = 3000,
        anchorIds = MergeLogCodec.encodeIds(listOf(2, 7)),
        groupIds = MergeLogCodec.encodeGroups(MergeLogCodec.Groups(listOf(4, 5), listOf(5))),
        undoneAt = undoneAt,
    )

    @Test
    fun codecRoundTrip() {
        val back = MergeLogCodec.decodeAssignments(MergeLogCodec.encodeAssignments(assigns))
        assertEquals(4, back.size)
        assertEquals(MergeLogCodec.Assign(43, true, AssignmentSource.ACCEPTED_SUGGESTION, 0.83f, 14), back[1])
        assertEquals(MergeLogCodec.Assign(44, false, AssignmentSource.FAMILY, null, 15), back[2])
        assertEquals(MergeLogCodec.Assign(45, true, null, null, 16), back[3])
        val links = MergeLogCodec.Links(listOf(1, 2), listOf(2), emptyList())
        assertEquals(links, MergeLogCodec.decodeLinks(MergeLogCodec.encodeLinks(links)))
        assertEquals(listOf(7L, 8L), MergeLogCodec.decodeIds(MergeLogCodec.encodeIds(listOf(7, 8))))
        // poškodený alebo chýbajúci text = prázdne (vrátenie potom vráti len osobu)
        assertTrue(MergeLogCodec.decodeAssignments("{nie je json").isEmpty())
        assertTrue(MergeLogCodec.decodeIds(null).isEmpty())
        assertEquals(MergeLogCodec.Groups(emptyList(), emptyList()), MergeLogCodec.decodeGroups(""))
    }

    @Test
    fun undoRestoresPersonFacesLinksAnchorsAndGroups() {
        val state = MergeUndo.State(
            personIds = setOf(3, 8),
            targetFaces = setOf(5, 42, 43, 44, 45),
            targetLinks = setOf(99, 100),
        )
        val plan = (MergeUndo.plan(log(), state) as MergeUndo.Outcome.Ready).plan
        assertEquals(9L, plan.personId)
        assertEquals("Janko", plan.name)
        assertEquals(3000L, plan.createdAt)
        assertEquals(3L, plan.targetId)
        // pôvodné priradenia presne (pôvod, istota, ručné/rodinné, čas)
        assertEquals(assigns, plan.assignments)
        assertEquals(listOf(99L, 100L), plan.insertFromLinks)
        // „99 nie je“ presunulo zlúčenie na cieľovú osobu → späť; 100 mala cieľová osoba už predtým → ostane
        assertEquals(listOf(99L), plan.deleteTargetLinks)
        // „43 nie je Ján“ zlúčenie zmazalo (43 patrila Jankovi) → vráti sa
        assertEquals(listOf(43L), plan.insertTargetLinks)
        assertEquals(listOf(2L, 7L), plan.anchorIds)
        assertEquals(listOf(4L, 5L), plan.groupsAdd)
        assertEquals(listOf(5L), plan.groupsRemoveTarget)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun facesMovedSinceMergeStayWhereTheyAre() {
        // 43 a 45 medzičasom preradené inam (alebo zrušené) — vrátenie ich nevezme
        val state = MergeUndo.State(personIds = setOf(3), targetFaces = setOf(42, 44), targetLinks = emptySet())
        val plan = (MergeUndo.plan(log(), state) as MergeUndo.Outcome.Ready).plan
        assertEquals(listOf(42L, 44L), plan.assignments.map { it.faceId })
        assertEquals(2, plan.skipped)
        // „43 nie je Ján“ sa nevráti — 43 už nie je Jankova
        assertTrue(plan.insertTargetLinks.isEmpty())
        // 99 už cieľová osoba nemá (niekto ho zmazal) → nie je čo mazať
        assertTrue(plan.deleteTargetLinks.isEmpty())
    }

    @Test
    fun refusedCases() {
        val state = MergeUndo.State(setOf(3), setOf(42), emptySet())
        assertEquals(
            MergeUndo.Outcome.Refused(MergeUndo.Blocked.ALREADY_UNDONE),
            MergeUndo.plan(log(undoneAt = 6000), state),
        )
        assertEquals(
            MergeUndo.Outcome.Refused(MergeUndo.Blocked.PERSON_EXISTS),
            MergeUndo.plan(log(), state.copy(personIds = setOf(3, 9))),
        )
        assertEquals(
            MergeUndo.Outcome.Refused(MergeUndo.Blocked.TARGET_MISSING),
            MergeUndo.plan(log(), state.copy(personIds = setOf(8))),
        )
    }

    @Test
    fun missingOptionalColumnsStillUndo() {
        val bare = log().copy(fromCreatedAt = null, anchorIds = null, groupIds = null, fromName = null)
        val outcome = MergeUndo.plan(bare, MergeUndo.State(setOf(3), setOf(42), emptySet()))
        val plan = (outcome as MergeUndo.Outcome.Ready).plan
        assertEquals(5000L, plan.createdAt)
        assertNull(plan.name)
        assertTrue(plan.anchorIds.isEmpty() && plan.groupsAdd.isEmpty())
    }
}
