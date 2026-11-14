package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Revízia 11/2026: fronta „Poslať“ — hotový prenos sa uprace celý, nedokončený nie; zlyhanie nezmizne potichu.
class OutboxRulesTest {
    private val t1 = "11111111-1111-1111-1111-111111111111"
    private val t2 = "22222222-2222-2222-2222-222222222222"
    private val t3 = "33333333-3333-3333-3333-333333333333"
    private val pending = OutboxEntity.STATE_PENDING
    private val sent = OutboxEntity.STATE_SENT
    private val failed = OutboxEntity.STATE_FAILED

    private fun chunk(id: Long, t: String, state: String) = OutboxRules.Row(id, t, true, state)
    private fun final(id: Long, t: String, state: String) = OutboxRules.Row(id, t, false, state)

    @Test
    fun onlyTransfersWithSentFinalAreFinished() {
        val rows = listOf(
            chunk(1, t1, sent), chunk(2, t1, sent), final(3, t1, sent),
            // kúsky odišli, ale záverečný balík nie → kúsky sa nemažú (dajú sa poslať znova)
            chunk(4, t2, sent), final(5, t2, pending),
        )
        assertEquals(setOf(t1), OutboxRules.finishedTransfers(rows))
        assertEquals(listOf(4L), OutboxRules.chunksToResend(rows, t2))
    }

    @Test
    fun failureMarksWholeTransferAndIsCounted() {
        val rows = listOf(chunk(1, t1, sent), chunk(2, t1, failed), final(3, t1, pending), final(4, t2, pending))
        assertEquals(setOf(t1), OutboxRules.failedTransfers(rows))
        assertEquals(listOf(1L, 3L), OutboxRules.toFail(rows, t1))
        val summary = OutboxRules.summary(rows)
        assertEquals(1, summary.queued)
        assertEquals(1, summary.failed)
    }

    @Test
    fun summaryCountsPhotosNotChunks() {
        val rows = listOf(
            chunk(1, t1, pending), chunk(2, t1, pending), final(3, t1, pending),
            final(4, t2, pending),
            chunk(5, t3, sent), final(6, t3, sent),
        )
        assertEquals(OutboxRules.Summary(queued = 2, failed = 0), OutboxRules.summary(rows))
        assertTrue(OutboxRules.summary(emptyList()).let { it.queued == 0 && it.failed == 0 })
    }

    @Test
    fun rowFromOutboxEntity() {
        val ref = BundleTransfer.Ref(t1, 0, 2, BundleTransfer.CHUNK_SIZE, BundleTransfer.CHUNK_SIZE + 10L, 5)
        val item = OutboxEntity(7, "peer", BundleTransfer.KIND_CHUNK, BundleTransfer.encode(ref), 1)
        assertEquals(OutboxRules.Row(7, t1, true, pending), OutboxRules.row(item))
        val broken = OutboxEntity(8, "peer", BundleTransfer.KIND_PHOTO, "nonsense", 1)
        assertEquals(OutboxRules.Row(8, null, false, pending), OutboxRules.row(broken))
    }
}
