package org.fossify.gallery.family

import org.fossify.gallery.faces.FaceAssignmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// D6: priradenie z rodiny je cudzie, kým sa ho lokálne nikto nedotkne; cudzie sa neposiela späť ako vlastný vzor.
class RemoteOriginTest {
    private val written = FaceAssignmentEntity(5L, 1L, true, 1000L)
    private val row = RemoteOrigin.record(written, "A", "op1")

    @Test
    fun untouchedUntilLocalChange() {
        assertTrue(RemoteOrigin.untouched(row, written))
        assertFalse(RemoteOrigin.untouched(row, written.copy(updatedAt = 2000L))) // používateľ potvrdil znova
        assertFalse(RemoteOrigin.untouched(row, written.copy(personId = 2L))) // preradené
        assertFalse(RemoteOrigin.untouched(row, null)) // odobraté
    }

    @Test
    fun foreignFacesAreExcludedFromOwnAnchors() {
        val own = FaceAssignmentEntity(6L, 1L, true, 1500L)
        val touched = FaceAssignmentEntity(7L, 1L, true, 3000L)
        val rows = listOf(row, RemoteAssignmentEntity(7L, "A", "op2", 1L, 2000L))
        val current = listOf(written, own, touched).associateBy { it.faceId }
        assertEquals(setOf(5L), RemoteOrigin.foreignFaces(rows, current))
        assertEquals("bundle:A:t1", RemoteOrigin.bundleOpId("A:t1"))
    }
}
