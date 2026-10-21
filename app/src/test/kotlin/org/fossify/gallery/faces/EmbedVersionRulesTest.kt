package org.fossify.gallery.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedVersionRulesTest {
    private fun tally(total: Int, done: Int, missing: Int = 0, exhausted: Int = 0, transient: Int = 0) =
        EmbedVersionRules.Tally(total, done, missing, exhausted, transient)

    @Test
    fun differentVersionsAreNeverCompared() {
        val v1 = EmbedVersionRules.MODEL_ID_V1
        val v2 = EmbedVersionRules.MODEL_ID_V2
        assertEquals("mobilefacenet-112-l2-v2", v2)
        assertFalse(EmbedVersionRules.compatible(v1, v2))
        assertFalse(EmbedVersionRules.compatible(v2, v1))
        assertFalse(EmbedVersionRules.compatible(null, v2))
        assertFalse(EmbedVersionRules.compatible(null, null))
        assertTrue(EmbedVersionRules.compatible(v2, v2))
        assertTrue(EmbedVersionRules.compatible(v1, v1))
    }

    @Test
    fun anchorsBeforeSwitchAreV1() {
        val at = 1_000_000L
        assertTrue(EmbedVersionRules.isAnchorV1(at - 1, at))
        assertFalse(EmbedVersionRules.isAnchorV1(at, at))
        assertFalse(EmbedVersionRules.isAnchorV1(at + 5, at))
    }

    @Test
    fun oldPicasaManifestDoesNotBlockReimport() {
        assertTrue(EmbedVersionRules.manifestStillValid(10L, null)) // ešte v1 → platí
        assertFalse(EmbedVersionRules.manifestStillValid(10L, 20L)) // import v1 pred prepnutím
        assertTrue(EmbedVersionRules.manifestStillValid(30L, 20L)) // import v2
    }

    @Test
    fun switchOnlyWhenEveryFaceHasV2OrIsGone() {
        assertTrue(EmbedVersionRules.readyToSwitch(tally(0, 0)))
        assertTrue(EmbedVersionRules.readyToSwitch(tally(10, 10)))
        assertTrue(EmbedVersionRules.readyToSwitch(tally(10, 7, missing = 2, exhausted = 1)))
        // jedna tvár ešte bez výsledku → v1 a v2 by sa miešali, nečaká sa na ňu
        assertFalse(EmbedVersionRules.readyToSwitch(tally(10, 9)))
        // nepripojená karta → počkať
        assertFalse(EmbedVersionRules.readyToSwitch(tally(10, 8, transient = 2)))
        // podozrivo veľa chýbajúcich súborov (odobraté povolenie) → počkať
        assertFalse(EmbedVersionRules.readyToSwitch(tally(10, 4, missing = 6)))
        assertTrue(EmbedVersionRules.readyToSwitch(tally(10, 5, missing = 5)))
    }

    @Test
    fun volumeRoots() {
        assertEquals("/storage/emulated/0", EmbedVersionRules.volumeRoot("/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertEquals("/storage/1A2B-3C4D", EmbedVersionRules.volumeRoot("/storage/1A2B-3C4D/DCIM/a.jpg"))
        assertEquals("/sdcard", EmbedVersionRules.volumeRoot("/sdcard/DCIM/a.jpg"))
        assertEquals("/data", EmbedVersionRules.volumeRoot("/data/x.jpg"))
        assertNull(EmbedVersionRules.volumeRoot("/"))
    }
}
