package org.fossify.gallery.family

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Záloha osôb .galeria-family (F6): formát (zápis → čítanie → rovnaké opy) a obnova cez OpApplier
// ako príjem od „zariadenia zálohy“ (potichu doplní, rozpory sú návrhy, nič nemaže).
class PeopleBackupTest {

    private fun ref(key: String) = FaceRef(key, listOf(RelBox(0.1f, 0.2f, 0.3f, 0.4f)))

    private fun sampleOps(): List<Op> = PeopleBackupFormat.snapshotOps(
        persons = listOf(
            PeopleBackupFormat.SnapPerson("u1", "Mama"),
            PeopleBackupFormat.SnapPerson("u2", null),
        ),
        manual = listOf(ref("k1") to "u1", ref("k2") to "u2"),
        groups = listOf(PeopleBackupFormat.SnapGroup("Rodina", listOf("u1", "u2"))),
        anchors = mapOf("u1" to listOf(floatArrayOf(0.6f, 0.8f), floatArrayOf(1f, 0f))),
        faceModelId = MODEL,
    )

    private fun backup(ops: List<Op> = sampleOps()) =
        PeopleBackupFormat.build("b1", 1_700_000_000_000L, "Xiaomi 15", MODEL, ops)

    private fun roundTrip(b: PeopleBackupFormat.Backup): PeopleBackupFormat.Backup? {
        val out = ByteArrayOutputStream()
        PeopleBackupFormat.write(b, out)
        return PeopleBackupFormat.read(ByteArrayInputStream(out.toByteArray()))
    }

    // --- formát ---

    @Test
    fun snapshotHasPersonsRenamesAssignmentsGroupsAndAnchors() {
        val types = sampleOps().map { it.type }
        assertEquals(
            listOf(
                OpType.PERSON_CREATE, OpType.PERSON_CREATE, OpType.PERSON_RENAME,
                OpType.ASSIGN_FACE, OpType.ASSIGN_FACE, OpType.GROUP_SET, OpType.ANCHORS_SET,
            ),
            types,
        )
        val anchors = sampleOps().filterIsInstance<AnchorsSet>().single()
        assertEquals(MODEL, anchors.faceModelId)
        assertTrue(sampleOps().filterIsInstance<AssignFace>().all { it.manual })
    }

    @Test
    fun anchorsAreCappedAtTwenty() {
        val many = (0 until 30).map { floatArrayOf(it.toFloat(), 1f) }
        val ops = PeopleBackupFormat.snapshotOps(
            listOf(PeopleBackupFormat.SnapPerson("u1", "A")), emptyList(), emptyList(), mapOf("u1" to many), MODEL,
        )
        assertEquals(AnchorsSet.MAX_ANCHORS, ops.filterIsInstance<AnchorsSet>().single().vectors.size)
    }

    @Test
    fun writeThenReadGivesSameOps() {
        val original = backup()
        val read = roundTrip(original)
        assertNotNull(read)
        read!!
        assertEquals(original.id, read.id)
        assertEquals(original.createdAt, read.createdAt)
        assertEquals("Xiaomi 15", read.deviceName)
        assertEquals(MODEL, read.faceModelId)
        assertEquals(original.ops, read.ops) // OpEntity: opId, deviceId, lamport, type, payload
        assertEquals("backup:b1", read.ops.first().deviceId)
        // payload sa dá dekódovať na tie isté operácie (odtlačky porovnané po prvkoch)
        val decoded = PeopleRestore.logged(read).map { it.op }
        val expected = sampleOps()
        assertEquals(expected.size, decoded.size)
        expected.zip(decoded).forEach { (e, d) ->
            if (e is AnchorsSet && d is AnchorsSet) {
                assertEquals(e.uuid, d.uuid)
                e.vectors.zip(d.vectors).forEach { (a, b) -> assertArrayEquals(a, b, 0f) }
            } else {
                assertEquals(e, d)
            }
        }
    }

    @Test
    fun opsUseSameJsonAsPeerSync() {
        val json = PeopleBackupFormat.toJson(backup())
        val first = json.getJSONArray("ops").getJSONObject(0)
        assertEquals(setOf("id", "lamport", "type", "payload"), first.keys().asSequence().toSet())
        assertEquals("b1:1", first.getString("id"))
        assertEquals(1L, first.getLong("lamport"))
        assertEquals(OpCodec.encode(PersonCreate("u1", "Mama")), first.getString("payload"))
    }

    @Test
    fun sameBackupHasStableOpIds() {
        assertEquals(backup().ops.map { it.opId }, backup().ops.map { it.opId })
    }

    @Test
    fun foreignOrNewerFilesAreRejected() {
        val good = PeopleBackupFormat.toJson(backup())
        assertNotNull(PeopleBackupFormat.fromJson(good))
        assertNull(PeopleBackupFormat.fromJson(JSONObject(good.toString()).put("format", "iné")))
        assertNull(PeopleBackupFormat.fromJson(JSONObject(good.toString()).put("v", PeopleBackupFormat.VERSION + 1)))
        assertNull(PeopleBackupFormat.fromJson(JSONObject(good.toString()).put("id", "")))
        assertNull(PeopleBackupFormat.read(ByteArrayInputStream("nie je zip".toByteArray())))
        // ZIP bez people.json
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("iny.txt"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        assertNull(PeopleBackupFormat.read(ByteArrayInputStream(out.toByteArray())))
    }

    // --- obnova cez OpApplier ---

    private class World : FamilyState, OpStore, ActionSink {
        val persons = LinkedHashMap<Long, String?>()
        val uuidToLocal = HashMap<String, Long>()
        val faces = HashMap<String, Long>()
        val assignments = HashMap<Long, LocalAssignment>()
        val groups = LinkedHashMap<String, MutableSet<Long>>()
        val anchors = HashMap<Long, MutableList<FloatArray>>()
        val proposals = ArrayList<Proposal>()
        val ops = LinkedHashMap<String, LoggedOp>()
        val applied = HashSet<String>()
        var removed = 0
        private var nextId = 100L

        fun person(name: String?, uuid: String? = null): Long {
            val id = nextId++
            persons[id] = name
            if (uuid != null) uuidToLocal[uuid] = id
            return id
        }

        override fun localPersonId(uuid: String): Long? = uuidToLocal[uuid]?.takeIf { persons.containsKey(it) }

        override fun isKnown(uuid: String) = uuidToLocal.containsKey(uuid)

        override fun personName(localId: Long) = persons[localId]

        override fun personByName(name: String): Long? =
            persons.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key

        override fun findFace(ref: FaceRef) = faces[ref.contentKey]

        override fun assignment(faceId: Long) = assignments[faceId]

        override fun groupMembers(name: String): Set<Long>? = groups[name]

        override fun anchors(localId: Long, source: String): List<FloatArray> = anchors[localId].orEmpty()

        override fun store(op: LoggedOp): Boolean {
            if (ops.containsKey(op.opId)) return false
            ops[op.opId] = op
            return true
        }

        override fun pending() = ops.values.filter { it.opId !in applied }

        override fun markApplied(opId: String) {
            applied.add(opId)
        }

        override fun execute(action: ApplyAction, op: LoggedOp) {
            when (action) {
                is ApplyAction.CreatePerson -> person(action.name, action.uuid)
                is ApplyAction.RenamePerson -> persons[action.localId] = action.name
                is ApplyAction.Assign -> assignments[action.faceId] = LocalAssignment(action.localId, action.manual)
                is ApplyAction.Reject -> removed++
                is ApplyAction.CreateGroup -> groups[action.name] = action.members.toMutableSet()
                is ApplyAction.AddGroupMembers -> groups.getValue(action.name).addAll(action.members)
                is ApplyAction.RenameGroup -> removed++
                is ApplyAction.ReplaceAnchors -> {
                    // D10: odtlačky zo zálohy sú vlastné, nie „family:backup:…“
                    assertEquals(OpApplier.RESTORED_ANCHOR_SOURCE, action.source)
                    anchors[action.localId] = action.vectors.toMutableList()
                }
                is ApplyAction.Propose -> proposals.add(action.proposal)
            }
        }
    }

    private val world = World()
    private val applier = OpApplier(ownDeviceId = "me", faceModelId = MODEL)

    private fun restore(b: PeopleBackupFormat.Backup = roundTrip(backup())!!) =
        PeopleRestore.restore(b, applier, world, world, world)

    @Test
    fun restoreOnEmptyPhoneAddsEverythingSilently() {
        world.faces["k1"] = 1L
        world.faces["k2"] = 2L
        val r = restore()
        assertEquals(0, r.proposals)
        val mama = world.uuidToLocal.getValue("u1")
        val unnamed = world.uuidToLocal.getValue("u2")
        assertEquals("Mama", world.persons[mama])
        assertNull(world.persons[unnamed])
        assertEquals(LocalAssignment(mama, true), world.assignments[1L])
        assertEquals(LocalAssignment(unnamed, true), world.assignments[2L])
        assertEquals(setOf(mama, unnamed), world.groups["Rodina"])
        assertEquals(2, world.anchors[mama]?.size)
        assertEquals(0, world.removed)
    }

    @Test
    fun restoringTwiceChangesNothing() {
        world.faces["k1"] = 1L
        restore()
        val persons = HashMap(world.persons)
        val r = restore()
        assertEquals(persons, world.persons)
        assertEquals(0, r.proposals)
        assertEquals(backup().ops.size, r.duplicates)
    }

    @Test
    fun differentNameBecomesProposalNotOverwrite() {
        val local = world.person("Mamka", "u1")
        world.person(null, "u2")
        val r = restore()
        assertEquals("Mamka", world.persons[local])
        assertEquals(1, r.proposals)
        val p = world.proposals.single() as RenameProposal
        assertEquals("Mama", p.theirName)
        assertEquals("Mamka", p.myName)
    }

    @Test
    fun missingNameIsFilledSilently() {
        val local = world.person(null, "u1")
        restore()
        assertEquals("Mama", world.persons[local])
        assertTrue(world.proposals.isEmpty())
    }

    @Test
    fun manualAssignmentToOtherPersonBecomesProposal() {
        world.person("Mama", "u1")
        world.person(null, "u2")
        val other = world.person("Teta")
        world.faces["k1"] = 1L
        world.assignments[1L] = LocalAssignment(other, true)
        restore()
        assertEquals(LocalAssignment(other, true), world.assignments[1L])
        assertTrue(world.proposals.any { it is FaceMoveProposal })
    }

    @Test
    fun deletedNamedPersonIsRestoredUnnamedIsNot() {
        // osoby existovali (UUID je známe), ale lokálne ich medzičasom odstránili
        world.uuidToLocal["u1"] = 1L
        world.uuidToLocal["u2"] = 2L
        restore()
        val mama = world.localPersonId("u1")
        assertNotNull(mama)
        assertEquals("Mama", world.persons[mama!!])
        assertNull(world.localPersonId("u2"))
    }

    @Test
    fun sameNameAsLocalPersonBecomesProposal() {
        world.person("Mama")
        restore()
        assertTrue(world.proposals.any { it is SameNameProposal })
        assertEquals(1, world.persons.values.count { it == "Mama" })
    }

    @Test
    fun photosNotHereWaitAndOnlyBackupOpsAreProcessed() {
        // odložená operácia z prepojeného telefónu sa pri obnove nespracuje
        world.store(LoggedOp("peer-op", "peer", 1, PersonCreate("p1", "Ujo")))
        val r = restore()
        assertEquals(2, r.deferred) // AssignFace k1, k2: fotky tu ešte nie sú
        assertNull(world.localPersonId("p1"))
        assertTrue("peer-op" !in world.applied)
    }

    @Test
    fun otherFaceModelAnchorsAreIgnored() {
        val ops = PeopleBackupFormat.snapshotOps(
            listOf(PeopleBackupFormat.SnapPerson("u1", "A")),
            emptyList(),
            emptyList(),
            mapOf("u1" to listOf(floatArrayOf(1f, 0f))),
            "iny-model",
        )
        restore(PeopleBackupFormat.build("b2", 1, null, "iny-model", ops))
        assertTrue(world.anchors.isEmpty())
    }

    companion object {
        private const val MODEL = "facenet-test"
    }
}
