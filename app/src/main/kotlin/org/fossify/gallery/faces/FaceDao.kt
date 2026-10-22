package org.fossify.gallery.faces

import androidx.room.ColumnInfo
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

    @Query("SELECT * FROM faces WHERE embedding IS NOT NULL")
    fun getAllFaces(): List<FaceEntity>

    // všetky tváre vrátane tých bez odtlačku (prepracovanie ich doplní na mieste)
    @Query("SELECT * FROM faces")
    fun getEveryFace(): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE media_full_path = :path")
    fun getFacesForPath(path: String): List<FaceEntity>

    // len id a cesta každej tváre (bez stĺpca embedding) — prepočet na v2 si odtlačky číta po fotkách
    @Query("SELECT id, media_full_path FROM faces")
    fun getFaceRefs(): List<FaceRef>

    // Tváre s odtlačkom, ale BEZ samotného vektora (embedding = NULL vo výsledku) — pre zoznamy,
    // ktoré vektor nepotrebujú (Návrhy bez osoby). Entita ani schéma sa nemení.
    @Query(
        "SELECT id, media_full_path, media_store_id, face_index, bbox_left, bbox_top, bbox_right, bbox_bottom, " +
            "score, NULL AS embedding, detected_at FROM faces WHERE embedding IS NOT NULL"
    )
    fun getEmbeddedFacesWithoutVectors(): List<FaceEntity>

    // tváre s odtlačkom po stránkach (podľa id) — skórovanie návrhov bez držania všetkých vektorov naraz
    @Query("SELECT * FROM faces WHERE embedding IS NOT NULL AND id > :afterId ORDER BY id LIMIT :limit")
    fun getEmbeddedFacesPage(afterId: Long, limit: Int): List<FaceEntity>

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

    // A6: otočenie rámčeka po otočení fotky v appke — id, skóre aj odtlačok ostávajú
    @Query(
        "UPDATE faces SET bbox_left = :left, bbox_top = :top, bbox_right = :right, bbox_bottom = :bottom WHERE id = :id"
    )
    fun updateBox(id: Long, left: Int, top: Int, right: Int, bottom: Int)

    // A5: presunutá fotka — tváre sa preklopia na novú cestu NA MIESTE (id a menovky ostanú)
    @Query(
        "UPDATE faces SET media_full_path = :newPath, media_store_id = :mediaStoreId WHERE media_full_path = :oldPath"
    )
    fun relinkPath(oldPath: String, newPath: String, mediaStoreId: Long): Int

    // A5: LEN nepomenované duplikáty z indexera na novej ceste presunutej fotky, ktorých id
    // FaceRelinker overil, že sa nevyskytujú v people.db, extras.db ani family.db (proposals).
    // Nikde inde nevolať — žiadne hromadné mazanie tvárí.
    @Query("DELETE FROM faces WHERE media_full_path = :path AND id IN (:ids)")
    fun deleteUnreferencedDuplicates(path: String, ids: List<Long>): Int

    @Query("SELECT * FROM indexed_photos WHERE path = :path")
    fun getIndexedPhoto(path: String): IndexedPhotoEntity?

    // značka „spracovaná" (prestavateľná) — maže sa len pri prenose na novú cestu presunutej fotky
    @Query("DELETE FROM indexed_photos WHERE path = :path")
    fun deleteIndexedPhoto(path: String)

    @Query("SELECT COUNT(*) FROM indexed_photos WHERE face_count > 0")
    fun getPhotosWithFacesCount(): Int

    @Query("SELECT COUNT(*) FROM indexed_photos")
    fun getProcessedCount(): Int

    // ⛔ Žiadne hromadné mazanie tvárí: id sú odkazované z people.db/extras.db (menovky).
    // (Nepoužívané deleteAllFaces/deleteAllIndexedPhotos boli odstránené, aby sa nedali omylom zavolať.)
}

// projekcia faces (id, cesta) — nie je to entita, schéma faces.db sa nemení
data class FaceRef(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "media_full_path") val mediaFullPath: String,
)
