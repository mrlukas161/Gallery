package org.fossify.gallery.faces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GeoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: GeoEntity)

    @Query("SELECT path FROM geo")
    fun getIndexedPaths(): List<String>

    // riadok fotky aj bez GPS (has_geo = 0 = spracovaná, poloha chýba) — rodinné prepojenie (F4)
    @Query("SELECT * FROM geo WHERE path = :path LIMIT 1")
    fun getRow(path: String): GeoEntity?

    // riadky fotiek mimo rozsahu spracovania (zamknutý/vylúčený priečinok) — IndexScope.keepAllowed
    @Query("DELETE FROM geo WHERE path IN (:paths)")
    fun deletePaths(paths: List<String>)

    @Query("SELECT * FROM geo WHERE has_geo = 1")
    fun getGeotagged(): List<GeoEntity>

    @Query("SELECT * FROM geo WHERE path = :path AND has_geo = 1 LIMIT 1")
    fun getByPath(path: String): GeoEntity?

    @Query("SELECT COUNT(*) FROM geo")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM geo WHERE has_geo = 1")
    fun countGeotagged(): Int
}
