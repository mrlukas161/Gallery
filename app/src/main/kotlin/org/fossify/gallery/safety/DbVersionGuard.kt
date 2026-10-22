package org.fossify.gallery.safety

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.family.FamilyDatabase
import java.io.File

// A4: dáta z novšej verzie appky (napr. po inštalácii staršieho APK alebo obnove zálohy z novšej).
//
// Room pri DB s vyššou verziou, než pozná, vyhodí IllegalStateException („A migration from X to Y
// was required…“) až pri prvom prístupe, často na vlákne na pozadí → pád appky. Dáta sú pritom
// v poriadku a nesmú sa dotknúť (žiadny destruktívny fallback). Preto sa pri štarte prečíta
// user_version každej chránenej DB priamo cez SQLite (len na čítanie, bez Room a bez migrácie)
// a ak je vyššia, appka namiesto pádu ukáže NewerDataActivity.
//
// Pri otváraní sa použije prázdny DatabaseErrorHandler: predvolený by poškodený súbor ZMAZAL.
object DbVersionGuard {
    @Volatile
    private var cached: List<String>? = null

    // názov súboru → verzia schémy, ktorú pozná táto appka
    fun expected(): Map<String, Int> = mapOf(
        DbFiles.PEOPLE to PeopleDatabase.SCHEMA_VERSION,
        DbFiles.EXTRAS to ExtrasDatabase.SCHEMA_VERSION,
        DbFiles.FAMILY to FamilyDatabase.SCHEMA_VERSION,
        DbFiles.FACES to FacesDatabase.SCHEMA_VERSION,
    )

    // DB z novšej appky (výsledok sa pamätá do konca procesu); prázdny zoznam = všetko v poriadku
    fun newerDatabases(context: Context): List<String> {
        cached?.let { return it }
        val app = context.applicationContext
        val onDisk = expected().keys.associateWith { onDiskVersion(app.getDatabasePath(it)) }
        return DbFiles.newer(onDisk, expected()).also { cached = it }
    }

    fun hasNewerData(context: Context): Boolean = newerDatabases(context).isNotEmpty()

    // Room: IllegalStateException pri chýbajúcej migrácii alebo inej identite schémy
    fun isSchemaError(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < MAX_CAUSES) {
            val msg = cur.message.orEmpty()
            if (cur is IllegalStateException &&
                (msg.contains("migration", ignoreCase = true) || msg.contains("verify the data integrity"))
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    // user_version súboru, null = súbor neexistuje alebo sa nedá prečítať (to rieši A1)
    fun onDiskVersion(file: File): Int? {
        if (!file.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, NO_DELETE).use { it.version }
        } catch (ignored: SQLiteException) {
            null
        }
    }

    // nič nemaže ani nepremenúva — tu sa len číta verzia
    private val NO_DELETE = DatabaseErrorHandler { }

    private const val MAX_CAUSES = 6
}
