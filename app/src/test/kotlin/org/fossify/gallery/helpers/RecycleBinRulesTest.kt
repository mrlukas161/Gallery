package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecycleBinRulesTest {
    private val prefix = "recycle_bin"
    private val appData = listOf("clip", "clip_ml", "tessdata", "picasa_crops", "datastore", "profileInstalled")

    private fun plan(deleted: List<String>, top: List<String>) =
        RecycleBinRules.plan(deleted, top, prefix).associate { it.relative to it.recursive }

    @Test
    fun appDataInFilesDirIsNeverDeleted() {
        val result = plan(
            listOf("recycle_bin/storage/emulated/0/DCIM/a.jpg"),
            appData + listOf("storage", ".tmp_IMG_1.jpg"),
        )
        appData.forEach { name ->
            assertFalse("$name sa nesmie mazať", result.keys.any { it == name || it.startsWith("$name/") })
        }
        assertEquals(mapOf("storage" to true, ".tmp_IMG_1.jpg" to false), result)
    }

    @Test
    fun storageRootsAreSweptIncludingOrphans() {
        val result = plan(emptyList(), listOf("storage", "sdcard", "mnt", "clip"))
        assertEquals(mapOf("mnt" to true, "sdcard" to true, "storage" to true), result)
    }

    @Test
    fun entriesOutsideStorageRootsAreDeletedOneByOne() {
        val result = plan(listOf("recycle_bin/otg/0000-1111/DCIM/b.jpg"), listOf("otg", "clip"))
        assertEquals(mapOf("otg/0000-1111/DCIM/b.jpg" to false), result)
    }

    @Test
    fun entriesUnderSweptRootAreNotDuplicated() {
        val deleted = listOf("recycle_bin/storage/emulated/0/a.jpg", "recycle_bin/storage/x/b.jpg")
        val result = plan(deleted, listOf("storage"))
        assertEquals(mapOf("storage" to true), result)
    }

    @Test
    fun storageEntryWithoutTopLevelSweepIsRecursive() {
        // binovaný priečinok (copyRecursively) pod koreňom úložiska, ktorý filesDir.list() nevrátil
        val result = plan(listOf("recycle_bin/storage/emulated/0/Folder"), emptyList())
        assertEquals(mapOf("storage/emulated/0/Folder" to true), result)
    }

    @Test
    fun suspiciousEntriesAreRejected() {
        assertNull(RecycleBinRules.entryOf("recycle_bin/storage/../clip/model.onnx", prefix))
        assertNull(RecycleBinRules.entryOf("recycle_bin/clip/model.onnx", prefix))
        assertNull(RecycleBinRules.entryOf("recycle_bin/tessdata/slk.traineddata", prefix))
        assertNull(RecycleBinRules.entryOf("recycle_bin/picasa_crops/1.jpg", prefix))
        assertNull(RecycleBinRules.entryOf("recycle_bin/a.jpg", prefix)) // len 1 úroveň = priamo v koreni
        assertNull(RecycleBinRules.entryOf("recycle_bin", prefix))
        assertNull(RecycleBinRules.entryOf("/storage/emulated/0/a.jpg", prefix)) // nie je v Koši
        assertNull(RecycleBinRules.entryOf("recycle_bin/storage/./a.jpg", prefix))
        val doubled = RecycleBinRules.entryOf("recycle_bin//storage/emulated/0/a.jpg", prefix)
        assertEquals("storage/emulated/0/a.jpg", doubled)
    }

    @Test
    fun tmpFilesOnlyWithName() {
        val result = plan(emptyList(), listOf(".tmp_", ".tmp_x.jpg", "tmp_y.jpg"))
        assertEquals(mapOf(".tmp_x.jpg" to false), result)
    }

    @Test
    fun emptyBinWithOnlyAppDataDeletesNothing() {
        assertTrue(plan(emptyList(), appData).isEmpty())
    }

    @Test
    fun insideBinCheck() {
        val root = "/data/user/0/org.fossify.gallery/files"
        assertTrue(RecycleBinRules.isInsideBin(root, "$root/storage/emulated/0/a.jpg"))
        assertTrue(RecycleBinRules.isInsideBin("$root/", "$root/storage"))
        assertFalse(RecycleBinRules.isInsideBin(root, root))
        assertFalse(RecycleBinRules.isInsideBin(root, "$root/clip/model.onnx"))
        assertFalse(RecycleBinRules.isInsideBin(root, "$root/picasa_crops"))
        assertFalse(RecycleBinRules.isInsideBin(root, "/data/user/0/org.fossify.gallery/files2/x"))
        assertFalse(RecycleBinRules.isInsideBin(root, "/storage/emulated/0/a.jpg"))
        assertFalse(RecycleBinRules.isInsideBin("", "/x"))
    }
}
