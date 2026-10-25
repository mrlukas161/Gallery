package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// E2 (people.db v3): záznam zlúčenia osoby [fromPersonId] do [toPersonId], aby sa dalo vrátiť (Ľudia →
// „Vrátiť zlúčenie“). Zapisuje ho PeopleDao.mergePersons v tej istej transakcii ako samotné zlúčenie.
//  - face_ids: JSON pole priradení zlúčenej osoby PRED zlúčením (MergeLogCodec: id tváre, is_manual,
//    source, confidence, updated_at);
//  - cannot_links: JSON {"from": tváre, ktoré „nie sú“ zlúčená osoba, "to_removed": tváre, ktorým zlúčenie
//    zmazalo „nie je“ cieľovej osoby, lebo jej po zlúčení patria};
//  - doplnky nad pôvodný návrh tabuľky (všetko na úplné vrátenie): meno a čas založenia zlúčenej osoby,
//    id vzorov (anchor_embeddings), skupiny (extras.db, zapisuje ich volajúci) a čas vrátenia (undone_at,
//    null = nevrátené). Záznamy sa nemažú (sú malé), vrátené sa len označia.
@Entity(tableName = "merge_log")
data class MergeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "from_person_id") val fromPersonId: Long,
    @ColumnInfo(name = "to_person_id") val toPersonId: Long,
    @ColumnInfo(name = "face_ids") val faceIds: String,
    @ColumnInfo(name = "cannot_links") val cannotLinks: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "from_name") val fromName: String? = null,
    @ColumnInfo(name = "from_created_at") val fromCreatedAt: Long? = null,
    @ColumnInfo(name = "anchor_ids") val anchorIds: String? = null,
    @ColumnInfo(name = "group_ids") val groupIds: String? = null,
    @ColumnInfo(name = "undone_at") val undoneAt: Long? = null,
)
