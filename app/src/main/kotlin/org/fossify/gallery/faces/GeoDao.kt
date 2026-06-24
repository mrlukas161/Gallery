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

    @Query("SELECT * FROM geo WHERE has_geo = 1")
    fun getGeotagged(): List<GeoEntity>

    @Query("SELECT COUNT(*) FROM geo")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM geo WHERE has_geo = 1")
    fun countGeotagged(): Int
}
