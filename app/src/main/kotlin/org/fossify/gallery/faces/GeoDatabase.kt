package org.fossify.gallery.faces

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Samostatná databáza pre polohy fotiek (geo.db).
@Database(entities = [GeoEntity::class], version = 1)
abstract class GeoDatabase : RoomDatabase() {
    abstract fun GeoDao(): GeoDao

    companion object {
        private var db: GeoDatabase? = null

        fun getInstance(context: Context): GeoDatabase {
            if (db == null) {
                synchronized(GeoDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, GeoDatabase::class.java, "geo.db")
                            .fallbackToDestructiveMigration()
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
