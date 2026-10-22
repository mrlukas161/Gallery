package org.fossify.gallery.family

// Mená (priradenia osôb) z balíka k fotke (D4, D5). Čistá logika bez Androidu (BundleNamesTest).
//
// Keď prijímateľ fotku už spracoval sám (napr. kópiu z WhatsAppu ešte pred synchronizáciou), tváre z balíka sa
// nezapíšu, ale spárujú sa s jeho tvárami cez FaceMatch (relatívne rámčeky) a prevezmú sa aspoň mená — podľa
// rovnakých pravidiel ako op-log: ručné priradenie prijímateľa sa nikdy neprepíše, automatické prepíše len ručné.
object BundleNames {
    data class Write(val faceId: Long, val personId: Long, val manual: Boolean)

    // Dvojice (index tváre v balíku → lokálne id tváre), len jednoznačné a jedna k jednej.
    fun pair(
        bundleFaces: List<BundleFace>,
        local: List<Pair<Long, List<RelBox>>>,
        minIou: Float,
    ): Map<Int, Long> {
        val raw = HashMap<Int, Long>()
        bundleFaces.forEachIndexed { i, f -> FaceMatch.best(listOf(f.box), local, minIou)?.let { raw[i] = it } }
        // dve tváre z balíka na jednu lokálnu = nejasné, nepáruje sa ani jedna
        val taken = raw.values.groupingBy { it }.eachCount()
        return raw.filterValues { taken[it] == 1 }
    }

    // Čo zapísať. resolve = lokálne id osoby pre rodinné UUID, current = lokálne priradenie tváre.
    fun writes(
        bundleFaces: List<BundleFace>,
        pairs: Map<Int, Long>,
        policy: NamePolicy,
        resolve: (String) -> Long?,
        current: (Long) -> LocalAssignment?,
    ): List<Write> {
        if (policy == NamePolicy.NONE) return emptyList()
        return pairs.entries.sortedBy { it.key }.mapNotNull { (i, faceId) ->
            val f = bundleFaces[i]
            if (f.ignored) return@mapNotNull null
            val person = f.familyUuid?.let(resolve) ?: return@mapNotNull null
            decide(faceId, person, f.manual && policy == NamePolicy.FULL, current(faceId))
        }
    }

    // rovnaké poradie dôležitosti ako FaceRules.assign v OpApplier (bez návrhov: rozpor = nič)
    fun decide(faceId: Long, person: Long, manual: Boolean, current: LocalAssignment?): Write? {
        val write = Write(faceId, person, manual)
        return when {
            current == null -> write
            current.personId == person -> write.takeIf { manual && !current.manual }
            current.manual -> null
            manual -> write
            else -> null
        }
    }

    // Tváre zapísané z balíka (nová fotka bez vlastných tvárí): mená podľa politiky D5.
    fun forNewFaces(bundleFaces: List<BundleFace>, policy: NamePolicy, resolve: (String) -> Long?): Map<Int, Write?> =
        bundleFaces.indices.associateWith { i ->
            val f = bundleFaces[i]
            val person = if (policy == NamePolicy.NONE) null else f.familyUuid?.let(resolve)
            person?.let { Write(-1, it, f.manual && policy == NamePolicy.FULL) }
        }
}
