package org.fossify.gallery.clip

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ClipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: ClipEntity)

    @Query("SELECT path FROM clip_index")
    fun getIndexedPaths(): List<String>

    @Query("SELECT * FROM clip_index")
    fun getAll(): List<ClipEntity>

    @Query("SELECT COUNT(*) FROM clip_index")
    fun count(): Int
}
