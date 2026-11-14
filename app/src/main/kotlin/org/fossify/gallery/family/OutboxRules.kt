package org.fossify.gallery.family

// Stav fronty „Poslať do prepojeného telefónu“ po prenosoch (jedna fotka = N kúskov + záverečná položka).
// Čistá logika bez Androidu (OutboxRulesTest).
//
//  - Prenos je hotový, až keď prešla záverečná položka (balík s výsledkami). Kúsky súboru sa dovtedy z fronty
//    nemažú: keď prijímateľ medzičasom o rozpracovaný súbor prišiel, pošlú sa znova.
//  - Zlyhaná položka (5 pokusov, súbor sa zmenil) označí celý prenos ako zlyhaný: nezmizne potichu, ostane vo
//    fronte, používateľ dostane notifikáciu a na obrazovke prepojenia „Skúsiť znova“.
object OutboxRules {
    // jedna položka fronty: transferId z BundleTransfer.Ref (null = iná alebo poškodená položka)
    data class Row(val id: Long, val transferId: String?, val isChunk: Boolean, val state: String)

    data class Summary(val queued: Int, val failed: Int)

    fun row(item: OutboxEntity): Row = Row(
        item.id,
        BundleTransfer.decode(item.refKey)?.transferId,
        item.kind == BundleTransfer.KIND_CHUNK,
        item.state,
    )

    // prenosy, ktorých záverečná položka prešla → všetky ich položky sa môžu z fronty zmazať
    fun finishedTransfers(rows: List<Row>): Set<String> =
        rows.filter { !it.isChunk && it.state == OutboxEntity.STATE_SENT }.mapNotNullTo(HashSet()) { it.transferId }

    // prenosy s aspoň jednou zlyhanou položkou
    fun failedTransfers(rows: List<Row>): Set<String> =
        rows.filter { it.state == OutboxEntity.STATE_FAILED }.mapNotNullTo(HashSet()) { it.transferId }

    // položky prenosu, ktoré treba označiť ako zlyhané (celý prenos naraz)
    fun toFail(rows: List<Row>, transferId: String): List<Long> =
        rows.filter { it.transferId == transferId && it.state != OutboxEntity.STATE_FAILED }.map { it.id }

    // záverečná položka neprešla (prijímateľ nemá všetky kúsky): už odoslané kúsky sa pošlú znova
    fun chunksToResend(rows: List<Row>, transferId: String): List<Long> =
        rows.filter { it.transferId == transferId && it.isChunk && it.state == OutboxEntity.STATE_SENT }.map { it.id }

    // čo vidí používateľ: počet fotiek vo fronte a zlyhaných fotiek (prenosov, nie kúskov)
    fun summary(rows: List<Row>): Summary {
        val failed = failedTransfers(rows)
        val finished = finishedTransfers(rows)
        val queued = rows.filter { !it.isChunk && it.state == OutboxEntity.STATE_PENDING }
            .count { it.transferId !in failed && it.transferId !in finished }
        return Summary(queued, failed.size)
    }
}
