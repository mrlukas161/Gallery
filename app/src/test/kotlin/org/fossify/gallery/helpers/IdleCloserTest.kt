package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IdleCloserTest {
    // ručný časovač: úlohy sa spustia až zavolaním fire()
    private class FakeScheduler : IdleCloser.Scheduler {
        val tasks = ArrayList<Pair<Long, Runnable>>()
        val cancelled = HashSet<Runnable>()

        override fun schedule(delayMs: Long, task: Runnable): IdleCloser.Cancel {
            tasks += delayMs to task
            return IdleCloser.Cancel { cancelled += task }
        }

        fun fireAll() {
            val pending = tasks.filter { it.second !in cancelled }
            tasks.clear()
            pending.forEach { it.second.run() }
        }

        fun pendingCount() = tasks.count { it.second !in cancelled }
    }

    private class Res(val id: Int) {
        var closed = false
    }

    private fun closer(scheduler: FakeScheduler, opened: MutableList<Res>, fail: Boolean = false) =
        IdleCloser(
            IDLE,
            open = { if (fail) null else Res(opened.size + 1).also { opened += it } },
            close = { it.closed = true },
            scheduler = scheduler,
        )

    @Test
    fun opensLazilyAndReusesWhileFresh() {
        val s = FakeScheduler()
        val opened = ArrayList<Res>()
        val c = closer(s, opened)
        assertFalse(c.isOpen)
        assertEquals(1, c.use { it.id })
        assertEquals(1, c.use { it.id })
        assertEquals(1, opened.size)
        assertEquals(IDLE, s.tasks.last().first)
        // každé použitie zruší predchádzajúci časovač, čaká len posledný
        assertEquals(1, s.pendingCount())
    }

    @Test
    fun closesAfterIdleAndReopensOnNextUse() {
        val s = FakeScheduler()
        val opened = ArrayList<Res>()
        val c = closer(s, opened)
        c.use { }
        s.fireAll()
        assertFalse(c.isOpen)
        assertTrue(opened[0].closed)
        assertEquals(2, c.use { it.id })
        assertFalse(opened[1].closed)
    }

    @Test
    fun staleTimerDoesNotCloseReusedResource() {
        val s = FakeScheduler()
        val opened = ArrayList<Res>()
        val c = closer(s, opened)
        c.use { }
        val stale = s.tasks.first().second
        c.use { }
        // starý časovač dobehne napriek zrušeniu (súbeh) — nesmie zavrieť
        stale.run()
        assertTrue(c.isOpen)
        assertFalse(opened[0].closed)
    }

    @Test
    fun timerNeverClosesWhileInUse() {
        val s = FakeScheduler()
        val opened = ArrayList<Res>()
        val c = closer(s, opened)
        c.use { }
        val timer = s.tasks.first().second
        c.use {
            timer.run()
            assertFalse(it.closed)
        }
        assertTrue(c.isOpen)
    }

    @Test
    fun releaseClosesNowWhenIdle() {
        val s = FakeScheduler()
        val opened = ArrayList<Res>()
        val c = closer(s, opened)
        c.use { }
        c.release()
        assertFalse(c.isOpen)
        assertTrue(opened[0].closed)
        assertEquals(0, s.pendingCount())
    }

    @Test
    fun releaseDuringUseClosesAfterLastUser() {
        val s = FakeScheduler()
        val opened = ArrayList<Res>()
        val c = closer(s, opened)
        c.use { outer ->
            c.use { inner ->
                c.release()
                assertFalse(inner.closed)
            }
            // druhý používateľ ešte beží
            assertFalse(outer.closed)
        }
        assertTrue(opened[0].closed)
        assertFalse(c.isOpen)
        assertEquals(0, s.pendingCount())
    }

    @Test
    fun failedOpenReturnsNullWithoutTimer() {
        val s = FakeScheduler()
        val c = closer(s, ArrayList(), fail = true)
        assertNull(c.use { 1 })
        assertFalse(c.isOpen)
        assertEquals(0, s.pendingCount())
    }

    @Test
    fun ifOpenDoesNotOpen() {
        val s = FakeScheduler()
        val opened = ArrayList<Res>()
        val c = closer(s, opened)
        var seen = 0
        c.ifOpen { seen++ }
        assertEquals(0, seen)
        c.use { }
        c.ifOpen { seen++ }
        assertEquals(1, seen)
        assertEquals(1, opened.size)
    }

    @Test
    fun exceptionInBlockStillCountsDown() {
        val s = FakeScheduler()
        val opened = ArrayList<Res>()
        val c = closer(s, opened)
        try {
            c.use { error("boom") }
        } catch (expected: IllegalStateException) {
        }
        s.fireAll()
        assertTrue(opened[0].closed)
    }

    @Test
    fun concurrentUsersNeverSeeClosedResource() {
        val opened = ArrayList<Res>()
        val c = IdleCloser(
            1L,
            open = { synchronized(opened) { Res(opened.size + 1).also { opened += it } } },
            close = { it.closed = true },
        )
        val start = CountDownLatch(1)
        var sawClosed = false
        val threads = (1..4).map {
            Thread {
                start.await()
                repeat(200) {
                    c.use { if (it.closed) sawClosed = true }
                    if (it % 50 == 0) c.release()
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
        assertFalse(sawClosed)
    }

    private companion object {
        const val IDLE = 60_000L
    }
}
