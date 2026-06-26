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

    // --- import z Picasy (anchor embeddingy + dedup mien) ---
    @Query("SELECT * FROM persons WHERE name = :name LIMIT 1")
    fun findPersonByName(name: String): PersonEntity?

    @Insert
    fun insertAnchor(anchor: AnchorEmbeddingEntity)

    @Query("SELECT * FROM anchor_embeddings")
    fun getAllAnchors(): List<AnchorEmbeddingEntity>

    @Query("DELETE FROM anchor_embeddings")
    fun deleteAllAnchors()

    @Query("SELECT embedding FROM anchor_embeddings WHERE person_id = :personId")
    fun getAnchorEmbeddings(personId: Long): List<ByteArray>

    @Query("DELETE FROM anchor_embeddings WHERE person_id = :personId")
    fun deleteAnchorsForPerson(personId: Long)

    @Query("SELECT COUNT(*) FROM anchor_embeddings")
    fun getAnchorCount(): Int

    @Insert
    fun insertImportManifest(manifest: ImportManifestEntity)

    @Query("SELECT * FROM import_manifests WHERE manifest_hash = :hash LIMIT 1")
    fun findImportManifest(hash: String): ImportManifestEntity?
}
