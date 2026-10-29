package org.fossify.gallery.helpers

// Pravidlá, kedy smie bežať spracovanie knižnice (rodinné prepojenie F5, spec Časť 3).
// Čistá funkcia bez Android tried, aby sa dala overiť JUnit testom. Stav batérie, nastavenia
// a počet čakajúcich fotiek dodáva IndexingService.
//
// - Plný profil bez voľby „Nové fotky hneď, zvyšok pri nabíjaní“ = správanie ako doteraz (vždy RUN).
// - Ľahký profil = len tváre a poloha, automaticky len pri nabíjaní; ostatné indexery sa nespúšťajú.
// - Voľba „Nové fotky hneď…“: malá dávka (do SMALL_BATCH_MAX fotiek) beží hneď,
//   väčšia čaká na nabíjanie.
// - Ľahký profil alebo zapnutá voľba: pri batérii pod LOW_BATTERY_PCT % (bez nabíjania) sa automatické
//   spracovanie zastaví.
// - Ručné spustenie (ťuk používateľa) rešpektuje len výber indexerov podľa profilu.
// - Jednorazový prepočet odtlačkov tvárí na v2 (FACE_V2) a dopĺňanie údajov tvárí (FACE_META, E1) bežia
//   automaticky VŽDY len pri nabíjaní (v každom profile — patria k tváram), ručne kedykoľvek.
// - E3: detekcia malých tvárí v dlaždiciach (multiScale) len pri nabíjaní alebo v Plnom profile.
object ProcessingPolicy {
    enum class Profile { FULL, LIGHT }

    enum class Indexer { FACES, GEO, QR, OCR, PHASH, CLIP, OTHER, FACE_V2, FACE_META }

    enum class Trigger { AUTO, MANUAL }

    enum class Decision { RUN, WAIT_FOR_CHARGING, STOP_LOW_BATTERY, SKIP_PROFILE }

    // uložené hodnoty v Config.processingProfile
    const val PROFILE_FULL = 0
    const val PROFILE_LIGHT = 1

    const val LOW_BATTERY_PCT = 20
    const val SMALL_BATCH_MAX = 20

    // stav batérie alebo veľkosť dávky sa nepodarilo zistiť
    const val UNKNOWN = -1

    private val LIGHT_INDEXERS = setOf(Indexer.FACES, Indexer.GEO)

    // jednorazové prepočty tvárí — automaticky len pri nabíjaní
    private val CHARGING_ONLY = setOf(Indexer.FACE_V2, Indexer.FACE_META)

    fun profileOf(stored: Int): Profile = if (stored == PROFILE_LIGHT) Profile.LIGHT else Profile.FULL

    // batteryPct: 0–100 alebo UNKNOWN; batchSize: počet čakajúcich fotiek pre daný indexer alebo
    // UNKNOWN (neznáma dávka sa berie ako veľká, teda počká na nabíjanie)
    fun decide(
        profile: Profile,
        newOnlyFirst: Boolean,
        charging: Boolean,
        batteryPct: Int,
        batchSize: Int,
        indexerKind: Indexer,
        trigger: Trigger = Trigger.AUTO,
    ): Decision = when {
        indexerKind in CHARGING_ONLY ->
            if (trigger == Trigger.MANUAL || charging) Decision.RUN else Decision.WAIT_FOR_CHARGING
        profile == Profile.LIGHT && indexerKind !in LIGHT_INDEXERS -> Decision.SKIP_PROFILE
        trigger == Trigger.MANUAL || charging -> Decision.RUN
        profile == Profile.FULL && !newOnlyFirst -> Decision.RUN
        batteryPct in 0 until LOW_BATTERY_PCT -> Decision.STOP_LOW_BATTERY
        profile == Profile.LIGHT -> Decision.WAIT_FOR_CHARGING
        batchSize in 0..SMALL_BATCH_MAX -> Decision.RUN
        else -> Decision.WAIT_FOR_CHARGING
    }

    // treba pre rozhodnutie poznať veľkosť dávky? (jej zistenie číta MediaStore a indexové DB)
    fun needsBatchSize(profile: Profile, newOnlyFirst: Boolean, charging: Boolean, trigger: Trigger): Boolean =
        trigger == Trigger.AUTO && profile == Profile.FULL && newOnlyFirst && !charging

    // má služba počas behu sledovať batériu a nabíjanie?
    fun needsWatching(profile: Profile, newOnlyFirst: Boolean): Boolean =
        profile == Profile.LIGHT || newOnlyFirst

    // … pre konkrétnu úlohu: prepočet na v2 a dopĺňanie údajov tvárí sa po odpojení nabíjačky zastavia vždy
    fun needsWatching(profile: Profile, newOnlyFirst: Boolean, indexerKind: Indexer): Boolean =
        indexerKind in CHARGING_ONLY || needsWatching(profile, newOnlyFirst)

    // E3: detekcia vo viacerých mierkach a dlaždiciach (~5 detekcií a väčšie dekódovanie na fotku) — len pri
    // nabíjaní alebo v Plnom profile; v Ľahkom profile na batérii nie
    fun multiScale(profile: Profile, charging: Boolean): Boolean = charging || profile == Profile.FULL
}
