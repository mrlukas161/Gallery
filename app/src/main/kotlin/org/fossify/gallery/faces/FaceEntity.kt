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
    // --- E1 (faces.db v3, Migration 2→3): všetko nullable alebo s predvolenou hodnotou, staré riadky sa
    // dopĺňajú lenivo pri nabíjaní (FaceMetaBackfill). Nové tváre ich dostanú pri detekcii (FaceStore).
    // skóre kvality 0–1 (FaceQuality.score); null = ešte nespočítané (brána ho berie ako „prejde“)
    @ColumnInfo(name = "quality") var quality: Float? = null,
    // natočenie hlavy v stupňoch z 5 bodov tváre (FaceQuality.pose); null = bez bodov (M2/MR) alebo nespočítané
    @ColumnInfo(name = "yaw") var yaw: Float? = null,
    @ColumnInfo(name = "pitch") var pitch: Float? = null,
    // ostrosť = variancia Laplaciánu na 112 px výreze (vyššie = ostrejšie, FaceQuality.laplacianVariance)
    @ColumnInfo(name = "blur") var blur: Float? = null,
    // metóda zarovnania odtlačku (FaceAligner.M5/M2/MR)
    @ColumnInfo(name = "align_method") var alignMethod: Int? = null,
    // priestor rámčeka (FaceBoxSpace): 0 neznámy/raw-éra, 1 upright v1 (s chybou EXIF 5/7), 2 upright v2 (= Glide)
    @ColumnInfo(name = "box_space", defaultValue = "0") var boxSpace: Int = FaceBoxSpace.UNKNOWN,
    // verzia odtlačku v stĺpci embedding (1/2, FaceEmbedVersion); null = bez odtlačku alebo nezistené
    @ColumnInfo(name = "embed_version") var embedVersion: Int? = null,
    // dátum nasnímania (MediaStore DATE_TAKEN, inak EXIF) v ms; 0 = zistené, že chýba; null = ešte nezistené
    @ColumnInfo(name = "taken_at") var takenAt: Long? = null,
)
