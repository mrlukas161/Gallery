package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Rozpoznaný text z fotky (OCR). norm_text = bez diakritiky/malé písmená pre uvoľnené hľadanie.
@Entity(tableName = "ocr_text")
data class OcrEntity(
    @PrimaryKey @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "norm_text") val normText: String,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
)
