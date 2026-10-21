package org.fossify.gallery.family

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.fossify.gallery.safety.SafeOpenHelperFactory

// Vlastná databáza rodinného prepojenia (family.db). Do people.db, faces.db ani extras.db nezasahuje.
//
// ⛔ ŽIADNY fallbackToDestructiveMigration: family.db je NENAHRADITEĽNÁ (mapa rodinné UUID ↔ lokálne
// id osôb, spárované telefóny, op-log, fronta). Pri nezhode verzie Room radšej vyhodí
// IllegalStateException a dáta ostanú na disku nedotknuté. Pri KAŽDEJ zmene entít zvýš `version`
// A pridaj .addMigrations(Migration(stará, nová) { … }), vzor: databases/GalleryDatabase.kt.
// BundleInboxEntity (F4) pribudla ešte vo verzii 1: family.db zatiaľ nebola vydaná v žiadnom APK.
// exportSchema ostáva predvolene zapnutý (rovnako ako PeopleDatabase/FacesDatabase/ExtrasDatabase).
@Database(
    entities = [
        PeerEntity::class,
        ContentKeyEntity::class,
        FamilyPersonEntity::class,
        OpEntity::class,
        OutboxEntity::class,
        ProposalEntity::class,
        MetaEntity::class,
        BundleInboxEntity::class,
    ],
    version = FamilyDatabase.SCHEMA_VERSION,
)
abstract class FamilyDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao

    abstract fun contentKeyDao(): ContentKeyDao

    abstract fun familyPersonDao(): FamilyPersonDao

    abstract fun opDao(): OpDao

    abstract fun outboxDao(): OutboxDao

    abstract fun proposalDao(): ProposalDao

    abstract fun metaDao(): MetaDao

    abstract fun bundleInboxDao(): BundleInboxDao

    companion object {
        // verzia schémy (= @Database.version); DbVersionGuard podľa nej spozná DB z novšej appky
        const val SCHEMA_VERSION = 1

        private const val DB_NAME = "family.db"

        @Volatile
        private var db: FamilyDatabase? = null

        fun getInstance(context: Context): FamilyDatabase =
            db ?: synchronized(FamilyDatabase::class) {
                db ?: Room.databaseBuilder(context.applicationContext, FamilyDatabase::class.java, DB_NAME)
                    // A1: poškodený súbor sa premenuje, nezmaže (inak by SQLite vytvoril prázdnu DB)
                    .openHelperFactory(SafeOpenHelperFactory())
                    .build()
                    .also { db = it }
            }
    }
}
