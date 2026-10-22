package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HeavyGateTest {
    private val always = { true }

    private fun thread(block: () -> Unit): Thread = Thread(block).apply {
        isDaemon = true
        start()
    }

    @Test
    fun freeGateDoesNotWait() {
        val gate = HeavyGate(POLL)
        var waited = false
        val lease = gate.acquire(always) { waited = true }
        assertNotNull(lease)
        assertFalse(waited)
        assertTrue(gate.isBusy)
        lease!!.close()
        assertFalse(gate.isBusy)
    }

    @Test
    fun stoppedTaskNeverGetsSlot() {
        val gate = HeavyGate(POLL)
        assertNull(gate.acquire({ false }))
        assertFalse(gate.isBusy)
    }

    @Test
    fun onlyOneHeavyTaskRunsAtOnce() {
        val gate = HeavyGate(POLL)
        val running = AtomicInteger(0)
        val maxRunning = AtomicInteger(0)
        val start = CountDownLatch(1)
        val threads = (1..4).map {
            thread {
                start.await()
                repeat(5) {
                    val lease = gate.acquire(always) ?: return@repeat
                    val now = running.incrementAndGet()
                    maxRunning.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    Thread.sleep(2)
                    running.decrementAndGet()
                    lease.close()
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(JOIN_MS) }
        assertEquals(1, maxRunning.get())
        assertFalse(gate.isBusy)
    }

    @Test
    fun waitingTaskReportsWaitAndRunsAfterRelease() {
        val gate = HeavyGate(POLL)
        val first = gate.acquire(always)!!
        val waited = CountDownLatch(1)
        val got = CountDownLatch(1)
        thread {
            gate.acquire(always) { waited.countDown() }?.use { got.countDown() }
        }
        assertTrue(waited.await(1, TimeUnit.SECONDS))
        assertFalse(got.await(POLL * 3, TimeUnit.MILLISECONDS))
        first.close()
        assertTrue(got.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun stopWhileWaitingGivesUpWithoutSlot() {
        val gate = HeavyGate(POLL)
        val first = gate.acquire(always)!!
        val running = AtomicBoolean(true)
        val result = AtomicInteger(-1)
        val waited = CountDownLatch(1)
        val t = thread {
            val lease = gate.acquire({ running.get() }) { waited.countDown() }
            result.set(if (lease == null) 0 else 1)
            lease?.close()
        }
        assertTrue(waited.await(1, TimeUnit.SECONDS))
        running.set(false)
        t.join(JOIN_MS)
        assertEquals(0, result.get())
        // miesto ostalo prvej úlohe a po nej je voľné
        assertTrue(gate.isBusy)
        first.close()
        assertFalse(gate.isBusy)
    }

    @Test
    fun waitersGetSlotInArrivalOrder() {
        val gate = HeavyGate(POLL)
        val first = gate.acquire(always)!!
        val order = Collections.synchronizedList(ArrayList<Int>())
        val threads = ArrayList<Thread>()
        for (i in 1..3) {
            val waiting = CountDownLatch(1)
            threads += thread {
                gate.acquire(always) { waiting.countDown() }?.use { order.add(i) }
            }
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            // nech je ďalší určite až za ním vo fronte semaforu
            Thread.sleep(POLL * 2)
        }
        first.close()
        threads.forEach { it.join(JOIN_MS) }
        assertEquals(listOf(1, 2, 3), order.toList())
    }

    @Test
    fun reentrantOnSameThreadAndLeaseCloseIsIdempotent() {
        val gate = HeavyGate(POLL)
        val outer = gate.acquire(always)!!
        val inner = gate.acquire({ false }) // na tom istom vlákne sa nečaká (ani pri zastavení)
        assertNotNull(inner)
        inner!!.close()
        inner.close()
        assertTrue(gate.isBusy)
        outer.close()
        outer.close()
        assertFalse(gate.isBusy)
        // druhé close nesmie pridať ďalšie miesto
        val a = gate.acquire(always)!!
        val other = AtomicInteger(-1)
        thread { other.set(if (gate.acquire({ false }) == null) 0 else 1) }.join(JOIN_MS)
        assertEquals(0, other.get())
        a.close()
    }

    @Test
    fun slotIsLazyAndReleasedOnClose() {
        val gate = HeavyGate(POLL)
        var resumed = 0
        val slot = HeavySlot(always, onResume = { resumed++ }, gate = gate)
        assertFalse(gate.isBusy)
        assertTrue(slot.ensure())
        assertTrue(slot.ensure())
        assertTrue(slot.held)
        assertEquals(0, resumed)
        slot.close()
        assertFalse(gate.isBusy)
        assertFalse(slot.held)
    }

    @Test
    fun slotResumesAfterWaitAndRequireThrowsWhenStopped() {
        val gate = HeavyGate(POLL)
        val first = gate.acquire(always)!!
        val resumed = AtomicInteger(0)
        val done = CountDownLatch(1)
        thread {
            HeavySlot(always, onResume = { resumed.incrementAndGet() }, gate = gate).use { slot ->
                slot.ensure()
                done.countDown()
            }
        }
        Thread.sleep(POLL * 2)
        first.close()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertEquals(1, resumed.get())

        val blocker = gate.acquire(always)!!
        val threw = AtomicBoolean(false)
        thread {
            try {
                HeavySlot({ false }, gate = gate).require()
            } catch (expected: HeavyGate.Stopped) {
                threw.set(true)
            }
        }.join(JOIN_MS)
        assertTrue(threw.get())
        blocker.close()
    }

    private companion object {
        const val POLL = 20L
        const val JOIN_MS = 5_000L
    }
}
