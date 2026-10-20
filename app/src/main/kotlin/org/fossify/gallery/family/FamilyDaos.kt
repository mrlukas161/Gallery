package org.fossify.gallery.family

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// DAO rodinného prepojenia, rozdelené podľa tabuliek (ContentKeyDao je v samostatnom súbore).

// spárované telefóny
@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertPeer(peer: PeerEntity)

    @Query("SELECT * FROM peers ORDER BY paired_at")
    fun getPeers(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE device_id = :deviceId")
    fun getPeer(deviceId: String): PeerEntity?

    @Query("UPDATE peers SET last_seen_at = :seenAt, last_lamport_seen = :lamport WHERE device_id = :deviceId")
    fun markPeerSeen(deviceId: String, seenAt: Long, lamport: Long)

    @Query("DELETE FROM peers WHERE device_id = :deviceId")
    fun deletePeer(deviceId: String)
}

// rodinné osoby (mapa rodinné UUID ↔ lokálne id v people.db)
@Dao
interface FamilyPersonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertPerson(person: FamilyPersonEntity)

    @Query("SELECT * FROM family_persons WHERE family_uuid = :familyUuid")
    fun getPersonByUuid(familyUuid: String): FamilyPersonEntity?

    @Query("SELECT * FROM family_persons WHERE local_person_id = :localPersonId")
    fun getPersonByLocalId(localPersonId: Long): FamilyPersonEntity?

    @Query("SELECT * FROM family_persons")
    fun getPersons(): List<FamilyPersonEntity>
}

// op-log
@Dao
interface OpDao {
    // IGNORE: opakované doručenie tej istej operácie (rovnaké opId) sa nezapíše druhýkrát. Vráti -1 pri duplicite.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOp(op: OpEntity): Long

    @Query("SELECT * FROM ops WHERE op_id = :opId")
    fun getOp(opId: String): OpEntity?

    @Query("SELECT * FROM ops WHERE device_id = :deviceId AND lamport > :sinceLamport ORDER BY lamport, op_id")
    fun getOpsSince(deviceId: String, sinceLamport: Long): List<OpEntity>

    @Query("SELECT * FROM ops WHERE applied_at IS NULL ORDER BY lamport, device_id, op_id")
    fun getUnappliedOps(): List<OpEntity>

    @Query("UPDATE ops SET applied_at = :appliedAt WHERE op_id = :opId")
    fun markOpApplied(opId: String, appliedAt: Long)

    @Query("SELECT MAX(lamport) FROM ops")
    fun getMaxLamport(): Long?
}

// fronta na odoslanie
@Dao
interface OutboxDao {
    @Insert
    fun insertOutbox(item: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE peer_device_id = :peerDeviceId AND state = :state ORDER BY id")
    fun getOutbox(peerDeviceId: String, state: String): List<OutboxEntity>

    @Query("UPDATE outbox SET state = :state, attempts = :attempts WHERE id = :id")
    fun updateOutbox(id: Long, state: String, attempts: Int)

    @Query("DELETE FROM outbox WHERE id = :id")
    fun deleteOutbox(id: Long)
}

// návrhy z druhého telefónu
@Dao
interface ProposalDao {
    @Insert
    fun insertProposal(proposal: ProposalEntity): Long

    @Query("SELECT * FROM proposals WHERE state = :state ORDER BY created_at")
    fun getProposals(state: String): List<ProposalEntity>

    @Query("SELECT COUNT(*) FROM proposals WHERE state = :state")
    fun countProposals(state: String): Int

    @Query("UPDATE proposals SET state = :state WHERE id = :id")
    fun setProposalState(id: Long, state: String)
}
