package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// "Vzorový" embedding osoby naimportovaný z Picasy (re-embednutý výrez tváre z PC).
// Nie je viazaný na žiadnu telefónnu fotku — len napája centroid osoby v PersonGrouper.
@Entity(tableName = "anchor_embeddings", indices = [Index("person_id")])
data class AnchorEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "person_id") val personId: Long,
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
