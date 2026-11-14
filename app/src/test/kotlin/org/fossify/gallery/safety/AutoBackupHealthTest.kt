package org.fossify.gallery.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// N8: po poškodení DB nesmie záloha vytlačiť dobré a obnova nesmie ponúknuť zálohu po poškodení.
// N13: obľúbené v zálohe.
class AutoBackupHealthTest {
    private fun f6(stamp: String) = AutoBackupNames.f6Name(stamp)
    private fun db(stamp: String) = AutoBackupNames.dbName(stamp)
    private fun meta(stamp: String, persons: Int, assignments: Int = persons * 10, healthy: Boolean = true) =
        AutoBackupHealth.Meta(stamp, persons, assignments, 0, healthy)

    @Test
    fun metaRoundTrip() {
        val m = AutoBackupHealth.Meta("20261101-031500", 25, 1200, 340, false)
        assertEquals(m, AutoBackupHealth.decode(AutoBackupHealth.encode(m)))
        assertEquals(m, AutoBackupHealth.decode(AutoBackupHealth.encode(m) + "\n"))
        assertNull(AutoBackupHealth.decode(""))
        assertNull(AutoBackupHealth.decode("x|1|2|3"))
        assertNull(AutoBackupHealth.decode("x|a|2|3|1"))
        assertNull(AutoBackupHealth.decode("x|1|2|3|2"))
    }

    @Test
    fun stampOfPairsF6AndZip() {
        assertEquals("20261101-031500", AutoBackupNames.stampOf(f6("20261101-031500")))
        assertEquals("20261101-031500", AutoBackupNames.stampOf(db("20261101-031500")))
        assertNull(AutoBackupNames.stampOf("galeria-osoby-rucna.galeria-family"))
        assertNull(AutoBackupNames.stampOf("galeria-databazy-auto-2026.zip"))
    }

    @Test
    fun skipWhileBackedUpDbIsCorrupted() {
        assertTrue(AutoBackupHealth.shouldSkip(listOf(DbFiles.PEOPLE), manual = false))
        assertTrue(AutoBackupHealth.shouldSkip(listOf(DbFiles.FACES, DbFiles.FAMILY), manual = false))
        // faces.db sa nezálohuje — jej poškodenie zálohu osôb nezastaví
        assertFalse(AutoBackupHealth.shouldSkip(listOf(DbFiles.FACES), manual = false))
        assertFalse(AutoBackupHealth.shouldSkip(emptyList(), manual = false))
        assertFalse(AutoBackupHealth.shouldSkip(listOf(DbFiles.PEOPLE), manual = true))
    }

    @Test
    fun significantDrop() {
        assertTrue(AutoBackupHealth.dropped(25, 0, AutoBackupHealth.MIN_PERSON_DROP))
        assertTrue(AutoBackupHealth.dropped(2, 0, AutoBackupHealth.MIN_PERSON_DROP))
        assertTrue(AutoBackupHealth.dropped(25, 10, AutoBackupHealth.MIN_PERSON_DROP))
        // zlúčenie pár osôb nie je pokles
        assertFalse(AutoBackupHealth.dropped(25, 23, AutoBackupHealth.MIN_PERSON_DROP))
        // malé čísla: 4 → 2 je síce 50 %, ale len o 2
        assertFalse(AutoBackupHealth.dropped(4, 2, AutoBackupHealth.MIN_PERSON_DROP))
        assertFalse(AutoBackupHealth.dropped(0, 0, AutoBackupHealth.MIN_PERSON_DROP))
        assertFalse(AutoBackupHealth.dropped(10, 30, AutoBackupHealth.MIN_PERSON_DROP))
        assertFalse(AutoBackupHealth.dropped(-1, 0, AutoBackupHealth.MIN_PERSON_DROP))
    }

    @Test
    fun suspectByPersonsOrAssignments() {
        val base = meta("a", 25, 1200)
        assertFalse(AutoBackupHealth.isSuspect(null, 0, 0))
        assertFalse(AutoBackupHealth.isSuspect(base, 26, 1250))
        assertTrue(AutoBackupHealth.isSuspect(base, 0, 0))
        assertTrue(AutoBackupHealth.isSuspect(base, 25, 300))
        // neznáme priradenia vo východisku (záloha spred tejto verzie)
        assertFalse(AutoBackupHealth.isSuspect(meta("", 25, -1), 25, 0))
    }

    @Test
    fun healthyRules() {
        val base = meta("a", 25, 1200)
        assertTrue(AutoBackupHealth.isHealthy(base, 25, 1200, manual = false, corrupted = false))
        assertFalse(AutoBackupHealth.isHealthy(base, 0, 0, manual = false, corrupted = false))
        // ručná záloha bez poškodenia = vedomé potvrdenie (napr. po upratovaní osôb)
        assertTrue(AutoBackupHealth.isHealthy(base, 5, 100, manual = true, corrupted = false))
        // ručná záloha počas poškodenia nikdy nie je zdravá
        assertFalse(AutoBackupHealth.isHealthy(base, 25, 1200, manual = true, corrupted = true))
    }

    @Test
    fun baselineIsNewestHealthy() {
        val metas = listOf(
            meta("20261101-000000", 20),
            meta("20261108-000000", 25),
            meta("20261115-000000", 0, healthy = false),
        )
        assertEquals("20261108-000000", AutoBackupHealth.baseline(metas, 0, -1)?.stamp)
        // bez metadát: uložené počty poslednej zdravej zálohy
        assertEquals(25, AutoBackupHealth.baseline(emptyList(), 25, -1)?.persons)
        assertEquals(25, AutoBackupHealth.baseline(listOf(meta("x", 0, healthy = false)), 25, 300)?.persons)
        assertNull(AutoBackupHealth.baseline(emptyList(), 0, -1))
    }

    @Test
    fun rotationNeverDropsLastHealthy() {
        // 1 zdravá + 4 podozrivé (prázdna DB po poškodení, týždeň po týždni)
        val stamps = listOf("01", "08", "15", "22", "29").map { "202610$it-000000" }
        val names = stamps.flatMap { listOf(f6(it), db(it)) }
        val metas = mapOf(stamps[0] to meta(stamps[0], 25)) +
            stamps.drop(1).associateWith { meta(it, 0, healthy = false) }
        val drop = AutoBackupHealth.toRotate(names, metas)
        assertTrue(drop.isEmpty())
        // ďalšia podozrivá: vypadne najstaršia podozrivá, zdravá ostane
        val next = "20261105-000000"
        val metas2 = metas + (next to meta(next, 0, healthy = false))
        val drop2 = AutoBackupHealth.toRotate(names + f6(next) + db(next), metas2)
        assertEquals(setOf(f6(stamps[1]), db(stamps[1])), drop2.toSet())
    }

    @Test
    fun rotationNormalWhenAllHealthy() {
        val stamps = (1..6).map { "202611%02d-000000".format(it) }
        val names = stamps.flatMap { listOf(f6(it), db(it)) }
        val metas = stamps.associateWith { meta(it, 25) }
        assertEquals(
            setOf(f6(stamps[0]), db(stamps[0]), f6(stamps[1]), db(stamps[1])),
            AutoBackupHealth.toRotate(names, metas).toSet(),
        )
    }

    @Test
    fun rotationProtectsNewestLegacyWithoutMeta() {
        // staré zálohy bez metadát + 4 podozrivé po aktualizácii
        val legacy = "20260901-000000"
        val suspect = (1..4).map { "202610%02d-000000".format(it) }
        val names = (listOf(legacy) + suspect).flatMap { listOf(f6(it), db(it)) } + f6("20260801-000000")
        val metas = suspect.associateWith { meta(it, 0, healthy = false) }
        val drop = AutoBackupHealth.toRotate(names, metas)
        assertEquals(listOf(f6("20260801-000000")), drop)
    }

    @Test
    fun candidatesOnlyBeforeCorruption() {
        val items = listOf(
            db("20261101-000000") to 1_000L,
            db("20261108-000000") to 2_000L,
            db("20261115-000000") to 3_000L,
        )
        val metas = mapOf(
            "20261101-000000" to meta("20261101-000000", 25),
            "20261108-000000" to meta("20261108-000000", 0, healthy = false),
        )
        val c = AutoBackupHealth.candidates(items, metas, corruptedAt = 2_500L)
        assertEquals(listOf(db("20261108-000000"), db("20261101-000000")), c.map { it.name })
        assertTrue(c[0].suspect)
        assertEquals(db("20261101-000000"), AutoBackupHealth.recommended(c)?.name)
        assertEquals(25, AutoBackupHealth.recommended(c)?.meta?.persons)
        // bez poškodenia všetky; bez metadát nie je podozrivá
        val all = AutoBackupHealth.candidates(items, emptyMap(), null)
        assertEquals(3, all.size)
        assertEquals(db("20261115-000000"), AutoBackupHealth.recommended(all)?.name)
        assertNull(AutoBackupHealth.recommended(AutoBackupHealth.candidates(items, metas, corruptedAt = 500L)))
    }

    @Test
    fun recommendedFallsBackToNewestWhenAllSuspect() {
        val items = listOf(db("20261101-000000") to 1_000L, db("20261108-000000") to 2_000L)
        val metas = mapOf(
            "20261101-000000" to meta("20261101-000000", 0, healthy = false),
            "20261108-000000" to meta("20261108-000000", 0, healthy = false),
        )
        val c = AutoBackupHealth.candidates(items, metas, null)
        assertEquals(db("20261108-000000"), AutoBackupHealth.recommended(c)?.name)
    }

    // --- N13: obľúbené ---

    @Test
    fun favoritesRoundTrip() {
        val paths = listOf("a.jpg", "b c.jpg", "A.jpg").map { "/storage/emulated/0/DCIM/$it" }
        val text = FavoritesBackup.encode(paths)
        assertEquals("/storage/emulated/0/DCIM/a.jpg\n/storage/emulated/0/DCIM/b c.jpg\n", text)
        assertEquals(paths.take(2), FavoritesBackup.decode(text))
        assertEquals("", FavoritesBackup.encode(emptyList()))
        assertTrue(FavoritesBackup.decode("").isEmpty())
        assertEquals(listOf("/x/y.jpg"), FavoritesBackup.decode("/x/y.jpg\r\nrelative.jpg\n\n"))
    }

    @Test
    fun favoritesMissingOnlyExistingAndNotPresent() {
        val backup = listOf("/a/1.jpg", "/a/2.jpg", "/a/3.jpg", "/a/gone.jpg")
        val current = listOf("/A/1.JPG")
        val exists = { p: String -> p != "/a/gone.jpg" }
        assertEquals(listOf("/a/2.jpg", "/a/3.jpg"), FavoritesBackup.missing(backup, current, exists))
        assertTrue(FavoritesBackup.missing(backup, backup, { true }).isEmpty())
    }
}
