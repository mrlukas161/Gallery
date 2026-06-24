package org.fossify.gallery.faces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExtrasDao {
    // --- ignorované tváre ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertIgnored(entity: IgnoredFaceEntity)

    @Query("DELETE FROM ignored_faces WHERE face_id = :faceId")
    fun deleteIgnored(faceId: Long)

    @Query("SELECT face_id FROM ignored_faces")
    fun getIgnoredIds(): List<Long>

    // --- skupiny osôb ---
    @Insert
    fun insertGroup(group: PersonGroupEntity): Long

    @Query("UPDATE person_groups SET name = :name WHERE id = :id")
    fun renameGroup(id: Long, name: String)

    @Query("DELETE FROM person_groups WHERE id = :id")
    fun deleteGroup(id: Long)

    @Query("SELECT * FROM person_groups ORDER BY name COLLATE NOCASE")
    fun getGroups(): List<PersonGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addMember(member: GroupMemberEntity)

    @Query("DELETE FROM group_members WHERE group_id = :groupId AND person_id = :personId")
    fun removeMember(groupId: Long, personId: Long)

    @Query("DELETE FROM group_members WHERE group_id = :groupId")
    fun deleteMembersForGroup(groupId: Long)

    @Query("SELECT person_id FROM group_members WHERE group_id = :groupId")
    fun getMembers(groupId: Long): List<Long>

    @Query("SELECT group_id FROM group_members WHERE person_id = :personId")
    fun getGroupsForPerson(personId: Long): List<Long>
}
