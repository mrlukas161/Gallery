package org.fossify.gallery.family

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// Samostatné DAO pre obsahové kľúče, aby ContentKeyStore šiel otestovať s falošnou implementáciou bez Androidu.
@Dao
interface ContentKeyDao {
    @Query("SELECT * FROM content_keys WHERE path = :path")
    fun get(path: String): ContentKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: ContentKeyEntity)

    @Query("SELECT * FROM content_keys WHERE content_key = :contentKey")
    fun findByContentKey(contentKey: String): List<ContentKeyEntity>

    @Query("DELETE FROM content_keys WHERE path = :path")
    fun delete(path: String)
}
