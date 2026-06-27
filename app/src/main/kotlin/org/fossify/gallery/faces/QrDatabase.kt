package org.fossify.gallery.faces

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Samostatná databáza pre QR/čiarové kódy (qr.db) — oddelená od ocr.db, faces.db aj people.db.
@Database(entities = [QrEntity::class], version = 1)
abstract class QrDatabase : RoomDatabase() {
    abstract fun QrDao(): QrDao

    companion object {
        private var db: QrDatabase? = null

        fun getInstance(context: Context): QrDatabase {
            if (db == null) {
                synchronized(QrDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, QrDatabase::class.java, "qr.db")
                            .fallbackToDestructiveMigration()
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
