package org.fossify.gallery.family

// Lamport hodiny op-logu (D1). Čistá logika bez Androidu (LamportClockTest).
//
// „have“ v hello = najvyšší lamport MOJICH operácií, ktoré už druhá strana má uložené. Druhá strana si
// pri výmene pýta len moje operácie s lamport > have (OpExchange.ownOpsSince). Keď sa tu obnoví staršia
// family.db (záloha A3), vlastné hodiny sú nižšie než have a nové operácie by mali lamport ≤ have —
// nikdy by neodišli. Preto sa hodiny pri hello zdvihnú aspoň na have.
object LamportClock {
    fun afterHello(current: Long, peerHave: Long): Long = maxOf(current, peerHave)

    // Moje operácie pre druhú stranu: lamport > since, najviac `limit` (operácie s rovnakým lamport sa nedelia,
    // aby sa „have“ neposunulo uprostred skupiny). Vráti (dávka, či zostali ďalšie).
    fun page(sorted: List<OpEntity>, since: Long, limit: Int): Pair<List<OpEntity>, Boolean> {
        val all = sorted.filter { it.lamport > since }
        if (all.size <= limit) return all to false
        var end = limit
        while (end < all.size && all[end].lamport == all[end - 1].lamport) end++
        return all.subList(0, end) to (end < all.size)
    }
}
