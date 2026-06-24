package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity

// Členstvo osoby v skupine. person_id = PersonEntity.id (z people.db, len hodnota).
@Entity(tableName = "group_members", primaryKeys = ["group_id", "person_id"])
data class GroupMemberEntity(
    @ColumnInfo(name = "group_id") val groupId: Long,
    @ColumnInfo(name = "person_id") val personId: Long,
)
