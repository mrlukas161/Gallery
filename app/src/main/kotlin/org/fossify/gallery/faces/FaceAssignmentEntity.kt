package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Priradenie tváre k osobe. face_id = FaceEntity.id (z faces.db, bez DB-FK, len hodnota).
// is_manual = používateľ to potvrdil ručne (NIKDY neprepisovať automatikou).
@Entity(tableName = "face_assignments")
data class FaceAssignmentEntity(
    @PrimaryKey @ColumnInfo(name = "face_id") val faceId: Long,
    @ColumnInfo(name = "person_id") val personId: Long,
    @ColumnInfo(name = "is_manual") val isManual: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
