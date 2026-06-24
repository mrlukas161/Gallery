package org.fossify.gallery.faces

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Samostatná databáza pre OCR text (ocr.db) — oddelená od faces.db aj people.db.
@Database(entities = [OcrEntity::class], version = 1)
abstract class OcrDatabase : RoomDatabase() {
    abstract fun OcrDao(): OcrDao

    companion object {
        private var db: OcrDatabase? = null

        fun getInstance(context: Context): OcrDatabase {
            if (db == null) {
                synchronized(OcrDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, OcrDatabase::class.java, "ocr.db")
                            .fallbackToDestructiveMigration()
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
