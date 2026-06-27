package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Výsledok skenu QR/čiarových kódov na fotke. qr_text = dekódovaný obsah (prázdne = kód sa nenašiel).
@Entity(tableName = "qr_codes")
data class QrEntity(
    @PrimaryKey @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "qr_text") val qrText: String,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
)
