package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// D1: po obnove staršej family.db musí nová operácia odísť (hodiny sa pri hello zdvihnú na „have“ druhej strany).
class LamportClockTest {

    private class FakeMeta : MetaDao() {
        val values = HashMap<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(meta: MetaEntity) {
            values[meta.key] = meta.value
        }

        override fun delete(key: String) {
            values.remove(key)
        }
    }

    private fun op(lamport: Long) = OpEntity("op$lamport", "ja", lamport, OpType.PERSON_RENAME, "{}")

    @Test
    fun newOpAfterRestoreOfOlderDbIsSent() {
        // telefón mal 10 operácií, druhý telefón ich má všetky (have = 10); potom sa obnovila záloha s hodinami 5
        val peerHave = 10L
        val restored = FakeMeta().apply { put(MetaEntity(MetaEntity.KEY_LAMPORT, "5")) }
        val ownOps = (1L..5L).map { op(it) }.toMutableList()

        // bez zdvihnutia by nová operácia mala lamport 6 ≤ have a druhá strana by si ju nikdy nevypýtala
        val withoutFix = FakeMeta().apply { put(MetaEntity(MetaEntity.KEY_LAMPORT, "5")) }.nextLamport()
        assertFalse(LamportClock.page(ownOps + op(withoutFix), peerHave, 100).first.any { it.lamport == withoutFix })

        // s D1: hello zdvihne hodiny na have, nová operácia dostane 11 a odíde
        restored.raiseLamport(peerHave)
        val fresh = restored.nextLamport()
        assertEquals(11L, fresh)
        ownOps.add(op(fresh))
        val (batch, more) = LamportClock.page(ownOps, peerHave, 100)
        assertEquals(listOf(11L), batch.map { it.lamport })
        assertFalse(more)
    }

    @Test
    fun raiseNeverLowersClock() {
        val meta = FakeMeta().apply { put(MetaEntity(MetaEntity.KEY_LAMPORT, "40")) }
        assertEquals(40L, meta.raiseLamport(12))
        assertEquals(41L, meta.nextLamport())
        assertEquals(50L, LamportClock.afterHello(50, 7))
        assertEquals(7L, FakeMeta().raiseLamport(7))
    }

    @Test
    fun pageDoesNotSplitSameLamport() {
        val ops = listOf(op(1), op(2), OpEntity("b", "ja", 2, OpType.PERSON_RENAME, "{}"), op(3))
        val (batch, more) = LamportClock.page(ops, 0, 2)
        assertEquals(3, batch.size)
        assertTrue(more)
    }
}
