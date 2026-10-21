package org.fossify.gallery.family

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

// A3: doplnené kľúče zálohy osôb (automatické priradenia, „Toto nie je táto osoba“, ignorované tváre)
// — zápis/čítanie, spätná kompatibilita a obnova, ktorá len dopĺňa.
class PeopleBackupExtrasTest {

    private fun ref(key: String) = FaceRef(key, listOf(RelBox(0.1f, 0.2f, 0.3f, 0.4f)))

    private val extras = PeopleBackupFormat.Extras(
        auto = listOf(PeopleBackupFormat.FaceLink(ref("a1"), "u1"), PeopleBackupFormat.FaceLink(ref("a2"), "u2")),
        cannot = listOf(PeopleBackupFormat.FaceLink(ref("c1"), "u1")),
        ignored = listOf(ref("i1")),
    )

    private fun backup(e: PeopleBackupFormat.Extras = extras) = PeopleBackupFormat.build(
        "b1", 1_700_000_000_000L, null, "m", listOf(PersonCreate("u1", "Mama")), e,
    )

    private fun roundTrip(b: PeopleBackupFormat.Backup): PeopleBackupFormat.Backup? {
        val out = ByteArrayOutputStream()
        PeopleBackupFormat.write(b, out)
        return PeopleBackupFormat.read(ByteArrayInputStream(out.toByteArray()))
    }

    // --- formát ---

    @Test
    fun extrasSurviveRoundTrip() {
        val read = roundTrip(backup())
        assertNotNull(read)
        assertEquals(extras, read!!.extras)
        assertEquals(1, read.ops.size)
    }

    @Test
    fun backupWithoutExtrasHasNoNewKeys() {
        val json = PeopleBackupFormat.toJson(backup(PeopleBackupFormat.Extras()))
        assertFalse(json.has("auto_assign"))
        assertFalse(json.has("cannot_links"))
        assertFalse(json.has("ignored_faces"))
        assertEquals(PeopleBackupFormat.VERSION, json.getInt("v"))
    }

    @Test
    fun olderBackupWithoutExtrasReadsAsEmpty() {
        val json = PeopleBackupFormat.toJson(backup(PeopleBackupFormat.Extras()))
        val read = PeopleBackupFormat.fromJson(JSONObject(json.toString()))
        assertNotNull(read)
        assertEquals(PeopleBackupFormat.Extras(), read!!.extras)
    }

    @Test
    fun damagedExtrasEntriesAreSkipped() {
        val json = PeopleBackupFormat.toJson(backup())
        val auto = json.getJSONArray("auto_assign")
        auto.put(JSONObject().put("ck", "x")) // bez rámčekov
        auto.put(JSONObject().put("ck", "y").put("boxes", JSONArray().put(JSONArray().put(1.0))).put("uuid", "u1"))
        auto.put("nie je objekt")
        json.getJSONArray("ignored_faces").put(JSONObject().put("boxes", JSONArray()))
        val read = PeopleBackupFormat.fromJson(JSONObject(json.toString()))
        assertNotNull(read)
        assertEquals(extras, read!!.extras)
    }

    // --- obnova ---

    private class Target(
        val faces: Map<String, Long>,
        val persons: Map<String, Long>,
        val assignments: Map<Long, LocalAssignment> = emptyMap(),
        val cannot: Set<Pair<Long, Long>> = emptySet(),
        val ignored: Set<Long> = emptySet(),
    ) : ExtrasTarget {
        override fun findFace(ref: FaceRef): Long? = faces[ref.contentKey]

        override fun localPersonId(uuid: String): Long? = persons[uuid]

        override fun assignment(faceId: Long): LocalAssignment? = assignments[faceId]

        override fun isCannot(faceId: Long, personId: Long): Boolean = (faceId to personId) in cannot

        override fun isIgnored(faceId: Long): Boolean = faceId in ignored
    }

    private val allFaces = mapOf("a1" to 1L, "a2" to 2L, "c1" to 3L, "i1" to 4L)
    private val allPersons = mapOf("u1" to 10L, "u2" to 20L)

    @Test
    fun emptyTargetGetsEverything() {
        val (actions, report) = PeopleBackupExtras.plan(extras, Target(allFaces, allPersons))
        assertEquals(
            setOf(
                ExtrasAction.AutoAssign(1, 10),
                ExtrasAction.AutoAssign(2, 20),
                ExtrasAction.CannotLink(3, 10),
                ExtrasAction.Ignore(4),
            ),
            actions.toSet(),
        )
        assertEquals(ExtrasReport(auto = 2, cannot = 1, ignored = 1), report)
    }

    @Test
    fun existingAssignmentIsNeverOverwritten() {
        val target = Target(allFaces, allPersons, assignments = mapOf(1L to LocalAssignment(20, true)))
        val (actions, report) = PeopleBackupExtras.plan(extras, target)
        assertFalse(actions.any { it is ExtrasAction.AutoAssign && it.faceId == 1L })
        assertEquals(1, report.kept)
    }

    @Test
    fun cannotLinkIsSkippedWhenFaceBelongsToThatPerson() {
        val target = Target(allFaces, allPersons, assignments = mapOf(3L to LocalAssignment(10, true)))
        val (actions, _) = PeopleBackupExtras.plan(extras, target)
        assertFalse(actions.any { it is ExtrasAction.CannotLink })
    }

    @Test
    fun autoAssignRespectsCannotAndIgnoredFromSameBackup() {
        val e = PeopleBackupFormat.Extras(
            auto = listOf(PeopleBackupFormat.FaceLink(ref("a1"), "u1"), PeopleBackupFormat.FaceLink(ref("i1"), "u2")),
            cannot = listOf(PeopleBackupFormat.FaceLink(ref("a1"), "u1")),
            ignored = listOf(ref("i1")),
        )
        val (actions, report) = PeopleBackupExtras.plan(e, Target(allFaces, allPersons))
        assertTrue(actions.none { it is ExtrasAction.AutoAssign })
        assertEquals(2, report.kept)
    }

    @Test
    fun missingFacesAndPersonsAreCounted() {
        val target = Target(mapOf("a1" to 1L), mapOf("u2" to 20L))
        val (actions, report) = PeopleBackupExtras.plan(extras, target)
        assertTrue(actions.isEmpty())
        assertEquals(4, report.missing)
    }

    @Test
    fun repeatedRestoreAddsNothing() {
        val target = Target(
            allFaces,
            allPersons,
            assignments = mapOf(1L to LocalAssignment(10, false), 2L to LocalAssignment(20, false)),
            cannot = setOf(3L to 10L),
            ignored = setOf(4L),
        )
        val (actions, report) = PeopleBackupExtras.plan(extras, target)
        assertTrue(actions.isEmpty())
        assertEquals(4, report.kept)
    }
}
