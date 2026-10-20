package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Tabuľka scenárov op-logu (spec 2.4): poradie, duplicitné doručenie, každé pravidlo rozporov,
// mazanie sa nikdy nepreberie. Lokálny stav je pamäťová napodobenina people.db + family.db.
class OpApplierTest {

    private class FakeWorld : FamilyState, OpStore, ActionSink {
        val persons = LinkedHashMap<Long, String?>()
        val uuidToLocal = HashMap<String, Long>()
        val faces = HashMap<String, Long>() // contentKey -> faceId (jedna tvár na fotku stačí)
        val assignments = HashMap<Long, LocalAssignment>()
        val cannotLinks = HashSet<Pair<Long, Long>>()
        val groups = LinkedHashMap<String, MutableSet<Long>>()
        val anchorsBySource = HashMap<Pair<Long, String>, MutableList<FloatArray>>()
        val proposals = ArrayList<Proposal>()
        val ops = LinkedHashMap<String, LoggedOp>()
        val applied = HashSet<String>()
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

        override fun anchors(localId: Long, source: String): List<FloatArray> =
            anchorsBySource[localId to source].orEmpty()

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
                is ApplyAction.Reject -> {
                    assignments.remove(action.faceId)
                    cannotLinks.add(action.faceId to action.localId)
                }

                is ApplyAction.CreateGroup -> groups[action.name] = action.members.toMutableSet()
                is ApplyAction.AddGroupMembers -> groups.getValue(action.name).addAll(action.members)
                is ApplyAction.RenameGroup -> groups[action.newName] = groups.remove(action.oldName)!!
                is ApplyAction.AddAnchors ->
                    anchorsBySource.getOrPut(action.localId to action.source) { ArrayList() }.addAll(action.vectors)

                is ApplyAction.Propose -> proposals.add(action.proposal)
            }
        }
    }

    private val world = FakeWorld()
    private val applier = OpApplier(ownDeviceId = ME, faceModelId = MODEL)
    private var lamport = 0L

    private fun op(o: Op, device: String = PEER, opId: String = "op${++lamport}", at: Long = lamport) =
        LoggedOp(opId, device, at, o)

    private fun receive(vararg ops: LoggedOp) = applier.process(ops.toList(), world, world, world)

    private fun ref(key: String) = FaceRef(key, listOf(RelBox(0.1f, 0.1f, 0.3f, 0.3f)))

    // --- potichu sa prevezme ---

    @Test
    fun newPersonIsCreatedSilently() {
        val r = receive(op(PersonCreate("u1", "Mama")))
        assertEquals(1, r.applied)
        assertEquals("Mama", world.persons[world.uuidToLocal["u1"]])
        assertTrue(world.proposals.isEmpty())
    }

    @Test
    fun newAssignmentWithoutConflictIsTakenSilently() {
        world.faces["k1"] = 1L
        receive(op(PersonCreate("u1", "Mama")), op(AssignFace(ref("k1"), "u1", manual = true)))
        assertEquals(LocalAssignment(world.uuidToLocal.getValue("u1"), true), world.assignments[1L])
        assertTrue(world.proposals.isEmpty())
    }

    @Test
    fun newGroupIsCreatedAndMembersOnlyAdded() {
        val a = world.person("A", "ua")
        val b = world.person("B", "ub")
        receive(op(GroupSet("Rodina", listOf("ua"))))
        assertEquals(setOf(a), world.groups["Rodina"])
        // druhý snímok bez A: A sa NEODOBERIE, B pribudne
        receive(op(GroupSet("Rodina", listOf("ub"))))
        assertEquals(setOf(a, b), world.groups["Rodina"])
    }

    @Test
    fun groupWaitsForMemberThatHasNotArrivedYet() {
        val r = receive(op(GroupSet("Rodina", listOf("u1")), at = 2))
        assertEquals(1, r.deferred)
        assertTrue(world.groups.isEmpty())
        receive(op(PersonCreate("u1", "Mama"), at = 1))
        assertEquals(setOf(world.uuidToLocal.getValue("u1")), world.groups["Rodina"])
    }

    @Test
    fun groupRenameIsTakenUnlessTargetExists() {
        world.groups["Rodina"] = mutableSetOf()
        world.groups["Práca"] = mutableSetOf()
        receive(op(GroupRename("Rodina", "Rodina 1")))
        assertTrue(world.groups.containsKey("Rodina 1"))
        val r = receive(op(GroupRename("Práca", "Rodina 1")))
        assertEquals(1, r.skipped)
        assertTrue(world.groups.containsKey("Práca"))
    }

    @Test
    fun anchorsAreStoredWithFamilySourceAndCapped() {
        val p = world.person("Mama", "u1")
        val vectors = (0 until 25).map { floatArrayOf(it.toFloat(), 1f) }
        receive(op(AnchorsSet("u1", MODEL, vectors)))
        val stored = world.anchorsBySource[p to "family:$PEER"].orEmpty()
        assertEquals(AnchorsSet.MAX_ANCHORS, stored.size)
        // opakovaný balík s tými istými vektormi nič nepridá
        val r = receive(op(AnchorsSet("u1", MODEL, vectors.take(3))))
        assertEquals(1, r.skipped)
        assertEquals(AnchorsSet.MAX_ANCHORS, world.anchorsBySource[p to "family:$PEER"]!!.size)
    }

    @Test
    fun anchorsOfOtherFaceModelAreDropped() {
        world.person("Mama", "u1")
        val r = receive(op(AnchorsSet("u1", "iny-model", listOf(floatArrayOf(1f, 0f)))))
        assertEquals(1, r.skipped)
        assertTrue(world.anchorsBySource.isEmpty())
        assertTrue(world.applied.isNotEmpty()) // zahodená = spracovaná, neodkladá sa
    }

    @Test
    fun renameIsTakenSilentlyWhenOnlyTheyRenamed() {
        val p = world.person("Mama", "u1")
        receive(op(PersonRename("u1", "Mamina", previousName = "Mama")))
        assertEquals("Mamina", world.persons[p])
        assertTrue(world.proposals.isEmpty())
    }

    @Test
    fun unnamedPersonGetsTheirName() {
        val p = world.person(null, "u1")
        receive(op(PersonRename("u1", "Dedo", previousName = null)))
        assertEquals("Dedo", world.persons[p])
    }

    // --- návrhy ---

    @Test
    fun renameOnBothSidesBecomesProposal() {
        val p = world.person("Babka Anka", "u1") // tu premenovaná z „Babka“
        receive(op(PersonRename("u1", "Stará mama", previousName = "Babka")))
        assertEquals("Babka Anka", world.persons[p]) // nič sa neprepísalo
        assertEquals(listOf(RenameProposal("u1", p, "Stará mama", "Babka Anka")), world.proposals)
    }

    @Test
    fun sameNameBecomesMergeProposalAndLaterOpsWait() {
        val mine = world.person("Mama", "mine")
        world.faces["k1"] = 1L
        val r = receive(op(PersonCreate("u1", "mama")), op(AssignFace(ref("k1"), "u1", manual = true)))
        assertEquals(listOf<Proposal>(SameNameProposal("u1", "mama", mine)), world.proposals)
        assertEquals(1, world.persons.size) // druhá „Mama“ nevznikla
        assertEquals(1, r.deferred) // priradenie čaká na rozhodnutie
        assertNull(world.assignments[1L])
    }

    @Test
    fun theirMergeBecomesProposal() {
        val a = world.person("Ďuri", "ua")
        val b = world.person("Juraj", "ub")
        receive(op(PersonMerge("ua", "ub")))
        assertEquals(listOf<Proposal>(MergeProposal("ua", "ub", a, b)), world.proposals)
        assertEquals(2, world.persons.size)
    }

    @Test
    fun removingManualFaceBecomesProposal() {
        val p = world.person("Mama", "u1")
        world.faces["k1"] = 1L
        world.assignments[1L] = LocalAssignment(p, manual = true)
        receive(op(UnassignFace(ref("k1"), "u1")))
        assertEquals(LocalAssignment(p, true), world.assignments[1L])
        assertEquals(listOf<Proposal>(FaceRemoveProposal(ref("k1"), 1L, p)), world.proposals)
    }

    @Test
    fun removingAutomaticFaceIsTakenSilently() {
        val p = world.person("Mama", "u1")
        world.faces["k1"] = 1L
        world.assignments[1L] = LocalAssignment(p, manual = false)
        receive(op(UnassignFace(ref("k1"), "u1")))
        assertNull(world.assignments[1L])
        assertTrue(world.cannotLinks.contains(1L to p))
        assertTrue(world.proposals.isEmpty())
    }

    @Test
    fun movingManualFaceToOtherPersonBecomesProposal() {
        val mama = world.person("Mama", "u1")
        val teta = world.person("Teta", "u2")
        world.faces["k1"] = 1L
        world.assignments[1L] = LocalAssignment(mama, manual = true)
        receive(op(AssignFace(ref("k1"), "u2", manual = true)))
        assertEquals(LocalAssignment(mama, true), world.assignments[1L])
        assertEquals(listOf<Proposal>(FaceMoveProposal(ref("k1"), 1L, mama, teta)), world.proposals)
    }

    @Test
    fun deletionIsNeverTakenOnlyProposed() {
        val p = world.person("Sused", "u1")
        world.faces["k1"] = 1L
        world.assignments[1L] = LocalAssignment(p, manual = true)
        receive(op(PersonDelete("u1", "Sused")))
        assertEquals("Sused", world.persons[p])
        assertEquals(LocalAssignment(p, true), world.assignments[1L])
        assertEquals(listOf<Proposal>(DeleteProposal("u1", p, "Sused")), world.proposals)
    }

    // --- ručné má prednosť ---

    @Test
    fun manualBeatsAutomaticEvenWhenAutomaticComesLater() {
        val mama = world.person("Mama", "u1")
        world.person("Teta", "u2")
        world.faces["k1"] = 1L
        world.assignments[1L] = LocalAssignment(mama, manual = true)
        val r = receive(op(AssignFace(ref("k1"), "u2", manual = false)))
        assertEquals(1, r.skipped)
        assertEquals(LocalAssignment(mama, true), world.assignments[1L])
        assertTrue(world.proposals.isEmpty())
    }

    @Test
    fun theirManualReplacesMyAutomatic() {
        val mama = world.person("Mama", "u1")
        val teta = world.person("Teta", "u2")
        world.faces["k1"] = 1L
        world.assignments[1L] = LocalAssignment(mama, manual = false)
        receive(op(AssignFace(ref("k1"), "u2", manual = true)))
        assertEquals(LocalAssignment(teta, true), world.assignments[1L])
    }

    @Test
    fun manualConfirmsSamePersonAutomatic() {
        val mama = world.person("Mama", "u1")
        world.faces["k1"] = 1L
        world.assignments[1L] = LocalAssignment(mama, manual = false)
        receive(op(AssignFace(ref("k1"), "u1", manual = true)))
        assertEquals(LocalAssignment(mama, true), world.assignments[1L])
    }

    @Test
    fun automaticDoesNotReplaceAutomatic() {
        val mama = world.person("Mama", "u1")
        world.person("Teta", "u2")
        world.faces["k1"] = 1L
        world.assignments[1L] = LocalAssignment(mama, manual = false)
        receive(op(AssignFace(ref("k1"), "u2", manual = false)))
        assertEquals(LocalAssignment(mama, false), world.assignments[1L])
    }

    // --- poradie a idempotencia ---

    @Test
    fun outOfOrderDeliveryWithinBatchIsSortedByLamport() {
        world.faces["k1"] = 1L
        val create = op(PersonCreate("u1", "Mama"), at = 1)
        val assign = op(AssignFace(ref("k1"), "u1", manual = true), at = 2)
        val r = receive(assign, create)
        assertEquals(2, r.applied)
        assertEquals(0, r.deferred)
        assertEquals(world.uuidToLocal["u1"], world.assignments[1L]?.personId)
    }

    @Test
    fun opThatArrivesBeforeItsPersonWaitsForNextBatch() {
        world.faces["k1"] = 1L
        val first = receive(op(AssignFace(ref("k1"), "u1", manual = true), at = 5))
        assertEquals(1, first.deferred)
        val second = receive(op(PersonCreate("u1", "Mama"), at = 3))
        assertEquals(2, second.applied)
        assertEquals(0, second.deferred)
        assertEquals(world.uuidToLocal["u1"], world.assignments[1L]?.personId)
    }

    @Test
    fun faceThatIsNotHereYetWaitsAndKeepsOrderWithLaterRemoval() {
        val p = world.person("Mama", "u1")
        val assign = op(AssignFace(ref("k1"), "u1", manual = false))
        val unassign = op(UnassignFace(ref("k1"), "u1"))
        assertEquals(2, receive(assign, unassign).deferred)
        world.faces["k1"] = 1L // fotka dorazila (F4)
        val r = receive()
        assertEquals(2, r.applied)
        assertNull(world.assignments[1L]) // automatické priradenie a jeho odobratie v správnom poradí
        assertTrue(world.cannotLinks.contains(1L to p))
    }

    @Test
    fun duplicateDeliveryIsAppliedOnlyOnce() {
        val create = op(PersonCreate("u1", "Mama"))
        receive(create)
        val again = receive(create, create.copy())
        assertEquals(2, again.duplicates)
        assertEquals(0, again.applied)
        assertEquals(1, world.persons.size)
    }

    @Test
    fun duplicateProposalIsNotCreatedTwice() {
        world.person("Ďuri", "ua")
        world.person("Juraj", "ub")
        val merge = op(PersonMerge("ua", "ub"))
        receive(merge)
        receive(merge)
        receive(merge)
        assertEquals(1, world.proposals.size)
    }

    @Test
    fun ownOpsAreIgnored() {
        val r = receive(op(PersonCreate("u1", "Mama"), device = ME))
        assertEquals(1, r.duplicates)
        assertTrue(world.persons.isEmpty())
    }

    @Test
    fun sameOutcomeRegardlessOfDeliveryOrder() {
        fun scenario(order: List<Int>): Map<Long, String?> {
            val w = FakeWorld()
            val a = OpApplier(ME, MODEL)
            val ops = listOf(
                LoggedOp("a", PEER, 1, PersonCreate("u1", "Babka")),
                LoggedOp("b", PEER, 2, PersonRename("u1", "Stará mama", "Babka")),
                LoggedOp("c", PEER, 3, PersonRename("u1", "Starká", "Stará mama")),
            )
            order.forEach { a.process(listOf(ops[it]), w, w, w) }
            return w.persons
        }
        val expected = scenario(listOf(0, 1, 2)).values.toList()
        assertEquals(listOf<String?>("Starká"), expected)
        // premenovania prichádzajú v ľubovoľnom poradí, ale aplikujú sa podľa lamport hodín
        assertEquals(expected, scenario(listOf(2, 1, 0)).values.toList())
        assertEquals(expected, scenario(listOf(1, 0, 2)).values.toList())
    }

    @Test
    fun opsOfDeletedLocalPersonAreSkipped() {
        val p = world.person("Mama", "u1")
        world.persons.remove(p) // zmazaná tu
        world.faces["k1"] = 1L
        val r = receive(op(AssignFace(ref("k1"), "u1", manual = true)), op(PersonRename("u1", "X", "Mama")))
        assertEquals(2, r.skipped)
        assertTrue(world.assignments.isEmpty())
        assertTrue(world.persons.isEmpty()) // osoba sa neobnoví
    }

    @Test
    fun failedWriteIsRetriedLater() {
        var fail = true
        val flaky = ActionSink { action, op -> if (fail) error("disk plný") else world.execute(action, op) }
        val r = applier.process(listOf(op(PersonCreate("u1", "Mama"))), world, world, flaky)
        assertEquals(1, r.failed)
        assertTrue(world.persons.isEmpty())
        fail = false
        val again = applier.process(emptyList(), world, world, flaky)
        assertEquals(1, again.applied)
        assertEquals(1, world.persons.size)
    }

    companion object {
        private const val ME = "ja"
        private const val PEER = "manzelka"
        private const val MODEL = "mobilefacenet-v1"
    }
}
