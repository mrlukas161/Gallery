package org.fossify.gallery.family

// Ochrana proti opakovaniu (čistá logika).
//
// ReplayGuard: v rámci jedného sedenia musí každé ďalšie poradové číslo byť väčšie ako posledné
// prijaté. Volá sa až PO overení rámca (číslo je v AAD), takže podvrhnutý rámec počítadlo neposunie.
class ReplayGuard(private var last: Long = 0) {
    @Synchronized
    fun accept(seq: Long): Boolean {
        if (seq <= last) return false
        last = seq
        return true
    }

    @Synchronized
    fun last(): Long = last
}

// NonceCache: pamätá si posledné nonce z hello správ (tie majú poradové číslo 0), aby sa ten istý
// zachytený hello nedal prehrať znova. Najstaršie záznamy vypadnú po dosiahnutí kapacity.
class NonceCache(private val capacity: Int = DEFAULT_CAPACITY) {
    private val seen = LinkedHashSet<String>()

    // true = nonce je nový (a odteraz je zapamätaný); false = opakovanie
    @Synchronized
    fun firstSeen(nonce: ByteArray): Boolean {
        val key = FamilyBytes.b64(nonce)
        if (!seen.add(key)) return false
        while (seen.size > capacity) {
            val oldest = seen.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }

    companion object {
        const val DEFAULT_CAPACITY = 512
    }
}
