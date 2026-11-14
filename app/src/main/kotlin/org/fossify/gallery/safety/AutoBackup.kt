package org.fossify.gallery.safety

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.RoomDatabase
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.family.FamilyDatabase
import org.fossify.gallery.family.PeopleBackup
import org.fossify.gallery.family.PeopleBackupFormat
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Automatická záloha osôb (A3): raz za týždeň pri nabíjaní (PeopleBackupJob) alebo ručne z Nastavení.
// Do Documents/Galeria+/zalohy/ (AutoBackupStore) zapíše:
//  1. F6 súbor .galeria-family (PeopleBackup) — bežná obnova cez existujúcu F6 obnovu (len dopĺňa);
//  2. ZIP s kópiami people.db, extras.db a family.db cez `VACUUM INTO` (konzistentný snímok aj počas
//     zápisov) — len na núdzovú obnovu po poškodení DB (A1, DbRestore). Na začiatku ZIP-u sú aj
//     metadáta zálohy (AutoBackupHealth.META_ENTRY) a obľúbené (FavoritesBackup.ENTRY).
// Potom rotácia: ponechajú sa 4 najnovšie z každého druhu a vždy posledná zdravá (AutoBackupHealth).
// Pri neuzavretom poškodení zálohovanej DB sa automatická záloha nerobí (N8). Blokuje → len z pozadia.
object AutoBackup {
    private const val TAG = "AutoBackup"
    private const val PREFS = "galeria_auto_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_LAST_OMITTED = "last_omitted"
    private const val KEY_LAST_PERSONS = "last_persons"
    private const val KEY_LAST_FAILED_AT = "last_failed_at"
    private const val KEY_LAST_SUSPECT = "last_suspect"
    private const val KEY_LAST_SKIPPED_AT = "last_skipped_at"
    private const val KEY_BASE_PERSONS = "baseline_persons"
    private const val KEY_BASE_ASSIGNMENTS = "baseline_assignments"
    private const val ZIP_MIME = "application/zip"
    private const val TMP_DIR = "auto-backup"

    // týždeň; job beží s periódou 7 dní, toto chráni pred zdvojením (napr. ručná záloha medzitým)
    const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    private const val DUE_SLACK_MS = 12L * 60 * 60 * 1000

    data class Status(
        val enabled: Boolean,
        val lastAt: Long,
        val lastPersons: Int,
        val lastOmitted: Int,
        val lastFailedAt: Long,
        // posledná záloha má výrazne menej osôb/priradení než posledná zdravá (tá sa zachová)
        val lastSuspect: Boolean = false,
        // automatická záloha sa vynechala pre neuzavreté poškodenie DB
        val lastSkippedAt: Long = 0L,
    )

    // automatická záloha sa nerobí, kým je databáza osôb poškodená (obnov ju alebo zvoľ „Nepýtať sa“)
    class SkippedException : IOException("databáza je poškodená, automatická záloha sa vynechala")

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(context: Context): Status {
        val p = prefs(context)
        return Status(
            p.getBoolean(KEY_ENABLED, true),
            p.getLong(KEY_LAST_AT, 0L),
            p.getInt(KEY_LAST_PERSONS, 0),
            p.getInt(KEY_LAST_OMITTED, 0),
            p.getLong(KEY_LAST_FAILED_AT, 0L),
            p.getBoolean(KEY_LAST_SUSPECT, false),
            p.getLong(KEY_LAST_SKIPPED_AT, 0L),
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val s = status(context)
        return s.enabled && now - s.lastAt >= INTERVAL_MS - DUE_SLACK_MS
    }

    // Vytvorí zálohu teraz. Výnimka = záloha sa nezapísala (staré ostávajú nedotknuté).
    // manual = Nastavenia → Zálohovať teraz (vedomé potvrdenie stavu, AutoBackupHealth.isHealthy).
    @Synchronized
    @Throws(IOException::class)
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun run(context: Context, manual: Boolean = false): PeopleBackup.Summary {
        val app = context.applicationContext
        if (DbVersionGuard.hasNewerData(app)) throw IOException("dáta sú z novšej verzie appky")
        val now = System.currentTimeMillis()
        val stamp = AutoBackupNames.stamp(now)
        val store = AutoBackupStore(app)
        try {
            // otvorenie DB odhalí poškodenie (SafeOpenHelperFactory ho zaznamená) ešte pred zápisom zálohy
            databases(app).forEach { (_, db) -> db.openHelper.writableDatabase }
            val corrupted = DbSafetyState(app).corruptions().map { it.dbName }
            if (AutoBackupHealth.shouldSkip(corrupted, manual)) {
                prefs(app).edit().putLong(KEY_LAST_SKIPPED_AT, now).apply()
                throw SkippedException()
            }
            val summary = store.write(AutoBackupNames.f6Name(stamp), PeopleBackupFormat.MIME_TYPE) {
                PeopleBackup(app).write(it)
            }
            val favorites = AutoBackupFavorites.paths(app)
            val p = prefs(app)
            // východisko = posledná zdravá záloha; pred zavedením metadát počet osôb poslednej zálohy
            val baseline = AutoBackupHealth.baseline(
                AutoBackupMeta.metas(app).values,
                p.getInt(KEY_BASE_PERSONS, p.getInt(KEY_LAST_PERSONS, 0)),
                p.getInt(KEY_BASE_ASSIGNMENTS, -1),
            )
            val isCorrupted = corrupted.any { it in DbFiles.BACKED_UP }
            val persons = summary.persons
            val assignments = summary.assignments
            val healthy = AutoBackupHealth.isHealthy(baseline, persons, assignments, manual, isCorrupted)
            val meta = AutoBackupHealth.Meta(stamp, persons, assignments, favorites.size, healthy)
            writeDatabases(app, store, AutoBackupNames.dbName(stamp), meta, favorites)
            AutoBackupMeta.save(app, meta)
            if (healthy) p.edit().putInt(KEY_BASE_PERSONS, persons).putInt(KEY_BASE_ASSIGNMENTS, assignments).apply()
            rotate(app, store)
            p.edit()
                .putLong(KEY_LAST_AT, now)
                .putInt(KEY_LAST_PERSONS, summary.persons)
                .putInt(KEY_LAST_OMITTED, summary.omitted)
                .putBoolean(KEY_LAST_SUSPECT, !healthy)
                .apply()
            return summary
        } catch (e: SkippedException) {
            throw e
        } catch (e: IOException) {
            prefs(app).edit().putLong(KEY_LAST_FAILED_AT, now).apply()
            throw e
        } catch (e: RuntimeException) {
            prefs(app).edit().putLong(KEY_LAST_FAILED_AT, now).apply()
            throw IOException(e)
        }
    }

    private fun writeDatabases(
        app: Context,
        store: AutoBackupStore,
        name: String,
        meta: AutoBackupHealth.Meta,
        favorites: List<String>,
    ) {
        val tmpDir = File(app.cacheDir, TMP_DIR)
        tmpDir.deleteRecursively() // len naše dočasné kópie z minulého behu
        if (!tmpDir.mkdirs()) throw IOException("dočasný priečinok sa nedá vytvoriť")
        try {
            val copies = databases(app).map { (fileName, db) ->
                val out = File(tmpDir, fileName)
                snapshot(db, app.getDatabasePath(fileName), out)
                fileName to out
            }
            store.write(name, ZIP_MIME) { stream ->
                val zip = ZipOutputStream(stream)
                // malé textové súbory na začiatku: zoznam záloh ich prečíta bez rozbaľovania databáz
                writeText(zip, AutoBackupHealth.META_ENTRY, AutoBackupHealth.encode(meta) + "\n")
                writeText(zip, FavoritesBackup.ENTRY, FavoritesBackup.encode(favorites))
                copies.forEach { (fileName, file) ->
                    zip.putNextEntry(ZipEntry(fileName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.finish()
                zip.flush()
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun writeText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun databases(app: Context): List<Pair<String, RoomDatabase>> = listOf(
        DbFiles.PEOPLE to PeopleDatabase.getInstance(app),
        DbFiles.EXTRAS to ExtrasDatabase.getInstance(app),
        DbFiles.FAMILY to FamilyDatabase.getInstance(app),
    )

    // Konzistentná kópia otvorenej DB. Android 11+ (SQLite ≥ 3.27): VACUUM INTO. Staršie: WAL sa
    // prepíše do hlavného súboru a súbor sa skopíruje počas zámku zápisu; keď medzitým pribudol
    // zápis do WAL, kópia sa zopakuje.
    private fun snapshot(db: RoomDatabase, source: File, out: File) {
        val sql = db.openHelper.writableDatabase
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sql.execSQL("VACUUM INTO '" + out.path.replace("'", "''") + "'")
            return
        }
        repeat(LEGACY_ATTEMPTS) {
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            sql.beginTransactionNonExclusive()
            try {
                val wal = File(source.path + "-wal")
                if (!wal.exists() || wal.length() == 0L) {
                    source.copyTo(out, overwrite = true)
                    return
                }
            } finally {
                sql.endTransaction()
            }
        }
        throw IOException("kópiu ${source.name} sa nepodarilo vytvoriť")
    }

    // posledná zdravá záloha sa nikdy nezmaže (AutoBackupHealth.toRotate); metadáta zmazaných záloh sa upracú
    private fun rotate(app: Context, store: AutoBackupStore) {
        val items = store.list()
        val metas = AutoBackupMeta.metas(app)
        val drop = AutoBackupHealth.toRotate(items.map { it.name }, metas).toSet()
        val deleted = HashSet<String>()
        items.filter { it.name in drop }.forEach {
            if (store.delete(it)) deleted.add(it.name) else Log.i(TAG, "staršia záloha ${it.name} ostáva")
        }
        // metadáta sa odoberú len zmazaným zálohám (zoznam nemusí vidieť všetky súbory)
        val kept = items.filter { it.name !in deleted }.mapNotNull { AutoBackupNames.stampOf(it.name) }.toSet()
        val gone = deleted.mapNotNull { AutoBackupNames.stampOf(it) }.filter { it !in kept }.toSet()
        AutoBackupMeta.forget(app, gone)
    }

    private const val LEGACY_ATTEMPTS = 3
}
