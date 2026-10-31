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

    // jeden riadok (rodinné prepojenie F4: balík k fotke a záložná zhoda)
    @Query("SELECT * FROM phash_index WHERE path = :path LIMIT 1")
    fun getByPath(path: String): PhashEntity?

    // riadky fotiek mimo rozsahu spracovania (zamknutý/vylúčený priečinok) — IndexScope.keepAllowed
    @Query("DELETE FROM phash_index WHERE path IN (:paths)")
    fun deletePaths(paths: List<String>)

    @Query("SELECT * FROM phash_index")
    fun getAllHashes(): List<PhashEntity>

    @Query("SELECT COUNT(*) FROM phash_index")
    fun count(): Int

    // pečiatka indexu pre cache zoznamu „Podobné a duplikáty“ (CompareRepository) spolu s count()
    @Query("SELECT MAX(indexed_at) FROM phash_index")
    fun maxIndexedAt(): Long?
}
