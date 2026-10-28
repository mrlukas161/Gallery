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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// E2 — migrácia people.db v2 → v3 nad skutočnými riadkami (osoby, priradenia, „nie je“, vzory, manifest):
// každý pôvodný riadok ostane, nové stĺpce priradení sú NULL, merge_log vznikne prázdny. Navyše na migrovanej
// DB cez Room: zlúčenie so záznamom a jeho vrátenie (osoba, tváre, „nie je“ a vzory sa vrátia presne).
// extras.db (skupiny) sa nemigruje — test overí, že jej v1 schéma a riadky ostávajú nedotknuté.
@RunWith(AndroidJUnit4::class)
class PeopleMigrationTest {
    private val name = "migration-test-people.db"
    private val extrasName = "migration-test-extras.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PeopleDatabase::class.java)

    @get:Rule
    val extrasHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ExtrasDatabase::class.java)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        context.deleteDatabase(name)
        context.deleteDatabase(extrasName)
    }

    private fun createV2() {
        helper.createDatabase(name, 2).use { db ->
            db.insert("persons", SQLiteDatabase.CONFLICT_ABORT, person(3, "Ján", 1000))
            db.insert("persons", SQLiteDatabase.CONFLICT_ABORT, person(8, null, 2000))
            db.insert("persons", SQLiteDatabase.CONFLICT_ABORT, person(9, "Janko", 3000))
            db.insert("face_assignments", SQLiteDatabase.CONFLICT_ABORT, assign(5, 3, true, 11))
            db.insert("face_assignments", SQLiteDatabase.CONFLICT_ABORT, assign(17, 3, false, 12))
            db.insert("face_assignments", SQLiteDatabase.CONFLICT_ABORT, assign(42, 9, true, 13))
            db.insert("face_assignments", SQLiteDatabase.CONFLICT_ABORT, assign(43, 9, true, 14))
            db.insert("cannot_links", SQLiteDatabase.CONFLICT_ABORT, link(17, 8))
            db.insert("cannot_links", SQLiteDatabase.CONFLICT_ABORT, link(99, 9))
            db.insert("cannot_links", SQLiteDatabase.CONFLICT_ABORT, link(43, 3))
            db.insert("anchor_embeddings", SQLiteDatabase.CONFLICT_ABORT, anchor(1, 3, byteArrayOf(1, 2), "picasa"))
            db.insert("anchor_embeddings", SQLiteDatabase.CONFLICT_ABORT, anchor(2, 9, byteArrayOf(3, 4), "family:x"))
            db.insert("import_manifests", SQLiteDatabase.CONFLICT_ABORT, manifest("abc", 77, 2))
        }
    }

    @Test
    fun migrate2To3KeepsEveryRow() {
        createV2()
        helper.runMigrationsAndValidate(name, 3, true, PeopleDatabase.MIGRATION_2_3).use { db ->
            assertEquals(listOf("3|Ján|1000", "8|null|2000", "9|Janko|3000"), rows(db, "SELECT id, name, created_at FROM persons ORDER BY id"))
            assertEquals(
                listOf("5|3|1|11|null|null", "17|3|0|12|null|null", "42|9|1|13|null|null", "43|9|1|14|null|null"),
                rows(db, "SELECT face_id, person_id, is_manual, updated_at, source, confidence FROM face_assignments ORDER BY face_id"),
            )
            assertEquals(
                listOf("17|8", "43|3", "99|9"),
                rows(db, "SELECT face_id, person_id FROM cannot_links ORDER BY face_id"),
            )
            assertEquals(
                listOf("1|3|picasa", "2|9|family:x"),
                rows(db, "SELECT id, person_id, source FROM anchor_embeddings ORDER BY id"),
            )
            db.query("SELECT embedding FROM anchor_embeddings WHERE id = 2").use { c ->
                assertTrue(c.moveToFirst())
                assertArrayEquals(byteArrayOf(3, 4), c.getBlob(0))
            }
            assertEquals(listOf("abc|77|2"), rows(db, "SELECT manifest_hash, imported_at, person_count FROM import_manifests"))
            assertEquals(emptyList<String>(), rows(db, "SELECT id FROM merge_log"))
        }
    }

    @Test
    fun mergeAndUndoOnMigratedDatabase() {
        createV2()
        helper.runMigrationsAndValidate(name, 3, true, PeopleDatabase.MIGRATION_2_3).close()
        val room = Room.databaseBuilder(context, PeopleDatabase::class.java, name)
            .addMigrations(PeopleDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = room.PeopleDao()
            val groups = MergeLogCodec.encodeGroups(MergeLogCodec.Groups(listOf(4L), listOf(4L)))
            // Janko (9) do Jána (3)
            dao.mergeManyInto(listOf(9L), 3L, 5000L, mapOf(9L to groups))
            assertNull(dao.getPerson(9))
            assertEquals(setOf(5L, 17L, 42L, 43L), dao.getAssignmentsForPerson(3).map { it.faceId }.toSet())
            // „43 nie je Ján“ si po zlúčení protirečí s priradením → zmazané; „99 nie je Janko“ → na Jána
            assertEquals(setOf(99L), dao.getCannotLinkFaces(3).toSet())
            val log = dao.getRecentMerges(10).single()
            assertEquals(9L, log.fromPersonId)
            assertEquals("Janko", log.fromName)

            val outcome = dao.undoMerge(log.id, 6000L)
            assertTrue(outcome is MergeUndo.Outcome.Ready)
            val back = dao.getPerson(9)
            assertNotNull(back)
            assertEquals("Janko", back?.name)
            assertEquals(3000L, back?.createdAt)
            assertEquals(setOf(42L, 43L), dao.getAssignmentsForPerson(9).map { it.faceId }.toSet())
            assertEquals(setOf(5L, 17L), dao.getAssignmentsForPerson(3).map { it.faceId }.toSet())
            assertEquals(setOf(99L), dao.getCannotLinkFaces(9).toSet())
            assertEquals(setOf(43L), dao.getCannotLinkFaces(3).toSet())
            assertEquals(9L, dao.getAllAnchors().single { it.id == 2L }.personId)
            assertTrue(dao.getRecentMerges(10).isEmpty())
            // druhé vrátenie toho istého zlúčenia nič neurobí
            assertFalse(dao.undoMerge(log.id, 7000L) is MergeUndo.Outcome.Ready)
        } finally {
            room.close()
        }
    }

    @Test
    fun extrasGroupsUntouched() {
        extrasHelper.createDatabase(extrasName, ExtrasDatabase.SCHEMA_VERSION).use { db ->
            db.insert("person_groups", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 4L)
                put("name", "Rodina")
                put("created_at", 10L)
            })
            db.insert("group_members", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("group_id", 4L)
                put("person_id", 3L)
            })
            db.insert("ignored_faces", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply { put("face_id", 17L) })
        }
        extrasHelper.runMigrationsAndValidate(extrasName, ExtrasDatabase.SCHEMA_VERSION, true).use { db ->
            assertEquals(listOf("4|Rodina|10"), rows(db, "SELECT id, name, created_at FROM person_groups"))
            assertEquals(listOf("4|3"), rows(db, "SELECT group_id, person_id FROM group_members"))
            assertEquals(listOf("17"), rows(db, "SELECT face_id FROM ignored_faces"))
        }
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<String> = db.query(sql).use { c ->
        val out = ArrayList<String>()
        while (c.moveToNext()) {
            out.add((0 until c.columnCount).joinToString("|") { i -> if (c.isNull(i)) "null" else c.getString(i) })
        }
        out
    }

    private fun person(id: Long, name: String?, createdAt: Long) = ContentValues().apply {
        put("id", id)
        if (name == null) putNull("name") else put("name", name)
        put("created_at", createdAt)
    }

    private fun assign(face: Long, person: Long, manual: Boolean, at: Long) = ContentValues().apply {
        put("face_id", face)
        put("person_id", person)
        put("is_manual", if (manual) 1 else 0)
        put("updated_at", at)
    }

    private fun link(face: Long, person: Long) = ContentValues().apply {
        put("face_id", face)
        put("person_id", person)
    }

    private fun anchor(id: Long, person: Long, embedding: ByteArray, source: String) = ContentValues().apply {
        put("id", id)
        put("person_id", person)
        put("embedding", embedding)
        put("source", source)
        put("created_at", 1L)
    }

    private fun manifest(hash: String, at: Long, count: Int) = ContentValues().apply {
        put("manifest_hash", hash)
        put("imported_at", at)
        put("person_count", count)
    }
}
