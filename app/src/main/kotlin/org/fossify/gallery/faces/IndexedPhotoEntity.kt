package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Eviduje, ktoré fotky už prešli detekciou (aj tie bez tvárí) — kvôli pokračovaniu (resume).
@Entity(tableName = "indexed_photos")
data class IndexedPhotoEntity(
    @PrimaryKey @ColumnInfo(name = "path") var path: String,
    @ColumnInfo(name = "face_count") var faceCount: Int,
    @ColumnInfo(name = "indexed_at") var indexedAt: Long,
)
