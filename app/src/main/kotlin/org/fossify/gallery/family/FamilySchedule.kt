package org.fossify.gallery.family

import org.fossify.gallery.helpers.ProcessingPolicy

// Kedy sa synchronizuje a kedy beží okno pri nabíjaní (spec Časť 1 „Kedy“, plán F5 bod 3).
// Čistá logika bez Android tried (FamilyScheduleTest). Stav (spárovanie, batéria, čakajúce
// spracovanie) dodáva FamilyAutoSync a FamilySyncJob.
//
// - Pri otvorení appky (onResume hlavnej obrazovky) krátky pokus, len keď je spárovaný telefón,
//   batéria nie je slabá a od posledného pokusu ubehla aspoň RESUME_MIN_INTERVAL_MS (15 min).
//   Keď vo fronte niečo čaká na odoslanie, skúsi sa hneď — len nie častejšie ako raz za
//   RESUME_PENDING_MIN_INTERVAL_MS (návraty z prehliadača by inak zakaždým hľadali telefón).
// - Okno pri nabíjaní: JobScheduler (nabíjanie, pri spárovaní aj nemeraná sieť). Okno trvá najviac
//   WINDOW_MS a skladá sa z krátkych behov jobu (SEGMENT_MS), každý pod 10-minútovým limitom
//   bežného jobu (Android 14–16). Nič nebeží trvalo: po okne ďalšie najskôr o NEXT_WINDOW_DELAY_MS.
// - V tom istom okne pri nabíjaní sa dobehne aj spracovanie knižnice odložené na nabíjanie.
// - Pri batérii pod ProcessingPolicy.LOW_BATTERY_PCT % bez nabíjania sa nespúšťa nič.
object FamilySchedule {
    const val WINDOW_MS = 60 * 60 * 1000L
    const val SEGMENT_MS = 9 * 60 * 1000L
    const val RETRY_INTERVAL_MS = 2 * 60 * 1000L
    const val NEXT_WINDOW_DELAY_MS = 6 * 60 * 60 * 1000L
    const val RESUME_MIN_INTERVAL_MS = 15 * 60 * 1000L
    const val RESUME_PENDING_MIN_INTERVAL_MS = 60 * 1000L
    const val RESUME_TIMEOUT_MS = 5_000L

    // odložené operácie (napr. z obnovy zálohy, čakajú na fotky) sa skúšajú znova najviac takto často
    const val RETRY_PENDING_INTERVAL_MS = 30 * 60 * 1000L

    data class Conditions(
        val paired: Boolean,
        val processingPending: Boolean,
        val charging: Boolean,
        val batteryPct: Int,
        val unmetered: Boolean,
    )

    // podmienky jobu v JobScheduler
    data class Constraints(val requiresCharging: Boolean, val requiresUnmetered: Boolean)

    // čo spustiť v jednom behu jobu
    data class Plan(val sync: Boolean, val process: Boolean) {
        val isEmpty: Boolean get() = !sync && !process
    }

    sealed interface Next {
        // ďalší beh toho istého okna
        data object Continue : Next

        // okno skončilo, ďalšie najskôr o delayMs
        data class Later(val delayMs: Long) : Next

        // nič nie je spárované a nič nečaká na spracovanie → job zrušiť
        data object Cancel : Next
    }

    fun lowBattery(charging: Boolean, batteryPct: Int): Boolean =
        !charging && batteryPct in 0 until ProcessingPolicy.LOW_BATTERY_PCT

    // job má zmysel, len keď je s kým synchronizovať alebo čo dobehnúť pri nabíjaní
    fun shouldSchedule(paired: Boolean, processingPending: Boolean): Boolean = paired || processingPending

    // nemeraná sieť je potrebná len na synchronizáciu; samotné spracovanie stačí pri nabíjaní
    fun constraints(paired: Boolean): Constraints = Constraints(requiresCharging = true, requiresUnmetered = paired)

    fun plan(c: Conditions): Plan {
        if (!c.charging || lowBattery(c.charging, c.batteryPct)) return Plan(sync = false, process = false)
        return Plan(sync = c.paired && c.unmetered, process = c.processingPending)
    }

    // outboxPending = vo fronte čaká niečo na odoslanie (fotky, zrušené prepojenie)
    fun shouldTryOnResume(
        paired: Boolean,
        charging: Boolean,
        batteryPct: Int,
        lastAttemptAt: Long,
        now: Long,
        outboxPending: Boolean = false,
    ): Boolean {
        val interval = if (outboxPending) RESUME_PENDING_MIN_INTERVAL_MS else RESUME_MIN_INTERVAL_MS
        // posunuté hodiny (posledný pokus „v budúcnosti“) nesmú pokusy zablokovať
        val paused = lastAttemptAt <= 0 || lastAttemptAt > now || now - lastAttemptAt >= interval
        return paired && !lowBattery(charging, batteryPct) && paused
    }

    // Začiatok okna pre tento beh: pokračuje sa v uloženom okne, kým neuplynie WINDOW_MS,
    // inak (prvý beh, staré okno, posunuté hodiny) začne nové okno teraz.
    fun windowStart(storedStart: Long, now: Long): Long =
        if (storedStart <= 0 || storedStart > now || now - storedStart >= WINDOW_MS) now else storedStart

    // koniec aktuálneho behu jobu: koniec okna alebo SEGMENT_MS od začiatku behu, čo nastane skôr
    fun segmentEnd(windowStart: Long, segmentStart: Long): Long =
        minOf(windowStart + WINDOW_MS, segmentStart + SEGMENT_MS)

    // Po behu jobu: attempted = v tomto behu sa synchronizovalo (plan.sync), allSynced = so všetkými
    // spárovanými telefónmi prebehla synchronizácia celá. Bez pokusu sa okno nepredlžuje, aby sa job
    // nezacyklil, keď appka a JobScheduler vidia sieť inak.
    fun next(
        paired: Boolean,
        processingPending: Boolean,
        windowStart: Long,
        now: Long,
        allSynced: Boolean,
        attempted: Boolean,
    ): Next = when {
        !shouldSchedule(paired, processingPending) -> Next.Cancel
        paired && attempted && !allSynced && now - windowStart < WINDOW_MS -> Next.Continue
        else -> Next.Later(NEXT_WINDOW_DELAY_MS)
    }
}
