package org.fossify.gallery.family

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.util.UUID

// vlastný stav zariadenia (deviceId, lamport hodiny, názov)
@Dao
abstract class MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    abstract fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun put(meta: MetaEntity)

    @Query("DELETE FROM meta WHERE `key` = :key")
    abstract fun delete(key: String)

    // vlastné deviceId vznikne pri prvom použití a potom sa nemení
    @Transaction
    open fun deviceId(): String =
        get(MetaEntity.KEY_DEVICE_ID) ?: UUID.randomUUID().toString().also {
            put(MetaEntity(MetaEntity.KEY_DEVICE_ID, it))
        }

    // lamport hodiny: ďalšia hodnota je max(vlastná, najvyššia videná) + 1
    @Transaction
    open fun nextLamport(seen: Long = 0): Long {
        val current = get(MetaEntity.KEY_LAMPORT)?.toLongOrNull() ?: 0
        val next = maxOf(current, seen) + 1
        put(MetaEntity(MetaEntity.KEY_LAMPORT, next.toString()))
        return next
    }

    // D1: pri hello sa hodiny zdvihnú aspoň na „have“ druhej strany (najvyšší môj lamport, ktorý už má).
    // Po obnove staršej family.db by inak nové operácie dostali lamport ≤ have a druhá strana by si ich
    // nikdy nevypýtala. Vráti aktuálnu hodnotu hodín.
    @Transaction
    open fun raiseLamport(atLeast: Long): Long {
        val current = get(MetaEntity.KEY_LAMPORT)?.toLongOrNull() ?: 0
        val raised = LamportClock.afterHello(current, atLeast)
        if (raised != current) put(MetaEntity(MetaEntity.KEY_LAMPORT, raised.toString()))
        return raised
    }
}
