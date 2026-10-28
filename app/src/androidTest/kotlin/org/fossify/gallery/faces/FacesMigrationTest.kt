package org.fossify.gallery.faces

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// E1 — migrácia faces.db v2 → v3 nad skutočnými riadkami: každý pôvodný riadok a id ostanú, nové stĺpce majú
// predvolené hodnoty (NULL, box_space = 0), autoincrement pokračuje nad pôvodnými id a Room DB otvorí
// a prečíta cez DAO (validácia schémy proti app/schemas/…FacesDatabase/3.json).
@RunWith(AndroidJUnit4::class)
class FacesMigrationTest {
    private val name = "migration-test-faces.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FacesDatabase::class.java)

    private class Row(
        val id: Long,
        val path: String,
        val storeId: Long,
        val index: Int,
        val box: IntArray,
        val score: Float,
        val embedding: ByteArray?,
        val detectedAt: Long,
    )

    private val rows = listOf(
        Row(
            5, "/storage/emulated/0/DCIM/a.jpg", 101, 0, intArrayOf(10, 20, 110, 140), 0.93f,
            bytes(1, 2, 3, 4), 1_700_000_000_000,
        ),
        Row(17, "/storage/emulated/0/DCIM/a.jpg", 101, 1, intArrayOf(300, 40, 380, 150), 0.71f, null, 1_700_000_000_001),
        Row(
            42, "/storage/emulated/0/Pictures/b.jpg", 0, 0, intArrayOf(0, 0, 64, 64), 0.6f,
            bytes(9, 8, 7, 6, 5, 4, 3, 2), 1_789_000_000_000,
        ),
    )

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
    }

    @Test
    fun migrate2To3KeepsEveryRowAndId() {
        helper.createDatabase(name, 2).use { db ->
            rows.forEach { db.insert("faces", SQLiteDatabase.CONFLICT_ABORT, values(it)) }
            db.insert("indexed_photos", SQLiteDatabase.CONFLICT_ABORT, photo("/storage/emulated/0/DCIM/a.jpg", 2, 11L))
            db.insert("indexed_photos", SQLiteDatabase.CONFLICT_ABORT, photo("/storage/emulated/0/DCIM/empty.jpg", 0, 12L))
        }

        helper.runMigrationsAndValidate(name, 3, true, FacesDatabase.MIGRATION_2_3).use { db ->
            assertEquals(rows.size, count(db, "faces"))
            for (r in rows) assertRow(db, r)
            assertEquals(2, count(db, "indexed_photos"))
            val sql = "SELECT face_count, indexed_at FROM indexed_photos WHERE path = ?"
            db.query(sql, arrayOf("/storage/emulated/0/DCIM/a.jpg")).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(2, c.getInt(0))
                assertEquals(11L, c.getLong(1))
            }
            // nová tvár dostane id nad pôvodnými (AUTOINCREMENT/sqlite_sequence prežili migráciu)
            val newId = db.insert("faces", SQLiteDatabase.CONFLICT_ABORT, newFace())
            assertTrue("nové id $newId", newId > 42)
        }

        // Room otvorí migrovanú DB (validácia schémy v3) a DAO prečíta pôvodné riadky aj predvolené hodnoty
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, FacesDatabase::class.java, name)
            .addMigrations(FacesDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val faces = room.FaceDao().getEveryFace().associateBy { it.id }
            for (r in rows) {
                val f = faces[r.id] ?: error("tvár ${r.id} chýba")
                assertEquals(r.path, f.mediaFullPath)
                assertArrayEquals(r.box, intArrayOf(f.bboxLeft, f.bboxTop, f.bboxRight, f.bboxBottom))
                assertEquals(FaceBoxSpace.UNKNOWN, f.boxSpace)
                assertNull(f.quality)
                assertNull(f.takenAt)
                assertNull(f.embedVersion)
            }
            assertEquals(2, room.FaceDao().getFacesForPath("/storage/emulated/0/DCIM/a.jpg").size)
        } finally {
            room.close()
        }
    }

    private fun assertRow(db: SupportSQLiteDatabase, r: Row) {
        db.query(
            "SELECT media_full_path, media_store_id, face_index, bbox_left, bbox_top, bbox_right, bbox_bottom, score, " +
                "embedding, detected_at, quality, yaw, pitch, blur, align_method, box_space, embed_version, taken_at " +
                "FROM faces WHERE id = ?",
            arrayOf<Any>(r.id),
        ).use { c ->
            assertTrue("riadok ${r.id} chýba", c.moveToFirst())
            assertEquals(r.path, c.getString(0))
            assertEquals(r.storeId, c.getLong(1))
            assertEquals(r.index, c.getInt(2))
            assertArrayEquals(r.box, intArrayOf(c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6)))
            assertEquals(r.score, c.getFloat(7), 1e-6f)
            if (r.embedding == null) assertTrue(c.isNull(8)) else assertArrayEquals(r.embedding, c.getBlob(8))
            assertEquals(r.detectedAt, c.getLong(9))
            // nové stĺpce: NULL, box_space = 0 (neznámy priestor)
            for (i in intArrayOf(10, 11, 12, 13, 14, 16, 17)) assertTrue("stĺpec $i nie je NULL", c.isNull(i))
            assertEquals(0, c.getInt(15))
        }
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); c.getInt(0) }

    private fun values(r: Row) = ContentValues().apply {
        put("id", r.id)
        put("media_full_path", r.path)
        put("media_store_id", r.storeId)
        put("face_index", r.index)
        put("bbox_left", r.box[0])
        put("bbox_top", r.box[1])
        put("bbox_right", r.box[2])
        put("bbox_bottom", r.box[3])
        put("score", r.score)
        if (r.embedding == null) putNull("embedding") else put("embedding", r.embedding)
        put("detected_at", r.detectedAt)
    }

    private fun newFace() = ContentValues().apply {
        put("media_full_path", "/storage/emulated/0/DCIM/c.jpg")
        put("media_store_id", 7L)
        put("face_index", 0)
        put("bbox_left", 1)
        put("bbox_top", 2)
        put("bbox_right", 30)
        put("bbox_bottom", 40)
        put("score", 0.9f)
        put("detected_at", 1L)
        put("box_space", FaceBoxSpace.UPRIGHT_V2)
    }

    private fun photo(path: String, count: Int, at: Long) = ContentValues().apply {
        put("path", path)
        put("face_count", count)
        put("indexed_at", at)
    }

    private companion object {
        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    }
}
