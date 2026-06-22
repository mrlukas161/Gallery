package org.fossify.gallery.faces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PeopleDao {
    // --- osoby ---
    @Insert
    fun insertPerson(person: PersonEntity): Long

    @Query("UPDATE persons SET name = :name WHERE id = :id")
    fun renamePerson(id: Long, name: String)

    @Query("DELETE FROM persons WHERE id = :id")
    fun deletePerson(id: Long)

    @Query("SELECT * FROM persons")
    fun getPersons(): List<PersonEntity>

    // --- priradenia tvárí ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAssignment(assignment: FaceAssignmentEntity)

    @Query("DELETE FROM face_assignments WHERE face_id = :faceId")
    fun deleteAssignment(faceId: Long)

    @Query("SELECT * FROM face_assignments")
    fun getAssignments(): List<FaceAssignmentEntity>

    @Query("UPDATE face_assignments SET person_id = :to WHERE person_id = :from")
    fun reassignPerson(from: Long, to: Long)

    @Query("DELETE FROM face_assignments WHERE person_id = :personId")
    fun deleteAssignmentsForPerson(personId: Long)

    // --- negatívne pravidlá (učenie) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCannotLink(link: CannotLinkEntity)

    @Query("SELECT * FROM cannot_links")
    fun getCannotLinks(): List<CannotLinkEntity>

    @Query("UPDATE cannot_links SET person_id = :to WHERE person_id = :from")
    fun reassignCannotLinks(from: Long, to: Long)

    @Query("DELETE FROM cannot_links WHERE person_id = :personId")
    fun deleteCannotLinksForPerson(personId: Long)
}
