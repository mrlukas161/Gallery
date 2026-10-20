package org.fossify.gallery.family

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Vlastná databáza rodinného prepojenia (family.db). Do people.db, faces.db ani extras.db nezasahuje.
//
// ⛔ ŽIADNY fallbackToDestructiveMigration: family.db je NENAHRADITEĽNÁ (mapa rodinné UUID ↔ lokálne
// id osôb, spárované telefóny, op-log, fronta). Pri nezhode verzie Room radšej vyhodí
// IllegalStateException a dáta ostanú na disku nedotknuté. Pri KAŽDEJ zmene entít zvýš `version`
// A pridaj .addMigrations(Migration(stará, nová) { … }), vzor: databases/GalleryDatabase.kt.
// exportSchema ostáva predvolene zapnutý (rovnako ako PeopleDatabase/FacesDatabase/ExtrasDatabase).
@Database(
    entities = [
        PeerEntity::class,
        ContentKeyEntity::class,
        FamilyPersonEntity::class,
        OpEntity::class,
        OutboxEntity::class,
        ProposalEntity::class,
    ],
    version = 1,
)
abstract class FamilyDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao

    abstract fun contentKeyDao(): ContentKeyDao

    abstract fun familyPersonDao(): FamilyPersonDao

    abstract fun opDao(): OpDao

    abstract fun outboxDao(): OutboxDao

    abstract fun proposalDao(): ProposalDao

    companion object {
        private const val DB_NAME = "family.db"

        @Volatile
        private var db: FamilyDatabase? = null

        fun getInstance(context: Context): FamilyDatabase =
            db ?: synchronized(FamilyDatabase::class) {
                db ?: Room.databaseBuilder(context.applicationContext, FamilyDatabase::class.java, DB_NAME)
                    .build()
                    .also { db = it }
            }
    }
}
