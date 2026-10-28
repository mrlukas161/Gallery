package org.fossify.gallery.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaServerLogicTest {
    @Test fun zmena_poctu_je_zmena() {
        assertTrue(IndexDiff.changed(mapOf(1L to (10L to 5L)), mapOf(1L to (10L to 5L), 2L to (11L to 7L))))
    }

    @Test fun rovnaky_obsah_nie_je_zmena() {
        assertFalse(IndexDiff.changed(mapOf(1L to (10L to 5L)), mapOf(1L to (10L to 5L))))
    }

    @Test fun upravena_fotka_je_zmena() {
        assertTrue(IndexDiff.changed(mapOf(1L to (10L to 5L)), mapOf(1L to (12L to 5L))))
    }

    // since je generické (Item s android Uri sa na JVM nedá zostrojiť) -> overí sa na mtime selektore
    @Test fun since_bez_parametra_vrati_vsetko() {
        assertEquals(listOf(5L, 9L, 12L), IndexDiff.since(listOf(5L, 9L, 12L), null) { it })
    }

    @Test fun since_vrati_len_novsie_alebo_rovnake() {
        assertEquals(listOf(9L, 12L), IndexDiff.since(listOf(5L, 9L, 12L), 9L) { it })
    }
}
