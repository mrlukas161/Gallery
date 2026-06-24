package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Skupina osôb (napr. Rodina 1, Rodina 2, vzdelávacia skupina).
@Entity(tableName = "person_groups")
data class PersonGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
)
