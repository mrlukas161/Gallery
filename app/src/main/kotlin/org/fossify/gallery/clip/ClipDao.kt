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

    // jeden vektor (rodinné prepojenie F4) — getAll() by načítal celý index (~2 KB na fotku)
    @Query("SELECT * FROM clip_index WHERE path = :path LIMIT 1")
    fun getByPath(path: String): ClipEntity?

    // riadky fotiek mimo rozsahu spracovania (zamknutý/vylúčený priečinok) — IndexScope.keepAllowed
    @Query("DELETE FROM clip_index WHERE path IN (:paths)")
    fun deletePaths(paths: List<String>)

    @Query("SELECT * FROM clip_index")
    fun getAll(): List<ClipEntity>

    @Query("SELECT COUNT(*) FROM clip_index")
    fun count(): Int
}
