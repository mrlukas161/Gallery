package org.fossify.gallery.faces

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Samostatná databáza pre "extra" metadáta (ignorované tváre + skupiny osôb).
// Oddelená od people.db, aby pridanie týchto funkcií NIKDY nevynútilo reset labelov/anchorov.
@Database(
    entities = [IgnoredFaceEntity::class, PersonGroupEntity::class, GroupMemberEntity::class],
    version = 1,
)
abstract class ExtrasDatabase : RoomDatabase() {
    abstract fun ExtrasDao(): ExtrasDao

    companion object {
        private var db: ExtrasDatabase? = null

        fun getInstance(context: Context): ExtrasDatabase {
            if (db == null) {
                synchronized(ExtrasDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, ExtrasDatabase::class.java, "extras.db")
                            .fallbackToDestructiveMigration()
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
