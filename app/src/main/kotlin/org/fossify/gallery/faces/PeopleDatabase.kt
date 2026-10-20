package org.fossify.gallery.faces

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Samostatná databáza pre POMENOVANÉ osoby a korekcie (people.db). Oddelená od faces.db,
// aby pridanie/zmena tejto schémy NIKDY nevynútila reindex drahých embeddingov v faces.db.
//
// ⛔ ŽIADNY fallbackToDestructiveMigration — ručne pomenované osoby sú NENAHRADITEĽNÉ.
// Pri nezhode verzie (vyššia schéma bez Migration alebo inštalácia staršej appky) Room radšej
// vyhodí IllegalStateException („A migration from X to Y was required but not found") a dáta
// ostanú na disku nedotknuté; predtým ich potichu zmazal (stalo sa pri 0.7.0, verzia 1 -> 2).
// Pri KAŽDEJ zmene entít: zvýš `version` A pridaj .addMigrations(Migration(stará, nová) { … })
// s ALTER/CREATE TABLE (vzor: databases/GalleryDatabase.kt). To isté platí pre faces.db a extras.db.
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
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
