package org.fossify.gallery.faces

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Samostatná databáza pre perceptuálne hashe (phash.db) — oddelená od ostatných.
@Database(entities = [PhashEntity::class], version = 1)
abstract class PhashDatabase : RoomDatabase() {
    abstract fun PhashDao(): PhashDao

    companion object {
        private var db: PhashDatabase? = null

        fun getInstance(context: Context): PhashDatabase {
            if (db == null) {
                synchronized(PhashDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, PhashDatabase::class.java, "phash.db")
                            .fallbackToDestructiveMigration()
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
