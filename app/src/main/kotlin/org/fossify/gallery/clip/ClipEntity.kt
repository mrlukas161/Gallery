package org.fossify.gallery.clip

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// CLIP obrázkový embedding (512 float, L2-normalizovaný) uložený ako ByteArray blob (2 KB/fotka).
@Entity(tableName = "clip_index")
data class ClipEntity(
    @PrimaryKey @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is ClipEntity && other.path == path
    override fun hashCode(): Int = path.hashCode()
}
