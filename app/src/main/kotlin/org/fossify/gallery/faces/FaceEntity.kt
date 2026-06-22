package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "faces",
    indices = [
        Index("media_full_path"),
        Index(value = ["media_full_path", "face_index"], unique = true),
    ]
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "media_full_path") var mediaFullPath: String,
    @ColumnInfo(name = "media_store_id") var mediaStoreId: Long,
    @ColumnInfo(name = "face_index") var faceIndex: Int,
    @ColumnInfo(name = "bbox_left") var bboxLeft: Int,
    @ColumnInfo(name = "bbox_top") var bboxTop: Int,
    @ColumnInfo(name = "bbox_right") var bboxRight: Int,
    @ColumnInfo(name = "bbox_bottom") var bboxBottom: Int,
    @ColumnInfo(name = "score") var score: Float,
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) var embedding: ByteArray?,
    @ColumnInfo(name = "detected_at") var detectedAt: Long,
)
