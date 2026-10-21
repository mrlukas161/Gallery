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
//     zápisov) — len na núdzovú obnovu po poškodení DB (A1, DbRestore).
// Potom rotácia: ponechajú sa 4 najnovšie z každého druhu. Blokuje → len z pozadia.
object AutoBackup {
    private const val TAG = "AutoBackup"
    private const val PREFS = "galeria_auto_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_LAST_OMITTED = "last_omitted"
    private const val KEY_LAST_PERSONS = "last_persons"
    private const val KEY_LAST_FAILED_AT = "last_failed_at"
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
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(context: Context): Status {
        val p = prefs(context)
        return Status(
            p.getBoolean(KEY_ENABLED, true),
            p.getLong(KEY_LAST_AT, 0L),
            p.getInt(KEY_LAST_PERSONS, 0),
            p.getInt(KEY_LAST_OMITTED, 0),
            p.getLong(KEY_LAST_FAILED_AT, 0L),
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
    @Synchronized
    @Throws(IOException::class)
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun run(context: Context): PeopleBackup.Summary {
        val app = context.applicationContext
        if (DbVersionGuard.hasNewerData(app)) throw IOException("dáta sú z novšej verzie appky")
        val now = System.currentTimeMillis()
        val stamp = AutoBackupNames.stamp(now)
        val store = AutoBackupStore(app)
        try {
            val summary = store.write(AutoBackupNames.f6Name(stamp), PeopleBackupFormat.MIME_TYPE) {
                PeopleBackup(app).write(it)
            }
            writeDatabases(app, store, AutoBackupNames.dbName(stamp))
            rotate(store)
            prefs(app).edit()
                .putLong(KEY_LAST_AT, now)
                .putInt(KEY_LAST_PERSONS, summary.persons)
                .putInt(KEY_LAST_OMITTED, summary.omitted)
                .apply()
            return summary
        } catch (e: IOException) {
            prefs(app).edit().putLong(KEY_LAST_FAILED_AT, now).apply()
            throw e
        } catch (e: RuntimeException) {
            prefs(app).edit().putLong(KEY_LAST_FAILED_AT, now).apply()
            throw IOException(e)
        }
    }

    private fun writeDatabases(app: Context, store: AutoBackupStore, name: String) {
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

    private fun rotate(store: AutoBackupStore) {
        val items = store.list()
        val drop = AutoBackupNames.toRotate(items.map { it.name }).toSet()
        items.filter { it.name in drop }.forEach {
            if (!store.delete(it)) Log.i(TAG, "staršia záloha ${it.name} ostáva")
        }
    }

    private const val LEGACY_ATTEMPTS = 3
}
