package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Tvár, ktorú používateľ NEchce zatiaľ priraďovať — odloží sa a prestane sa navrhovať/zobrazovať medzi neoznačenými.
@Entity(tableName = "ignored_faces")
data class IgnoredFaceEntity(
    @PrimaryKey @ColumnInfo(name = "face_id") val faceId: Long,
)
