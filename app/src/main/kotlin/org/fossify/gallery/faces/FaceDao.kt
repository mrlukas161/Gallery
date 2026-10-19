package org.fossify.gallery.faces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FaceDao {
    // ABORT, nie REPLACE: pri kolízii (cesta, poradie tváre) by REPLACE potichu zmazal existujúci
    // riadok aj s jeho id — a tým menovku osoby v people.db. Volajúci kolíziám predchádzajú
    // (FaceIndexer nedeteguje nanovo fotky s tvárami, RefreshFaces čísluje za všetkými tvárami fotky).
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertFaces(faces: List<FaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertIndexedPhoto(photo: IndexedPhotoEntity)

    @Query("SELECT path FROM indexed_photos")
    fun getProcessedPaths(): List<String>

    // fotky, ktoré už majú v DB tváre — indexer ich NESMIE detegovať nanovo: nové riadky by
    // dostali nové id a menovky v people.db (face_id) by ostali visieť
    @Query("SELECT DISTINCT media_full_path FROM faces")
    fun getPathsWithFaces(): List<String>

    @Query("SELECT COUNT(*) FROM faces WHERE media_full_path = :path")
    fun getFaceCountForPath(path: String): Int

    @Query("SELECT COUNT(*) FROM faces")
    fun getFaceCount(): Int

    @Query("SELECT embedding FROM faces WHERE embedding IS NOT NULL")
    fun getAllEmbeddings(): List<ByteArray>

    @Query("SELECT * FROM faces WHERE embedding IS NOT NULL")
    fun getAllFaces(): List<FaceEntity>

    // všetky tváre vrátane tých bez odtlačku (prepracovanie ich doplní na mieste)
    @Query("SELECT * FROM faces")
    fun getEveryFace(): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE media_full_path = :path")
    fun getFacesForPath(path: String): List<FaceEntity>

    // fotky s tvárou bez odtlačku (výpočet zlyhal) — náhodne, aby neopraviteľné neblokovali ostatné
    @Query("SELECT DISTINCT media_full_path FROM faces WHERE embedding IS NULL ORDER BY RANDOM() LIMIT :limit")
    fun getPathsWithUnembeddedFaces(limit: Int): List<String>

    @Query("SELECT * FROM faces WHERE id IN (:ids)")
    fun getFacesByIds(ids: List<Long>): List<FaceEntity>

    @Query("UPDATE faces SET embedding = :embedding WHERE id = :id")
    fun updateEmbedding(id: Long, embedding: ByteArray)

    // oprava polohy tváre po prepracovaní (upright priestor) — id ostáva, menovka prežije
    @Query("UPDATE faces SET bbox_left = :left, bbox_top = :top, bbox_right = :right, bbox_bottom = :bottom, score = :score WHERE id = :id")
    fun updateFaceBox(id: Long, left: Int, top: Int, right: Int, bottom: Int, score: Float)

    @Query("SELECT COUNT(*) FROM indexed_photos WHERE face_count > 0")
    fun getPhotosWithFacesCount(): Int

    @Query("SELECT COUNT(*) FROM indexed_photos")
    fun getProcessedCount(): Int

    @Query("DELETE FROM faces")
    fun deleteAllFaces()

    @Query("DELETE FROM indexed_photos")
    fun deleteAllIndexedPhotos()
}
