package org.fossify.gallery.faces

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Samostatná databáza pre POMENOVANÉ osoby a korekcie (people.db). Oddelená od faces.db,
// aby pridanie/zmena tejto schémy NIKDY nevynútila reindex drahých embeddingov v faces.db.
@Database(
    entities = [
        PersonEntity::class,
        FaceAssignmentEntity::class,
        CannotLinkEntity::class,
        AnchorEmbeddingEntity::class,
        ImportManifestEntity::class,
    ],
    version = 2,
)
abstract class PeopleDatabase : RoomDatabase() {
    abstract fun PeopleDao(): PeopleDao

    companion object {
        private var db: PeopleDatabase? = null

        fun getInstance(context: Context): PeopleDatabase {
            if (db == null) {
                synchronized(PeopleDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, PeopleDatabase::class.java, "people.db")
                            .fallbackToDestructiveMigration()
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
