package org.fossify.gallery.family

import org.fossify.gallery.helpers.ProcessingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Kedy plánovať okno pri nabíjaní a čo v ňom spustiť (F5 bod 3).
class FamilyScheduleTest {

    private fun cond(
        paired: Boolean = true,
        processing: Boolean = false,
        charging: Boolean = true,
        pct: Int = 50,
        unmetered: Boolean = true,
    ) = FamilySchedule.Conditions(paired, processing, charging, pct, unmetered)

    @Test
    fun jobIsScheduledOnlyWhenPairedOrWorkWaits() {
        assertTrue(FamilySchedule.shouldSchedule(paired = true, processingPending = false))
        assertTrue(FamilySchedule.shouldSchedule(paired = false, processingPending = true))
        assertFalse(FamilySchedule.shouldSchedule(paired = false, processingPending = false))
    }

    @Test
    fun unmeteredNetworkIsRequiredOnlyForSync() {
        assertEquals(FamilySchedule.Constraints(true, true), FamilySchedule.constraints(paired = true))
        assertEquals(FamilySchedule.Constraints(true, false), FamilySchedule.constraints(paired = false))
    }

    @Test
    fun chargingWithWifiRunsSyncAndDeferredProcessing() {
        assertEquals(FamilySchedule.Plan(sync = true, process = true), FamilySchedule.plan(cond(processing = true)))
        assertEquals(FamilySchedule.Plan(sync = true, process = false), FamilySchedule.plan(cond()))
        assertEquals(
            FamilySchedule.Plan(sync = false, process = true),
            FamilySchedule.plan(cond(paired = false, processing = true)),
        )
    }

    @Test
    fun meteredNetworkSkipsSyncButNotProcessing() {
        assertEquals(
            FamilySchedule.Plan(sync = false, process = true),
            FamilySchedule.plan(cond(processing = true, unmetered = false)),
        )
    }

    @Test
    fun withoutChargingNothingRuns() {
        assertTrue(FamilySchedule.plan(cond(processing = true, charging = false, pct = 90)).isEmpty)
        assertTrue(FamilySchedule.plan(cond(processing = true, charging = false, pct = 10)).isEmpty)
    }

    @Test
    fun lowBatteryWithoutChargingBlocksEverything() {
        val low = ProcessingPolicy.LOW_BATTERY_PCT - 1
        assertTrue(FamilySchedule.lowBattery(charging = false, batteryPct = low))
        assertFalse(FamilySchedule.lowBattery(charging = false, batteryPct = ProcessingPolicy.LOW_BATTERY_PCT))
        assertFalse(FamilySchedule.lowBattery(charging = true, batteryPct = 5))
        assertFalse(FamilySchedule.lowBattery(charging = false, batteryPct = ProcessingPolicy.UNKNOWN))
        // na nabíjačke aj pri 5 % (nabíja sa)
        assertFalse(FamilySchedule.plan(cond(charging = true, pct = 5)).isEmpty)
    }

    @Test
    fun resumeAttemptNeedsPeerBatteryAndPause() {
        val now = 1_000_000L
        assertTrue(FamilySchedule.shouldTryOnResume(true, false, 80, 0, now))
        assertFalse(FamilySchedule.shouldTryOnResume(false, false, 80, 0, now))
        assertFalse(FamilySchedule.shouldTryOnResume(true, false, 10, 0, now))
        assertTrue(FamilySchedule.shouldTryOnResume(true, true, 10, 0, now))
        val recent = now - FamilySchedule.RESUME_MIN_INTERVAL_MS + 1
        assertFalse(FamilySchedule.shouldTryOnResume(true, false, 80, recent, now))
        assertTrue(FamilySchedule.shouldTryOnResume(true, false, 80, now - FamilySchedule.RESUME_MIN_INTERVAL_MS, now))
    }

    @Test
    fun resumeAttemptAtMostEveryFifteenMinutes() {
        val now = 100_000_000L
        assertEquals(15 * 60 * 1000L, FamilySchedule.RESUME_MIN_INTERVAL_MS)
        // 2 minúty po poslednom pokuse (predtým stačila minúta) sa nič nespúšťa
        assertFalse(FamilySchedule.shouldTryOnResume(true, false, 80, now - 2 * 60 * 1000L, now))
        val interval = FamilySchedule.RESUME_MIN_INTERVAL_MS
        assertFalse(FamilySchedule.shouldTryOnResume(true, false, 80, now - interval + 1, now))
        assertTrue(FamilySchedule.shouldTryOnResume(true, false, 80, now - interval, now))
    }

    @Test
    fun pendingOutboxTriesRightAwayButNotOnEveryResume() {
        val now = 100_000_000L
        val twoMinAgo = now - 2 * 60 * 1000L
        assertTrue(FamilySchedule.shouldTryOnResume(true, false, 80, twoMinAgo, now, outboxPending = true))
        val justNow = now - FamilySchedule.RESUME_PENDING_MIN_INTERVAL_MS + 1
        assertFalse(FamilySchedule.shouldTryOnResume(true, false, 80, justNow, now, outboxPending = true))
        // fronta neobíde spárovanie ani slabú batériu
        assertFalse(FamilySchedule.shouldTryOnResume(false, false, 80, 0, now, outboxPending = true))
        assertFalse(FamilySchedule.shouldTryOnResume(true, false, 10, 0, now, outboxPending = true))
    }

    @Test
    fun clockMovedBackDoesNotBlockResumeAttempts() {
        val now = 100_000_000L
        assertTrue(FamilySchedule.shouldTryOnResume(true, false, 80, now + 60_000L, now))
    }

    @Test
    fun windowContinuesUntilOneHourThenStartsAnew() {
        val start = 10_000_000L
        assertEquals(start, FamilySchedule.windowStart(0, start))
        assertEquals(start, FamilySchedule.windowStart(start, start + FamilySchedule.WINDOW_MS - 1))
        val later = start + FamilySchedule.WINDOW_MS
        assertEquals(later, FamilySchedule.windowStart(start, later))
        // hodiny posunuté dozadu → nové okno
        assertEquals(start - 5, FamilySchedule.windowStart(start, start - 5))
    }

    @Test
    fun segmentStaysUnderJobLimitAndInsideWindow() {
        val start = 0L
        assertEquals(FamilySchedule.SEGMENT_MS, FamilySchedule.segmentEnd(start, start))
        assertTrue(FamilySchedule.SEGMENT_MS < 10 * 60 * 1000L)
        val nearEnd = FamilySchedule.WINDOW_MS - 60_000L
        assertEquals(FamilySchedule.WINDOW_MS, FamilySchedule.segmentEnd(start, nearEnd))
    }

    @Test
    fun nextStepAfterSegment() {
        val later = FamilySchedule.Next.Later(FamilySchedule.NEXT_WINDOW_DELAY_MS)
        // nesynchronizované a okno ešte trvá → ďalší beh
        assertEquals(FamilySchedule.Next.Continue, FamilySchedule.next(true, false, 0, 1000, false, true))
        // synchronizované → okno končí skôr
        assertEquals(later, FamilySchedule.next(true, false, 0, 1000, true, true))
        // hodina uplynula
        assertEquals(later, FamilySchedule.next(true, false, 0, FamilySchedule.WINDOW_MS, false, true))
        // bez pokusu (napr. meraná sieť) sa okno nepredlžuje, nezacyklí sa
        assertEquals(later, FamilySchedule.next(true, false, 0, 1000, false, false))
        // len spracovanie, bez spárovania
        assertEquals(later, FamilySchedule.next(false, true, 0, 1000, false, false))
        // nič spárované, nič nečaká → zrušiť
        assertEquals(FamilySchedule.Next.Cancel, FamilySchedule.next(false, false, 0, 1000, true, true))
    }
}
