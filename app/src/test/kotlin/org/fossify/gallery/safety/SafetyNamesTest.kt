package org.fossify.gallery.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// A1/A3/A4: pomenovanie premenovaných DB, rozpoznanie DB z novšej verzie, názvy a rotácia
// automatických záloh.
class SafetyNamesTest {

    // --- A1: poškodená DB sa premenuje ---

    @Test
    fun corruptFileKeepsSqliteSidecarPairing() {
        val main = DbFiles.aside("/data/db/people.db", DbFiles.CORRUPT, "20261021-031500")
        val wal = DbFiles.aside("/data/db/people.db", DbFiles.CORRUPT, "20261021-031500", "-wal")
        assertEquals("/data/db/people.db.corrupt-20261021-031500", main)
        // SQLite hľadá WAL ako „<hlavný súbor>-wal“
        assertEquals("$main-wal", wal)
        assertEquals("people.db.corrupt-20261021-031500", DbFiles.fileName(main))
    }

    @Test
    fun moveAsideRenamesAndNeverDeletes() {
        val dir = Files.createTempDirectory("ta1").toFile()
        try {
            val db = File(dir, "people.db").apply { writeText("data") }
            File(dir, "people.db-wal").writeText("wal")
            val aside = SafeOpenHelperFactory.moveAside(db.path, DbFiles.CORRUPT, 0L)
            assertNotNull(aside)
            assertFalse(db.exists())
            assertEquals("data", File(dir, aside!!).readText())
            assertEquals("wal", File(dir, "$aside-wal").readText())
            assertNull(SafeOpenHelperFactory.moveAside(db.path, DbFiles.CORRUPT, 0L))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sqliteHeaderIsRecognized() {
        val good = "SQLite format 3\u0000rest".toByteArray(Charsets.US_ASCII)
        assertTrue(DbFiles.isSqliteHeader(good))
        assertFalse(DbFiles.isSqliteHeader("PK\u0003\u0004".toByteArray(Charsets.US_ASCII)))
        assertFalse(DbFiles.isSqliteHeader(ByteArray(0)))
    }

    // --- A4: DB z novšej verzie ---

    @Test
    fun newerVersionIsDetectedOnlyAboveExpected() {
        val expected = mapOf("people.db" to 2, "extras.db" to 1, "family.db" to 1, "faces.db" to 2)
        assertEquals(emptyList<String>(), DbFiles.newer(mapOf("people.db" to 2, "faces.db" to 1), expected))
        assertEquals(emptyList<String>(), DbFiles.newer(emptyMap(), expected))
        assertEquals(
            listOf("faces.db", "people.db"),
            DbFiles.newer(mapOf("people.db" to 3, "faces.db" to 5, "extras.db" to null), expected),
        )
    }

    // --- A3: názvy a rotácia ---

    @Test
    fun namesRoundTrip() {
        val stamp = "20261021-031500"
        val f6 = AutoBackupNames.parse(AutoBackupNames.f6Name(stamp))
        val db = AutoBackupNames.parse(AutoBackupNames.dbName(stamp))
        assertEquals(AutoBackupNames.Kind.F6, f6!!.kind)
        assertEquals(AutoBackupNames.Kind.DB, db!!.kind)
        assertEquals(f6.createdAt, db.createdAt)
        assertEquals(stamp, AutoBackupNames.stamp(f6.createdAt))
    }

    @Test
    fun foreignFilesAreIgnored() {
        assertNull(AutoBackupNames.parse("galeria-osoby-2026-10-21.galeria-family")) // ručná záloha
        assertNull(AutoBackupNames.parse("galeria-osoby-auto-2026.galeria-family"))
        assertNull(AutoBackupNames.parse("galeria-databazy-auto-20261341-031500.zip")) // neplatný dátum
        assertNull(AutoBackupNames.parse("galeria-databazy-auto-20261021-031500.zip.tmp"))
        assertNull(AutoBackupNames.parse("fotka.jpg"))
    }

    @Test
    fun rotationKeepsFourNewestOfEachKind() {
        val stamps = (1..6).map { "202610%02d-031500".format(it) }
        val names = stamps.flatMap { listOf(AutoBackupNames.f6Name(it), AutoBackupNames.dbName(it)) } +
            "galeria-osoby-2026-10-01.galeria-family"
        val drop = AutoBackupNames.toRotate(names)
        assertEquals(
            setOf(
                AutoBackupNames.f6Name(stamps[0]), AutoBackupNames.f6Name(stamps[1]),
                AutoBackupNames.dbName(stamps[0]), AutoBackupNames.dbName(stamps[1]),
            ),
            drop.toSet(),
        )
        val newest = AutoBackupNames.sorted(names, AutoBackupNames.Kind.F6).first()
        assertEquals(stamps[5], AutoBackupNames.stamp(newest.createdAt))
    }

    @Test
    fun fewBackupsAreNotRotated() {
        val names = listOf(AutoBackupNames.f6Name("20261021-031500"), AutoBackupNames.dbName("20261021-031500"))
        assertTrue(AutoBackupNames.toRotate(names).isEmpty())
    }
}
