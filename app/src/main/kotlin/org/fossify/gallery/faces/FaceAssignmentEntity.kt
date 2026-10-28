package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Priradenie tváre k osobe. face_id = FaceEntity.id (z faces.db, bez DB-FK, len hodnota).
// is_manual = používateľ to potvrdil ručne (NIKDY neprepisovať automatikou).
// E2 (people.db v3): source = pôvod priradenia (AssignmentSource), confidence = skóre (kNN) v čase potvrdenia
// návrhu; obe nullable — staré riadky pred v3 ich nemajú (null = neznámy pôvod).
@Entity(tableName = "face_assignments")
data class FaceAssignmentEntity(
    @PrimaryKey @ColumnInfo(name = "face_id") val faceId: Long,
    @ColumnInfo(name = "person_id") val personId: Long,
    @ColumnInfo(name = "is_manual") val isManual: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "source") val source: String? = null,
    @ColumnInfo(name = "confidence") val confidence: Float? = null,
)

// Hodnoty face_assignments.source (E2). Len pridávať, nikdy nepremenovať (sú v DB aj v zálohe F6).
object AssignmentSource {
    // ručné priradenie (Označiť tváre, presun medzi osobami, „Pomôž mi rozhodnúť“)
    const val MANUAL = "manual"

    // prišlo z prepojeného telefónu (op-log, balík k fotke, prijatý návrh)
    const val FAMILY = "family"

    // potvrdený návrh (Návrhy osoby, pomenovaná skupina návrhov)
    const val ACCEPTED_SUGGESTION = "accepted_suggestion"

    // potvrdená automaticky alebo neisto zaradená tvár (sekcia „Potvrď“ na karte osoby)
    const val AUTO_CONFIRMED = "auto_confirmed"

    // obnovené zo zálohy osôb (F6) alebo vrátením zlúčenia
    const val RESTORE = "restore"
}
