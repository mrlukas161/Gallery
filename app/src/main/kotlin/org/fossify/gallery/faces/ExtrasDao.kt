package org.fossify.gallery.faces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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

    // Zmazanie skupiny aj s členstvami v jednej transakcii (Room tu cudzí kľúč s kaskádou nevynucuje).
    @Transaction
    fun deleteGroupWithMembers(groupId: Long) {
        deleteMembersForGroup(groupId)
        deleteGroup(groupId)
    }

    @Query("SELECT * FROM person_groups ORDER BY name COLLATE NOCASE")
    fun getGroups(): List<PersonGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addMember(member: GroupMemberEntity)

    @Query("DELETE FROM group_members WHERE group_id = :groupId AND person_id = :personId")
    fun removeMember(groupId: Long, personId: Long)

    @Query("DELETE FROM group_members WHERE group_id = :groupId")
    fun deleteMembersForGroup(groupId: Long)

    // osoba zanikla (zmazaná) — nesmie ostať v skupinách ako sirota
    @Query("DELETE FROM group_members WHERE person_id = :personId")
    fun removePersonFromGroups(personId: Long)

    @Query(
        "INSERT OR IGNORE INTO group_members (group_id, person_id) " +
            "SELECT group_id, :to FROM group_members WHERE person_id = :from"
    )
    fun copyMemberships(from: Long, to: Long)

    // zlúčenie osôb: výsledná osoba zdedí skupiny zlúčenej (člen v oboch skupinách ostane jednou položkou)
    @Transaction
    fun moveMemberships(from: Long, to: Long) {
        copyMemberships(from, to)
        removePersonFromGroups(from)
    }

    @Query("SELECT person_id FROM group_members WHERE group_id = :groupId")
    fun getMembers(groupId: Long): List<Long>

    @Query("SELECT group_id FROM group_members WHERE person_id = :personId")
    fun getGroupsForPerson(personId: Long): List<Long>
}
