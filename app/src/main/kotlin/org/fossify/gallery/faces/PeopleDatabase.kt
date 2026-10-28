package org.fossify.gallery.faces

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.fossify.gallery.safety.SafeOpenHelperFactory

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
        MergeLogEntity::class,
    ],
    version = PeopleDatabase.SCHEMA_VERSION,
)
abstract class PeopleDatabase : RoomDatabase() {
    abstract fun PeopleDao(): PeopleDao

    companion object {
        // verzia schémy (= @Database.version); DbVersionGuard podľa nej spozná DB z novšej appky
        const val SCHEMA_VERSION = 3

        // E2 (v2 → v3): pôvod a istota priradení (nullable stĺpce) + tabuľka merge_log na vrátenie zlúčenia.
        // Existujúce riadky sa nemenia ani nemažú (staré priradenia majú source = NULL = neznámy pôvod).
        // Testy: androidTest PeopleMigrationTest.
        val MIGRATION_2_3 = object : Migration(2, SCHEMA_VERSION) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach { db.execSQL(it) }
            }
        }

        val MIGRATION_2_3_SQL = listOf(
            "ALTER TABLE `face_assignments` ADD COLUMN `source` TEXT",
            "ALTER TABLE `face_assignments` ADD COLUMN `confidence` REAL",
            "CREATE TABLE IF NOT EXISTS `merge_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`from_person_id` INTEGER NOT NULL, `to_person_id` INTEGER NOT NULL, `face_ids` TEXT NOT NULL, " +
                "`cannot_links` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `from_name` TEXT, " +
                "`from_created_at` INTEGER, `anchor_ids` TEXT, `group_ids` TEXT, `undone_at` INTEGER)",
        )

        private var db: PeopleDatabase? = null

        fun getInstance(context: Context): PeopleDatabase {
            if (db == null) {
                synchronized(PeopleDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, PeopleDatabase::class.java, "people.db")
                            // A1: poškodený súbor sa premenuje, nezmaže (inak by SQLite vytvoril prázdnu DB)
                            .openHelperFactory(SafeOpenHelperFactory())
                            .addMigrations(MIGRATION_2_3)
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
