package org.fossify.gallery.helpers

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// Koordinácia LAN servera pre PC (sync/MediaServer) so spracovaním knižnice v tom istom procese.
// Server pri každej „pracovnej“ požiadavke zavolá begin()/end() — ping a štatistiky nie, tie chodia
// z otvorenej karty v PC stále a indexovanie by sa inak nikdy nespustilo. Čistá logika bez Android
// tried (ServerActivityTest).
//
// - IndexingService nespúšťa automatické úlohy, kým bol server aktívny za posledných AUTO_QUIET_MS
//   (ručne spustené áno) — autoStartAllowed().
// - Bežiace indexery (aj ľahké) po dokončení aktuálnej fotky počkajú, kým je server aktívny
//   (ServerYield.pause), a po RESUME_QUIET_MS ticha pokračujú.
class ServerActivity(private val clock: () -> Long = System::currentTimeMillis) {
    private val running = AtomicInteger(0)

    // čas začiatku alebo konca poslednej požiadavky; NEVER = server od štartu nič neobsluhoval
    @Volatile
    private var lastMs = NEVER

    fun begin() {
        running.incrementAndGet()
        lastMs = clock()
    }

    fun end() {
        if (running.decrementAndGet() < 0) running.set(0)
        lastMs = clock()
    }

    val inFlight: Int
        get() = running.get()

    // ms od poslednej požiadavky (0 = práve niečo beží); Long.MAX_VALUE = server nič neobsluhoval
    fun idleMs(): Long {
        val last = lastMs
        return when {
            last == NEVER -> Long.MAX_VALUE
            running.get() > 0 -> 0L
            else -> (clock() - last).coerceAtLeast(0L)
        }
    }

    // bol server aktívny za posledných windowMs? Bežiaca požiadavka sa ráta, kým nie je podozrivo stará
    // (STUCK_MS) — keby sa koniec požiadavky nikdy nezapísal, indexovanie nesmie stáť navždy
    fun activeWithin(windowMs: Long): Boolean {
        val last = lastMs
        if (last == NEVER) return false
        val since = (clock() - last).coerceAtLeast(0L)
        return (running.get() > 0 && since < STUCK_MS) || since < windowMs
    }

    // server sa zastavil: nič nečaká (inak by indexery čakali na server, ktorý už nebeží)
    fun reset() {
        running.set(0)
        lastMs = NEVER
    }

    companion object {
        const val AUTO_QUIET_MS = 60_000L
        const val RESUME_QUIET_MS = 30_000L
        const val STUCK_MS = 10 * 60_000L
        private const val NEVER = Long.MIN_VALUE

        // jedna inštancia pre celý proces (server aj IndexingService)
        val SHARED = ServerActivity()

        // smie sa úloha spustiť teraz? Ručné (výslovná voľba používateľa) vždy, automatické len po tichu servera
        fun autoStartAllowed(manual: Boolean, activity: ServerActivity = SHARED): Boolean =
            manual || !activity.activeWithin(AUTO_QUIET_MS)

        // požiadavky, ktoré sa nerátajú ako práca servera (stále pingy z otvorenej karty, diagnostika)
        private val PASSIVE = setOf("/api/ping", "/api/stats", "/favicon.ico", "/api/login", "/api/logout")

        fun countsAsWork(uri: String): Boolean = uri !in PASSIVE
    }
}

// Bežiaci indexer ustúpi serveru: na začiatku každej fotky zavolá pause(). Kým je server aktívny, čaká
// po kúskoch POLL_MS (s kontrolou zastavenia active()). Keď o ťažké miesto (HeavyGate) žiada naliehavá
// úloha (/api/quality, porovnávač), indexer raz zavolá release — uvoľní svoj model aj miesto
// a po čakaní si ich lenivo vezme znova. Poslucháči (notifikácia IndexingService) vedia, kto čaká.
class ServerYield(
    private val activity: ServerActivity,
    private val urgentWaiting: () -> Boolean,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    interface Listener {
        fun onPause(task: String)

        fun onResume(task: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val paused: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // úlohy, ktoré práve čakajú na server (diagnostika /api/stats)
    fun pausedTasks(): List<String> = paused.sorted()

    // true = indexer čakal; vráti sa, keď je server ticho alebo keď úlohu zastavili
    fun pause(task: String, active: () -> Boolean, release: (() -> Unit)? = null): Boolean {
        if (!active() || !activity.activeWithin(ServerActivity.RESUME_QUIET_MS)) return false
        paused.add(task)
        notifyAll { it.onPause(task) }
        var released = false
        try {
            while (active() && activity.activeWithin(ServerActivity.RESUME_QUIET_MS)) {
                if (!released && release != null && urgentWaiting()) {
                    released = true
                    releaseSafely(release)
                }
                sleep(POLL_MS)
            }
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            paused.remove(task)
            notifyAll { it.onResume(task) }
        }
        return true
    }

    // model sa pri ďalšej fotke načíta znova; chyba pri zatváraní nesmie zhodiť beh
    private fun releaseSafely(release: () -> Unit) {
        try {
            release()
        } catch (ignored: RuntimeException) {
        }
    }

    // chyba v notifikácii nesmie zastaviť indexer
    private fun notifyAll(call: (Listener) -> Unit) {
        for (l in listeners) {
            try {
                call(l)
            } catch (ignored: RuntimeException) {
            }
        }
    }

    companion object {
        const val POLL_MS = 500L

        val SHARED = ServerYield(ServerActivity.SHARED, { HeavyGate.SHARED.hasUrgentWaiter })
    }
}
