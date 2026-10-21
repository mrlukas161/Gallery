package org.fossify.gallery.faces

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.fossify.gallery.safety.SafeOpenHelperFactory

// Samostatná databáza pre "extra" metadáta (ignorované tváre + skupiny osôb).
// Oddelená od people.db, aby pridanie týchto funkcií NIKDY nevynútilo reset labelov/anchorov.
// ⛔ ŽIADNY fallbackToDestructiveMigration (skupiny osôb a odložené tváre by zmizli potichu) —
// pri zmene entít zvýš `version` A pridaj Migration, pozri PeopleDatabase.
@Database(
    entities = [IgnoredFaceEntity::class, PersonGroupEntity::class, GroupMemberEntity::class],
    version = ExtrasDatabase.SCHEMA_VERSION,
)
abstract class ExtrasDatabase : RoomDatabase() {
    abstract fun ExtrasDao(): ExtrasDao

    companion object {
        // verzia schémy (= @Database.version); DbVersionGuard podľa nej spozná DB z novšej appky
        const val SCHEMA_VERSION = 1

        private var db: ExtrasDatabase? = null

        fun getInstance(context: Context): ExtrasDatabase {
            if (db == null) {
                synchronized(ExtrasDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, ExtrasDatabase::class.java, "extras.db")
                            // A1: poškodený súbor sa premenuje, nezmaže (inak by SQLite vytvoril prázdnu DB)
                            .openHelperFactory(SafeOpenHelperFactory())
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
