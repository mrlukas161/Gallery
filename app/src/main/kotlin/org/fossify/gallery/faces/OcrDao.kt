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
}
