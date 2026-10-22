package org.fossify.gallery.faces

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Malá samostatná DB faces_v2.db pre prepočet odtlačkov na v2 (bez zmeny schémy faces.db).
// Pre každú tvár (face_id z faces.db) drží stav prepočtu, hotový v2 vektor a zálohu pôvodného v1
// vektora (prepnutie ho vo faces.db prepíše; záloha umožní návrat bez nového výpočtu).
// Prerušený prepočet pokračuje od tvárí, ktoré tu ešte nie sú hotové. Nič sa odtiaľ nemaže.
// Obyčajný SQLiteOpenHelper (nie Room): žiadne entity ani migrácie navyše, dáta sa dajú kedykoľvek prestavať.
@Suppress("TooManyFunctions") // malé čítania/zápisy jednej tabuľky
class FaceV2Staging private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    class Entry(val state: Int, val tries: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE staged (" +
                "face_id INTEGER PRIMARY KEY, " +
                "state INTEGER NOT NULL, " +
                "tries INTEGER NOT NULL DEFAULT 0, " +
                "v2 BLOB, " +
                "v1 BLOB, " +
                "updated_at INTEGER NOT NULL)"
        )
    }

    // budúce verzie tabuľky len pridávajú stĺpce; nič sa nemaže
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // Hotové v2 vektory jednej fotky naraz (v1 = pôvodný vektor ako záloha, môže chýbať).
    fun putDone(items: List<Triple<Long, ByteArray, ByteArray?>>) {
        if (items.isEmpty()) return
        inTransaction { db, now ->
            for ((id, v2, v1) in items) upsert(db, id, EmbedVersionRules.STATE_DONE, v2, v1, now)
        }
    }

    // neúspešný pokus (STATE_MISSING / STATE_FAILED); hotový záznam sa nikdy neprepíše
    fun putFailed(ids: List<Pair<Long, ByteArray?>>, state: Int) {
        if (ids.isEmpty()) return
        inTransaction { db, now ->
            for ((id, v1) in ids) upsert(db, id, state, null, v1, now)
        }
    }

    private fun inTransaction(block: (SQLiteDatabase, Long) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db, System.currentTimeMillis())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // Zápis jednej tváre: počet pokusov +1, záloha v1 sa nikdy neprepíše (pri opakovanom zápise už môže
    // byť vo faces.db v2), neúspech neprepíše hotový záznam.
    private fun upsert(db: SQLiteDatabase, id: Long, state: Int, v2: ByteArray?, v1: ByteArray?, now: Long) {
        val old = entry(db, id)
        if (v2 == null && old?.state == EmbedVersionRules.STATE_DONE) return
        val cv = ContentValues().apply {
            put("face_id", id)
            put("state", state)
            put("tries", (old?.tries ?: 0) + 1)
            if (v2 != null) put("v2", v2)
            put("updated_at", now)
        }
        if (old != null) {
            db.update(TABLE, cv, "face_id = ?", arrayOf(id.toString()))
            return
        }
        if (v1 != null) cv.put("v1", v1)
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // záloha v1 vektora tváre, ktorá pri prepnutí ostane bez odtlačku a nebola v prepočte
    fun backupV1(id: Long, v1: ByteArray) {
        val db = writableDatabase
        if (entry(db, id) != null) return
        val cv = ContentValues().apply {
            put("face_id", id)
            put("state", EmbedVersionRules.STATE_FAILED)
            put("tries", 0)
            put("v1", v1)
            put("updated_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun entries(): Map<Long, Entry> {
        val out = HashMap<Long, Entry>()
        readableDatabase.rawQuery("SELECT face_id, state, tries FROM $TABLE", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = Entry(c.getInt(1), c.getInt(2))
        }
        return out
    }

    fun doneVectors(): Map<Long, ByteArray> {
        val out = HashMap<Long, ByteArray>()
        readableDatabase.rawQuery(
            "SELECT face_id, v2 FROM $TABLE WHERE state = ? AND v2 IS NOT NULL",
            arrayOf(EmbedVersionRules.STATE_DONE.toString()),
        ).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getBlob(1)
        }
        return out
    }

    fun doneCount(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE state = ?",
            arrayOf(EmbedVersionRules.STATE_DONE.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun entry(db: SQLiteDatabase, id: Long): Entry? =
        db.rawQuery("SELECT state, tries FROM $TABLE WHERE face_id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) Entry(c.getInt(0), c.getInt(1)) else null
        }

    companion object {
        private const val DB_NAME = "faces_v2.db"
        private const val DB_VERSION = 1
        private const val TABLE = "staged"

        @Volatile
        private var instance: FaceV2Staging? = null

        fun get(context: Context): FaceV2Staging =
            instance ?: synchronized(this) { instance ?: FaceV2Staging(context).also { instance = it } }
    }
}
