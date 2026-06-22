package org.fossify.gallery.faces

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Samostatná databáza pre tváre (faces.db) — úplne oddelená od gallery.db, aby žiadna
// zmena/chyba schémy tvárí nemohla zasiahnuť cache fotiek v hlavnej galérii.
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
                            .fallbackToDestructiveMigration()
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
