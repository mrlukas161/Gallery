package org.fossify.gallery.family

// Čo smie odísť do prepojeného telefónu (spec 2.5: zamknuté a vylúčené priečinky sa neposielajú, ani výsledky
// z nich). Čistá logika bez Androidu (PublishRulesTest), databázy dodáva OpRecorder.
//
// Osoba (jej meno aj samotná existencia) sa druhému telefónu ukáže až vtedy, keď má aspoň jednu tvár
// z povoleného priečinka. Osoba pomenovaná len na fotkách v zamknutom priečinku tak na druhom telefóne
// nevznikne. Skupina odíde len s už zverejnenými členmi a bez nich vôbec.
object PublishRules {
    // má osoba aspoň jednu tvár z povoleného priečinka?
    fun hasAllowedFace(paths: Sequence<String>, allows: (String) -> Boolean): Boolean = paths.any(allows)

    // Členovia skupiny pre GroupSet: len zverejnené osoby (published = rodinné UUID alebo null). null = skupina
    // nemá žiadneho zverejneného člena, neposiela sa.
    fun groupMembers(localIds: List<Long>, published: (Long) -> String?): List<String>? =
        localIds.mapNotNull(published).distinct().takeIf { it.isNotEmpty() }

    // Lokálne odobratí členovia skupiny (rodinné UUID): kto v skupine bol (známy zo skoršieho GroupSet tu alebo
    // z druhého telefónu) a teraz nie je. Opätovne pridaný člen zo zoznamu vypadne. Prijatý GroupSet z druhého
    // telefónu ich potom späť nepridá (celý zoznam členov by inak odobratého člena potichu vrátil).
    fun removedMembers(known: Set<String>, current: Set<String>, removedBefore: Set<String>): Set<String> =
        (removedBefore + (known - current)) - current

    // zoznam UUID v tabuľke meta (oddelené čiarkou; UUID čiarku neobsahuje)
    fun encodeSet(values: Set<String>): String = values.sorted().joinToString(",")

    fun decodeSet(text: String?): Set<String> =
        text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
