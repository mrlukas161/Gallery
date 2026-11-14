package org.fossify.gallery.sync

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

// Ochrana servera pre PC (fix068-web), čistá logika bez Android tried (ServerGuardTest).

// Kto sa smie pripojiť: len klient z lokálnej siete – loopback (adb forward), súkromné IPv4 (Wi-Fi doma,
// hotspot, Zdieľanie pripojenia cez USB: 10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10)
// a IPv6 ULA (fc00::/7). Mobilné dáta, VPN s verejnou adresou a iné verejné adresy = odmietnuť.
object ClientNet {
    // IPv4 v bodkovom zápise alebo IPv6 (aspoň jedna dvojbodka) – nikdy meno, ktoré by išlo do DNS
    private val LITERAL = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*)$")
    private const val ULA_MASK = 0xfe
    private const val ULA_PREFIX = 0xfc

    // remoteAddr = hlavička remote-addr z NanoHTTPD (číselná adresa, IPv6 aj so „%rozhranie“)
    fun isLocal(remoteAddr: String?): Boolean {
        val a = remoteAddr?.trim()?.removePrefix("/")?.removeSurrounding("[", "]")?.substringBefore('%').orEmpty()
        // len číselná adresa – getByName s menom by robil DNS dotaz
        if (a.isEmpty() || !LITERAL.matches(a)) return false
        val ip = try {
            InetAddress.getByName(a)
        } catch (ignored: UnknownHostException) {
            return false
        }
        if (ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress) return true
        return ip is Inet6Address && (ip.address[0].toInt() and ULA_MASK) == ULA_PREFIX
    }
}

// Hádanie PIN-u: rátajú sa RÔZNE zlé tokeny (stará karta posiela stále ten istý starý PIN a nemá nič zamknúť).
// - z jednej adresy PER_IP zlých -> tá adresa má pauzu (1 min, potom 2, 4 … najviac 30 min);
// - spolu GLOBAL zlých (zo všetkých adries) -> 10 min sa PIN neprijme od nikoho nového; adresy, ktoré sa
//   predtým prihlásili správnym PIN-om, a prihlásené prehliadače (cookie) fungujú ďalej;
// - spolu HARD_LIMIT zlých za beh servera -> server sa zastaví (šanca uhádnuť 6-miestny PIN ~ 100 : 900 000).
// Útočník na sieti tak server rýchlo nevypne a hádať vie len pomaly.
class PinGuard(private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val PER_IP = 5
        const val IP_PAUSE_MS = 60_000L
        const val IP_PAUSE_MAX_MS = 30 * 60_000L
        const val GLOBAL = 20
        const val GLOBAL_PAUSE_MS = 10 * 60_000L
        const val HARD_LIMIT = 100
        private const val KEEP = 64
        private const val MAX_IPS = 256
        private const val MAX_SHIFT = 10
        private const val INITIAL_IPS = 16
        private const val LOAD_FACTOR = 0.75f
    }

    enum class Verdict { OK, BAD, PAUSED, LOCKED }

    // GLOBAL_PAUSE = práve sa začala spoločná pauza (oznámiť v telefóne), HARD_LOCK = server sa má zastaviť
    enum class Event { GLOBAL_PAUSE, HARD_LOCK }

    class Result(val verdict: Verdict, val event: Event? = null, val retryMs: Long = 0L)

    private class IpState {
        val bad = HashSet<String>()
        var strikes = 0
        var pausedUntil = 0L
    }

    private val ips = object : LinkedHashMap<String, IpState>(INITIAL_IPS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IpState>?): Boolean = size > MAX_IPS
    }
    private val trusted = HashSet<String>()
    private val allBad = HashSet<String>()
    private var globalPausedUntil = 0L
    private var nextGlobalAt = GLOBAL

    @Volatile
    var hardLocked = false
        private set

    // correct = PIN sedí (porovnané v konštantnom čase volajúcim); počas pauzy sa výsledok neprezradí
    @Synchronized
    fun attempt(ip: String, token: String, correct: Boolean): Result {
        val now = clock()
        val st = ips.getOrPut(ip) { IpState() }
        val waitUntil = maxOf(st.pausedUntil, if (ip in trusted) 0L else globalPausedUntil)
        return when {
            hardLocked -> Result(Verdict.LOCKED)
            now < waitUntil -> Result(Verdict.PAUSED, retryMs = waitUntil - now)
            correct -> {
                if (trusted.size < MAX_IPS) trusted.add(ip)
                st.bad.clear()
                Result(Verdict.OK)
            }
            // ten istý zlý token znova (stará karta) sa neráta
            !st.bad.add(token.take(KEEP)) -> Result(Verdict.BAD)
            else -> countBad(st, token.take(KEEP), now)
        }
    }

    private fun countBad(st: IpState, key: String, now: Long): Result {
        allBad.add(key)
        if (allBad.size >= HARD_LIMIT) {
            hardLocked = true
            return Result(Verdict.LOCKED, Event.HARD_LOCK)
        }
        if (st.bad.size >= PER_IP) {
            val pause = (IP_PAUSE_MS shl st.strikes.coerceAtMost(MAX_SHIFT)).coerceAtMost(IP_PAUSE_MAX_MS)
            st.strikes++
            st.pausedUntil = now + pause
            st.bad.clear()
        }
        if (allBad.size >= nextGlobalAt) {
            nextGlobalAt += GLOBAL
            globalPausedUntil = now + GLOBAL_PAUSE_MS
            return Result(Verdict.BAD, Event.GLOBAL_PAUSE)
        }
        return Result(Verdict.BAD)
    }
}

// Automatické vypnutie servera po nečinnosti: „práca“ = každá požiadavka okrem pingu a štatistík, plus ping
// s ?active=1 (web ho posiela, kým sa používateľ v karte hýbe). Kým beží požiadavka (napr. dlhý ZIP), server
// nie je nečinný – okrem podozrivo starej (STUCK_MS), ktorej koniec sa nikdy nezapísal.
class IdleClock(private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val IDLE_STOP_MS = 30 * 60_000L
        const val STUCK_MS = 3 * 60 * 60_000L
    }

    private var running = 0
    private var lastMs = clock()
    private var runningSince = 0L

    @Synchronized
    fun touch() {
        lastMs = clock()
    }

    @Synchronized
    fun begin() {
        if (running == 0) runningSince = clock()
        running++
        lastMs = clock()
    }

    @Synchronized
    fun end() {
        if (running > 0) running--
        lastMs = clock()
    }

    @Synchronized
    fun idleMs(): Long {
        val now = clock()
        if (running > 0 && now - runningSince < STUCK_MS) return 0L
        return (now - lastMs).coerceAtLeast(0L)
    }

    fun shouldStop(limitMs: Long = IDLE_STOP_MS): Boolean = idleMs() >= limitMs
}
