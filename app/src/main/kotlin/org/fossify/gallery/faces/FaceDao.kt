package org.fossify.gallery.faces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFaces(faces: List<FaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertIndexedPhoto(photo: IndexedPhotoEntity)

    @Query("SELECT path FROM indexed_photos")
    fun getProcessedPaths(): List<String>

    @Query("SELECT COUNT(*) FROM faces")
    fun getFaceCount(): Int

    @Query("SELECT COUNT(*) FROM indexed_photos WHERE face_count > 0")
    fun getPhotosWithFacesCount(): Int

    @Query("SELECT COUNT(*) FROM indexed_photos")
    fun getProcessedCount(): Int

    @Query("DELETE FROM faces")
    fun deleteAllFaces()

    @Query("DELETE FROM indexed_photos")
    fun deleteAllIndexedPhotos()
}
