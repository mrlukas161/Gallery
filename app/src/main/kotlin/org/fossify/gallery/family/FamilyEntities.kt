package org.fossify.gallery.family

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Entity rodinného prepojenia (family.db). Žiadna z nich neodkazuje cudzím kľúčom do people.db,
// faces.db ani extras.db: lokálne id sú len hodnoty, schéma tých databáz sa nemení.

// Spárovaný telefón. Trvalý kľúč páru NIE JE v DB, len alias zabaleného kľúča v Android Keystore.
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "public_tag") val publicTag: String,
    @ColumnInfo(name = "key_alias") val keyAlias: String,
    @ColumnInfo(name = "paired_at") val pairedAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long = 0,
    @ColumnInfo(name = "last_lamport_seen") val lastLamportSeen: Long = 0,
)

// Obsahový kľúč fotky (pozri ContentKey). Prepočíta sa len pri zmene veľkosti alebo mtime.
@Entity(tableName = "content_keys", indices = [Index("content_key")])
data class ContentKeyEntity(
    @PrimaryKey @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "mtime") val mtime: Long,
    @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
)

// Mapa rodinné UUID ↔ lokálne PersonEntity.id (people.db). Lokálne id ostáva bez zmeny.
@Entity(
    tableName = "family_persons",
    indices = [Index(value = ["local_person_id"], unique = true)],
)
data class FamilyPersonEntity(
    @PrimaryKey @ColumnInfo(name = "family_uuid") val familyUuid: String,
    @ColumnInfo(name = "local_person_id") val localPersonId: Long,
    @ColumnInfo(name = "name_version_lamport") val nameVersionLamport: Long = 0,
    @ColumnInfo(name = "name_device_id") val nameDeviceId: String? = null,
)

// Záznam op-logu. opId zaručí, že sa operácia aplikuje práve raz; appliedAt == null = ešte neaplikovaná.
@Entity(tableName = "ops", indices = [Index(value = ["device_id", "lamport"])])
data class OpEntity(
    @PrimaryKey @ColumnInfo(name = "op_id") val opId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "lamport") val lamport: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "applied_at") val appliedAt: Long? = null,
)

// Fronta na odoslanie (prežije reštart appky). kind = napr. "photo"/"bundle", refKey = cesta alebo contentKey.
@Entity(tableName = "outbox", indices = [Index(value = ["peer_device_id", "state"])])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "peer_device_id") val peerDeviceId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "ref_key") val refKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "state") val state: String = STATE_PENDING,
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_SENT = "sent"
        const val STATE_FAILED = "failed"
    }
}

// Návrh z druhého telefónu, o ktorom rozhodne používateľ (rozpory sa nikdy neprepisujú potichu).
@Entity(tableName = "proposals", indices = [Index("state")])
data class ProposalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "from_device_id") val fromDeviceId: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "state") val state: String = STATE_OPEN,
) {
    companion object {
        const val STATE_OPEN = "open"
        const val STATE_ACCEPTED = "accepted"
        const val STATE_REJECTED = "rejected"
    }
}

// Prijatý balík výsledkov k fotke (F4). id = "<deviceId odosielateľa>:<transferId>".
// STATE_WAITING: fotka (bez súboru, prišla inak, napr. cez WhatsApp) tu zatiaľ nie je, výsledky sa aplikujú,
// keď ju nájde spracovanie knižnice (FamilyInbox). STATE_APPLIED: hotové, riadok ostáva ako značka, aby sa
// opakovane doručený balík nespracoval druhýkrát (payload sa vtedy vyprázdni). STATE_EXPIRED: fotka neprišla.
@Entity(tableName = "bundle_inbox", indices = [Index("state"), Index("content_key")])
data class BundleInboxEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "from_device_id") val fromDeviceId: String,
    @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "applied_path") val appliedPath: String? = null,
) {
    companion object {
        const val STATE_WAITING = "waiting"
        const val STATE_APPLIED = "applied"
        const val STATE_EXPIRED = "expired"
    }
}

// Vlastný stav tohto zariadenia ako kľúč–hodnota: vlastné deviceId (UUID, vznikne raz), lamport hodiny,
// názov zariadenia. Jedna tabuľka pre F2 (párovanie) aj F3 (op-log), aby verzia 1 schémy ostala jedna.
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
) {
    companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_LAMPORT = "lamport"
    }
}
