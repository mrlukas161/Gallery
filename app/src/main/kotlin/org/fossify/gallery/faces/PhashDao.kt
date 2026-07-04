package org.fossify.gallery.faces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PhashDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: PhashEntity)

    @Query("SELECT path FROM phash_index")
    fun getIndexedPaths(): List<String>

    @Query("SELECT * FROM phash_index")
    fun getAllHashes(): List<PhashEntity>

    @Query("SELECT COUNT(*) FROM phash_index")
    fun count(): Int
}
