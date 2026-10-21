package org.fossify.gallery.safety

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.io.IOException

// A1: poškodená databáza sa NIKDY nemaže.
//
// Predvolený SupportSQLiteOpenHelper.Callback.onCorruption (rovnako ako DefaultDatabaseErrorHandler)
// poškodený súbor zmaže a SQLite vytvorí prázdny — všetky mená osôb by sa stratili bez možnosti
// obnovy. Táto továreň obalí Room callback (onCreate/onUpgrade/onOpen… idú bez zmeny ďalej) a len
// onCorruption nahradí: súbor sa premenuje na `<db>.corrupt-<čas>` (aj s -wal/-shm/-journal),
// zapíše sa záznam pre dialóg pri štarte (DbSafetyState) a SQLite potom otvorí novú prázdnu DB,
// takže appka nespadne. Ak premenovanie zlyhá, súbor ostane na mieste (radšej chyba pri otvorení
// než strata dát).
//
// Room 2.8: Builder.openHelperFactory(…) je stále podporovaný spôsob pre SupportSQLite (bez
// vlastného SQLiteDriver); vnútri sa použije rovnaký FrameworkSQLiteOpenHelperFactory ako predvolene.
class SafeOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val wrapped = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(RenamingCallback(configuration.context.applicationContext, configuration.callback))
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
            .build()
        return delegate.create(wrapped)
    }

    private class RenamingCallback(
        private val app: Context,
        private val base: SupportSQLiteOpenHelper.Callback,
    ) : SupportSQLiteOpenHelper.Callback(base.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = base.onConfigure(db)

        override fun onCreate(db: SupportSQLiteDatabase) = base.onCreate(db)

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            base.onUpgrade(db, oldVersion, newVersion)

        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            base.onDowngrade(db, oldVersion, newVersion)

        override fun onOpen(db: SupportSQLiteDatabase) = base.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            val mainPath = db.path
            Log.e(TAG, "poškodená databáza: $mainPath — premenujem ju, nemažem")
            var attached: List<String>? = null
            if (db.isOpen) {
                try {
                    attached = db.attachedDbs?.map { it.second }
                } catch (ignored: SQLiteException) {
                    // zoznam pripojených DB sa nedá prečítať — premenuje sa aspoň hlavná
                }
                try {
                    db.close()
                } catch (ignored: IOException) {
                }
            }
            val paths = (attached ?: listOfNotNull(mainPath))
                .filter { it.isNotBlank() && !it.equals(":memory:", ignoreCase = true) }
                .distinct()
            val now = System.currentTimeMillis()
            paths.forEach { path ->
                val aside = moveAside(path, DbFiles.CORRUPT, now)
                if (aside != null) DbSafetyState(app).recordCorruption(DbFiles.fileName(path), aside, now)
            }
        }
    }

    companion object {
        private const val TAG = "SafeOpenHelper"

        // `path` → `path.<druh>-<čas>` spolu so sprievodnými súbormi; vráti názov premenovaného
        // hlavného súboru, null = súbor neexistuje alebo sa nedal premenovať (ostal na mieste)
        fun moveAside(path: String, kind: String, now: Long): String? {
            val main = File(path)
            if (!main.exists()) return null
            val stamp = DbFiles.stamp(now)
            val target = File(DbFiles.aside(path, kind, stamp))
            if (!main.renameTo(target)) {
                Log.e(TAG, "súbor $path sa nepodarilo premenovať, ostáva na mieste")
                return null
            }
            DbFiles.SIDE_SUFFIXES.forEach { suffix ->
                val side = File(path + suffix)
                if (side.exists() && !side.renameTo(File(DbFiles.aside(path, kind, stamp, suffix)))) {
                    Log.w(TAG, "sprievodný súbor ${side.name} sa nepodarilo premenovať")
                }
            }
            return target.name
        }
    }
}
