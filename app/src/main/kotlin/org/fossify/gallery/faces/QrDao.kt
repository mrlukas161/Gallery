package org.fossify.gallery.faces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface QrDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: QrEntity)

    @Query("SELECT path FROM qr_codes")
    fun getIndexedPaths(): List<String>

    // jeden riadok aj bez kódu (qr_text = '' = spracovaná) — rodinné prepojenie (F4)
    @Query("SELECT * FROM qr_codes WHERE path = :path LIMIT 1")
    fun getByPath(path: String): QrEntity?

    // riadky fotiek mimo rozsahu spracovania (zamknutý/vylúčený priečinok) — IndexScope.keepAllowed
    @Query("DELETE FROM qr_codes WHERE path IN (:paths)")
    fun deletePaths(paths: List<String>)

    @Query("SELECT COUNT(*) FROM qr_codes")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM qr_codes WHERE qr_text != ''")
    fun countWithContent(): Int

    // cesty fotiek, ktoré obsahujú aspoň jeden QR/čiarový kód
    @Query("SELECT path FROM qr_codes WHERE qr_text != ''")
    fun getPathsWithQr(): List<String>

    // hľadanie v dekódovanom obsahu kódu (napr. časť URL/IBAN)
    @Query("SELECT path FROM qr_codes WHERE qr_text LIKE '%' || :q || '%' AND qr_text != ''")
    fun search(q: String): List<String>
}
