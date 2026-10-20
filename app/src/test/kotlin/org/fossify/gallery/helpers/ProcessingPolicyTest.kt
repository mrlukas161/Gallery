package org.fossify.gallery.helpers

import org.fossify.gallery.helpers.ProcessingPolicy.Decision
import org.fossify.gallery.helpers.ProcessingPolicy.Indexer
import org.fossify.gallery.helpers.ProcessingPolicy.Profile
import org.fossify.gallery.helpers.ProcessingPolicy.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingPolicyTest {
    private fun decide(
        profile: Profile = Profile.FULL,
        newOnlyFirst: Boolean = false,
        charging: Boolean = false,
        batteryPct: Int = 80,
        batchSize: Int = 5,
        indexer: Indexer = Indexer.FACES,
        trigger: Trigger = Trigger.AUTO,
    ) = ProcessingPolicy.decide(profile, newOnlyFirst, charging, batteryPct, batchSize, indexer, trigger)

    @Test
    fun fullProfileWithoutOptionBehavesAsBefore() {
        // ani slabá batéria, ani veľká dávka nič nemenia — správanie ako doteraz
        for (indexer in Indexer.values()) {
            assertEquals(Decision.RUN, decide(indexer = indexer, batteryPct = 5, batchSize = 10_000))
            assertEquals(Decision.RUN, decide(indexer = indexer, batteryPct = ProcessingPolicy.UNKNOWN))
        }
    }

    @Test
    fun lightProfileRunsOnlyFacesAndGeo() {
        val allowed = setOf(Indexer.FACES, Indexer.GEO)
        for (indexer in Indexer.values()) {
            val auto = decide(profile = Profile.LIGHT, charging = true, indexer = indexer)
            val manual = decide(profile = Profile.LIGHT, indexer = indexer, trigger = Trigger.MANUAL)
            if (indexer in allowed) {
                assertEquals(Decision.RUN, auto)
                assertEquals(Decision.RUN, manual)
            } else {
                assertEquals(Decision.SKIP_PROFILE, auto)
                assertEquals(Decision.SKIP_PROFILE, manual)
            }
        }
    }

    @Test
    fun lightProfileAutoWaitsForCharging() {
        assertEquals(Decision.WAIT_FOR_CHARGING, decide(profile = Profile.LIGHT, batchSize = 1))
        assertEquals(Decision.RUN, decide(profile = Profile.LIGHT, charging = true, batchSize = 10_000))
    }

    @Test
    fun lowBatteryStopsWhenPolicyActive() {
        val low = ProcessingPolicy.LOW_BATTERY_PCT - 1
        assertEquals(Decision.STOP_LOW_BATTERY, decide(newOnlyFirst = true, batteryPct = low, batchSize = 1))
        assertEquals(Decision.STOP_LOW_BATTERY, decide(profile = Profile.LIGHT, batteryPct = low))
        // pri nabíjaní sa nezastavuje ani pri slabej batérii
        assertEquals(Decision.RUN, decide(newOnlyFirst = true, charging = true, batteryPct = low))
        // presne na hranici ešte beží
        val edge = ProcessingPolicy.LOW_BATTERY_PCT
        assertEquals(Decision.RUN, decide(newOnlyFirst = true, batteryPct = edge, batchSize = 1))
    }

    @Test
    fun newPhotosFirstRunsSmallBatchAndDefersLarge() {
        val small = ProcessingPolicy.SMALL_BATCH_MAX
        assertEquals(Decision.RUN, decide(newOnlyFirst = true, batchSize = 0))
        assertEquals(Decision.RUN, decide(newOnlyFirst = true, batchSize = small))
        assertEquals(Decision.WAIT_FOR_CHARGING, decide(newOnlyFirst = true, batchSize = small + 1))
        // neznáma dávka sa berie ako veľká
        assertEquals(Decision.WAIT_FOR_CHARGING, decide(newOnlyFirst = true, batchSize = ProcessingPolicy.UNKNOWN))
        // pri nabíjaní beží aj veľká dávka
        assertEquals(Decision.RUN, decide(newOnlyFirst = true, charging = true, batchSize = small + 1))
    }

    @Test
    fun unknownBatteryDoesNotStop() {
        assertEquals(
            Decision.RUN,
            decide(newOnlyFirst = true, batteryPct = ProcessingPolicy.UNKNOWN, batchSize = 1),
        )
    }

    @Test
    fun manualStartIgnoresBatteryAndBatch() {
        assertEquals(
            Decision.RUN,
            decide(newOnlyFirst = true, batteryPct = 1, batchSize = 10_000, trigger = Trigger.MANUAL),
        )
    }

    @Test
    fun helpersMatchRules() {
        assertTrue(ProcessingPolicy.needsBatchSize(Profile.FULL, true, false, Trigger.AUTO))
        assertFalse(ProcessingPolicy.needsBatchSize(Profile.FULL, false, false, Trigger.AUTO))
        assertFalse(ProcessingPolicy.needsBatchSize(Profile.FULL, true, true, Trigger.AUTO))
        assertFalse(ProcessingPolicy.needsBatchSize(Profile.LIGHT, true, false, Trigger.AUTO))
        assertFalse(ProcessingPolicy.needsBatchSize(Profile.FULL, true, false, Trigger.MANUAL))
        assertTrue(ProcessingPolicy.needsWatching(Profile.LIGHT, false))
        assertTrue(ProcessingPolicy.needsWatching(Profile.FULL, true))
        assertFalse(ProcessingPolicy.needsWatching(Profile.FULL, false))
        assertEquals(Profile.LIGHT, ProcessingPolicy.profileOf(ProcessingPolicy.PROFILE_LIGHT))
        assertEquals(Profile.FULL, ProcessingPolicy.profileOf(ProcessingPolicy.PROFILE_FULL))
        assertEquals(Profile.FULL, ProcessingPolicy.profileOf(42))
    }
}
