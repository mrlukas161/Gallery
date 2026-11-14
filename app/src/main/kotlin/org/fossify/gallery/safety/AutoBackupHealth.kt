package org.fossify.gallery.safety

// Zdravie automatických záloh (revízia 11/2026, N8). Čistá logika bez Androidu (AutoBackupHealthTest).
//
// Problém: po poškodení DB vznikne prázdna (SafeOpenHelperFactory ju odloží a SQLite založí novú).
// Týždenná záloha by potom zapísala zálohu s nula osobami, dialóg obnovy by ju ponúkol ako „poslednú“
// a rotácia (4 najnovšie) by za štyri týždne vytlačila všetky dobré.
//
// Riešenie:
//  - kým je zaznamenané neuzavreté poškodenie zálohovanej DB, automatická záloha sa nerobí (shouldSkip);
//  - každá záloha má metadáta (počet osôb, priradení, obľúbených, zdravá áno/nie) v preferenciách aj
//    v samotnom ZIP-e (súbor META_ENTRY); záloha s výrazným poklesom oproti poslednej zdravej je
//    „podozrivá“ (isSuspect) — zapíše sa, ale nikdy nevytlačí poslednú zdravú (toRotate);
//  - ručná záloha (Nastavenia → Zálohovať teraz) bez neuzavretého poškodenia je vedomé potvrdenie
//    súčasného stavu → je zdravá aj po veľkom upratovaní osôb a stane sa novým východiskom;
//  - dialóg obnovy ponúkne len zálohy spred poškodenia, s dátumom a počtom osôb, a predvolí najnovšiu
//    zdravú (candidates).
object AutoBackupHealth {
    const val META_ENTRY = "galeria-zaloha.txt"

    // pokles o viac než 30 % a zároveň aspoň o toľko kusov (malé výkyvy pri zlučovaní osôb nevadia)
    private const val KEEP_RATIO_PERCENT = 70
    private const val PERCENT = 100
    const val MIN_PERSON_DROP = 3
    const val MIN_ASSIGNMENT_DROP = 30

    private const val SEP = "|"
    private const val FIELDS = 5

    // -1 = neznáme (napr. staršia záloha bez metadát)
    data class Meta(
        val stamp: String,
        val persons: Int,
        val assignments: Int,
        val favorites: Int,
        val healthy: Boolean,
    )

    fun encode(meta: Meta): String =
        listOf(meta.stamp, meta.persons, meta.assignments, meta.favorites, if (meta.healthy) 1 else 0).joinToString(SEP)

    fun decode(line: String): Meta? {
        val parts = line.trim().split(SEP)
        if (parts.size != FIELDS) return null
        val stamp = parts.first()
        val numbers = parts.subList(1, FIELDS - 1).mapNotNull { it.toIntOrNull() }
        val healthy = mapOf("1" to true, "0" to false)[parts.last()]
        if (stamp.isEmpty() || healthy == null || numbers.size != FIELDS - 2) return null
        val (persons, assignments, favorites) = numbers
        return Meta(stamp, persons, assignments, favorites, healthy)
    }

    // kým nie je poškodenie zálohovanej DB vyriešené (obnova alebo „Nepýtať sa“), automaticky nezálohovať
    fun shouldSkip(corruptedDbNames: Collection<String>, manual: Boolean): Boolean =
        !manual && corruptedDbNames.any { it in DbFiles.BACKED_UP }

    // výrazný pokles: na nulu, alebo o viac než 30 % a aspoň o minAbs
    fun dropped(before: Int, now: Int, minAbs: Int): Boolean {
        if (before <= 0 || now < 0) return false
        if (now == 0) return true
        return before - now >= minAbs && now * PERCENT < before * KEEP_RATIO_PERCENT
    }

    // baseline = posledná zdravá záloha (null = žiadna → nie je s čím porovnať)
    fun isSuspect(baseline: Meta?, persons: Int, assignments: Int): Boolean {
        if (baseline == null) return false
        return dropped(baseline.persons, persons, MIN_PERSON_DROP) ||
            dropped(baseline.assignments, assignments, MIN_ASSIGNMENT_DROP)
    }

    // ručná záloha bez poškodenia = vedomé potvrdenie stavu; inak rozhodne pokles
    fun isHealthy(baseline: Meta?, persons: Int, assignments: Int, manual: Boolean, corrupted: Boolean): Boolean =
        if (manual && !corrupted) true else !corrupted && !isSuspect(baseline, persons, assignments)

    // Posledná zdravá záloha. Keď v metadátach nie je (preinštalovanie, záloha spred tejto verzie),
    // použijú sa uložené počty poslednej zdravej zálohy (storedAssignments -1 = neznáme).
    fun baseline(metas: Collection<Meta>, storedPersons: Int, storedAssignments: Int): Meta? {
        val newest = metas.filter { it.healthy }.maxByOrNull { it.stamp }
        if (newest != null) return newest
        if (storedPersons <= 0 && storedAssignments <= 0) return null
        return Meta("", storedPersons, storedAssignments, -1, true)
    }

    // Rotácia: ako AutoBackupNames.toRotate (KEEP najnovších z každého druhu), ale súbory najnovšej
    // zálohy, ktorá nie je podozrivá (zdravá alebo bez metadát), sa nezmažú nikdy.
    fun toRotate(names: Collection<String>, metas: Map<String, Meta>, keep: Int = AutoBackupNames.KEEP): List<String> {
        val protectedStamp = names.mapNotNull { AutoBackupNames.stampOf(it) }.distinct()
            .filter { metas[it]?.healthy != false }
            .maxOrNull()
        return AutoBackupNames.toRotate(names, keep).filter { AutoBackupNames.stampOf(it) != protectedStamp }
    }

    // ponuka pri obnove po poškodení
    data class Candidate(val name: String, val createdAt: Long, val meta: Meta?) {
        val suspect: Boolean get() = meta?.healthy == false
    }

    // len zálohy spred prvého neuzavretého poškodenia; najnovšie prvé
    fun candidates(items: List<Pair<String, Long>>, metas: Map<String, Meta>, corruptedAt: Long?): List<Candidate> =
        items.filter { (_, at) -> corruptedAt == null || at < corruptedAt }
            .sortedByDescending { it.second }
            .map { (name, at) -> Candidate(name, at, AutoBackupNames.stampOf(name)?.let { metas[it] }) }

    // predvolená: najnovšia nepodozrivá, inak najnovšia vôbec
    fun recommended(candidates: List<Candidate>): Candidate? =
        candidates.firstOrNull { !it.suspect } ?: candidates.firstOrNull()
}
