package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Perceptuálny hash fotky (average hash, 64 bitov) na hľadanie podobných/duplicitných fotiek.
@Entity(tableName = "phash_index")
data class PhashEntity(
    @PrimaryKey @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "hash_value") val hashValue: Long,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
)
