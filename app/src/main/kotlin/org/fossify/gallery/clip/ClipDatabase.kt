package org.fossify.gallery.clip

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Samostatná databáza pre CLIP embeddingy (clip.db).
@Database(entities = [ClipEntity::class], version = 1)
abstract class ClipDatabase : RoomDatabase() {
    abstract fun ClipDao(): ClipDao

    companion object {
        private var db: ClipDatabase? = null

        fun getInstance(context: Context): ClipDatabase {
            if (db == null) {
                synchronized(ClipDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, ClipDatabase::class.java, "clip.db")
                            .fallbackToDestructiveMigration()
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
