package org.fossify.gallery.faces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OcrDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: OcrEntity)

    @Query("SELECT path FROM ocr_text")
    fun getIndexedPaths(): List<String>

    @Query("SELECT COUNT(*) FROM ocr_text")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM ocr_text WHERE text != ''")
    fun countWithText(): Int

    // uvoľnené hľadanie nad normalizovaným textom
    @Query("SELECT path FROM ocr_text WHERE norm_text LIKE '%' || :q || '%'")
    fun search(q: String): List<String>

    // rozpoznaný text konkrétnej fotky (na zobrazenie/výber v prehliadači)
    @Query("SELECT text FROM ocr_text WHERE path = :path LIMIT 1")
    fun getText(path: String): String?

    // podklad pre album Dokumenty — len fotky s nejakým textom
    @Query("SELECT * FROM ocr_text WHERE text != ''")
    fun getAllForDocs(): List<OcrEntity>
}
