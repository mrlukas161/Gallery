package org.fossify.gallery.faces

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

// Samostatná databáza pre tváre (faces.db) — úplne oddelená od gallery.db, aby žiadna
// zmena/chyba schémy tvárí nemohla zasiahnuť cache fotiek v hlavnej galérii.
// ⛔ ŽIADNY fallbackToDestructiveMigration: id tvárí sú odkazované z people.db (face_assignments,
// cannot_links) a extras.db (ignored_faces) — zmazanie faces.db = stratené menovky, a nové
// autoincrement id by sa navyše prilepili na cudzie tváre. Pri zmene entít zvýš `version`
// A pridaj Migration, pozri PeopleDatabase.
@Database(entities = [FaceEntity::class, IndexedPhotoEntity::class], version = 2)
abstract class FacesDatabase : RoomDatabase() {
    abstract fun FaceDao(): FaceDao

    companion object {
        private var db: FacesDatabase? = null

        fun getInstance(context: Context): FacesDatabase {
            if (db == null) {
                synchronized(FacesDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, FacesDatabase::class.java, "faces.db")
                            .build()
                    }
                }
            }
            return db!!
        }

        // Poistka proti „posunu id": face_id v people.db a extras.db je len hodnota bez DB-FK. Keby
        // faces.db niekedy vznikla nanovo (napr. poškodený súbor SQLite zmaže a vytvorí prázdny),
        // autoincrement by začal od 1 a staré menovky by sa potichu prilepili na CUDZIE nové tváre.
        // Pred zápisom nových tvárí sa preto počítadlo id posunie nad najvyššie odkazované id.
        // V bežnom stave je to no-op (počítadlo je už vyššie). Vráti false, keď sa odkazy nedajú
        // prečítať — volajúci vtedy nové tváre NEZAPISUJE.
        fun ensureIdsAboveReferences(context: Context): Boolean {
            val maxRef = try {
                val people = PeopleDatabase.getInstance(context).openHelper.readableDatabase
                val extras = ExtrasDatabase.getInstance(context).openHelper.readableDatabase
                maxOf(
                    queryLong(people, "SELECT MAX(face_id) FROM face_assignments"),
                    queryLong(people, "SELECT MAX(face_id) FROM cannot_links"),
                    queryLong(extras, "SELECT MAX(face_id) FROM ignored_faces"),
                )
            } catch (e: Throwable) {
                return false
            }
            if (maxRef <= 0L) return true
            return try {
                val sql = getInstance(context).openHelper.writableDatabase
                val maxId = queryLong(sql, "SELECT MAX(id) FROM faces")
                val seq = queryLong(sql, "SELECT seq FROM sqlite_sequence WHERE name = 'faces'")
                if (maxOf(maxId, seq) < maxRef) {
                    // sqlite_sequence smie aplikácia meniť bežným UPDATE/INSERT (dokumentované v SQLite)
                    if (queryLong(sql, "SELECT COUNT(*) FROM sqlite_sequence WHERE name = 'faces'") > 0L) {
                        sql.execSQL("UPDATE sqlite_sequence SET seq = ? WHERE name = 'faces'", arrayOf<Any>(maxRef))
                    } else {
                        sql.execSQL("INSERT INTO sqlite_sequence(name, seq) VALUES('faces', ?)", arrayOf<Any>(maxRef))
                    }
                }
                true
            } catch (e: Throwable) {
                false
            }
        }

        private fun queryLong(sql: SupportSQLiteDatabase, query: String): Long =
            sql.query(query).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
    }
}
