package org.fossify.gallery.family

// Ako sa zapíšu mená (priradenia osôb) z balíka k fotke (D5).
enum class NamePolicy {
    FULL, // ako v balíku (ručné ostanú ručné)
    AUTOMATIC, // len ako automatické (manual = false): balíku zodpovedá rovnako dobre aj iná fotka
    NONE, // tváre bez mien: balíku zodpovedá lepšie iná fotka (séria bez EXIF)
}

// Séria záberov bez EXIF (D5): pHash susedných snímok sa líši o pár bitov a bez dátumu sa zhoda opiera len
// o pHash ≤ 3. Pred zápisom sa overí, že balíku nezodpovedá lepšie alebo rovnako dobre iná nedávna fotka
// (a že vybranej fotke nezodpovedá lepšie iný čakajúci balík). Čistá logika (InboxMatcherTest).
object SeriesGuard {
    // bundle = fotka z balíka, chosen = lokálna fotka, ku ktorej sa balík priraďuje,
    // otherLocals = ostatné nedávne lokálne fotky, otherBundles = ostatné čakajúce balíky
    fun policy(
        bundle: FuzzyMatch.Fingerprint,
        chosen: FuzzyMatch.Fingerprint,
        otherLocals: Collection<FuzzyMatch.Fingerprint>,
        otherBundles: Collection<FuzzyMatch.Fingerprint> = emptyList(),
    ): NamePolicy {
        val mine = FuzzyMatch.score(bundle, chosen) ?: return NamePolicy.NONE
        val rivals = otherLocals.mapNotNull { FuzzyMatch.score(bundle, it) } +
            otherBundles.mapNotNull { FuzzyMatch.score(it, chosen) }
        return when {
            rivals.any { it < mine } -> NamePolicy.NONE
            rivals.any { it.compareTo(mine) == 0 } -> NamePolicy.AUTOMATIC
            else -> NamePolicy.FULL
        }
    }
}

// Párovanie lokálnej fotky s čakajúcimi balíkmi (FamilyInbox.check, F4 + D5). Čistá logika (InboxMatcherTest).
object InboxMatcher {
    class Waiting(
        val id: String,
        val contentKey: String,
        val fingerprint: FuzzyMatch.Fingerprint?, // null = balík bez pHash (len presná zhoda)
        val receivedAt: Long,
    )

    data class Choice(val id: String, val match: BundleMatch, val names: NamePolicy)

    // localKey = obsahový kľúč lokálnej fotky, local = jej pHash a dátum (lenivo; null = nie je obrázok),
    // mtime = čas súboru (záloha skúša len balíky z podobného obdobia), otherLocals = ostatné nedávne lokálne
    // fotky (počítajú sa len pri zhode cez zálohu)
    fun choose(
        localKey: String?,
        local: () -> FuzzyMatch.Fingerprint?,
        mtime: Long,
        waiting: List<Waiting>,
        otherLocals: () -> Collection<FuzzyMatch.Fingerprint>,
    ): Choice? {
        val exact = localKey?.let { key -> waiting.firstOrNull { it.contentKey == key } }
        if (exact != null) return Choice(exact.id, BundleMatch.EXACT, NamePolicy.FULL)
        val candidates = waiting.filter { BundleRules.inFuzzyWindow(mtime, it.receivedAt) }
            .mapNotNull { w -> w.fingerprint?.let { w.id to it } }
            .toMap()
        val fingerprint = if (candidates.isEmpty()) null else local()
        val bestId = fingerprint?.let { FuzzyMatch.findBest(it, candidates) } ?: return null
        val rivals = candidates.filterKeys { it != bestId }.values
        val names = SeriesGuard.policy(candidates.getValue(bestId), fingerprint, otherLocals(), rivals)
        return Choice(bestId, BundleMatch.FUZZY, names)
    }
}
