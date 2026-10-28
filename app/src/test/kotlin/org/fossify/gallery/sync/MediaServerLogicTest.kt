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

    @Test fun unikatne_id_zachovaju_poradie() {
        assertEquals(listOf(7L, 3L, 9L), IndexDiff.uniqueIds(listOf(7L, 3L, 7L, 9L, 3L)))
    }

    @Test fun kluce_rozsahu_su_vylucene_zahrnute_a_zamknute_priecinky() {
        assertTrue(IndexDiff.isScopeKey("excluded_folders"))
        assertTrue(IndexDiff.isScopeKey("included_folders"))
        assertTrue(IndexDiff.isScopeKey("protected_folder_hash_/storage/emulated/0/DCIM/Sukromne"))
        assertTrue(IndexDiff.isScopeKey("protected_folder_type_/storage/emulated/0/DCIM/Sukromne"))
        assertFalse(IndexDiff.isScopeKey("use_recycle_bin"))
        assertFalse(IndexDiff.isScopeKey("sort_order"))
    }
}
