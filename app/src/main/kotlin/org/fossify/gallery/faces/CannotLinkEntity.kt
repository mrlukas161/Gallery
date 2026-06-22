package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity

// "Toto NIE je daná osoba" — negatívne pravidlo. Grouper túto tvár tejto osobe už nenavrhne (učenie).
@Entity(tableName = "cannot_links", primaryKeys = ["face_id", "person_id"])
data class CannotLinkEntity(
    @ColumnInfo(name = "face_id") val faceId: Long,
    @ColumnInfo(name = "person_id") val personId: Long,
)
