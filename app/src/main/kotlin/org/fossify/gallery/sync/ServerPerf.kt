package org.fossify.gallery.sync

import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// Čistá logika rýchlosti a diagnostiky LAN servera (server-rychlost) — bez Android tried, testovaná
// v ServerPerfTest. MediaServer ju len volá; výstup číta GET /api/stats (docs/api/server-pre-pc.md).

// Časy obsluhy podľa endpointu: kruhový buffer posledných `capacity` požiadaviek, z neho p50/p95.
// handler = čas v serve() (bez prenosu tela), total = až po odoslanie celej odpovede.
class LatencyStats(private val capacity: Int = DEFAULT_CAPACITY, private val maxKeys: Int = DEFAULT_MAX_KEYS) {
    data class Summary(
        val count: Long,
        val p50: Long,
        val p95: Long,
        val max: Long,
        val p50Total: Long,
        val p95Total: Long,
    )

    private class Ring(cap: Int) {
        val handler = LongArray(cap)
        val total = LongArray(cap)
        var size = 0
        var pos = 0
        var count = 0L
        var max = 0L
    }

    private val rings = LinkedHashMap<String, Ring>()

    @Synchronized
    fun record(endpoint: String, handlerMs: Long, totalMs: Long) {
        // neznáme cesty (aj bez prihlásenia) nesmú nafúknuť mapu
        val key = if (endpoint in rings || rings.size < maxKeys) endpoint else OTHER
        val r = rings.getOrPut(key) { Ring(capacity) }
        r.handler[r.pos] = handlerMs
        r.total[r.pos] = totalMs
        r.pos = (r.pos + 1) % capacity
        if (r.size < capacity) r.size++
        r.count++
        if (handlerMs > r.max) r.max = handlerMs
    }

    @Synchronized
    fun snapshot(): Map<String, Summary> = rings.mapValues { (_, r) ->
        val h = r.handler.copyOf(r.size)
        val t = r.total.copyOf(r.size)
        Summary(r.count, percentile(h, P50), percentile(h, P95), r.max, percentile(t, P50), percentile(t, P95))
    }

    companion object {
        const val DEFAULT_CAPACITY = 256
        const val DEFAULT_MAX_KEYS = 40
        const val OTHER = "other"
        const val P50 = 50
        const val P95 = 95
        private const val PERCENT = 100

        // nearest-rank percentil (0 pre prázdne pole)
        fun percentile(values: LongArray, p: Int): Long {
            if (values.isEmpty()) return 0L
            val sorted = values.sortedArray()
            val rank = ((p.toLong() * sorted.size + PERCENT - 1) / PERCENT).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

// Posledných `capacity` pomalých požiadaviek (obsluha nad thresholdMs, alebo malá odpoveď, ktorej
// odoslanie trvalo dlho — to ukazuje na sieť, nie na mobil). Veľké originály sa pre prenos neratajú.
class SlowLog(private val capacity: Int = DEFAULT_CAPACITY, private val thresholdMs: Long = DEFAULT_THRESHOLD_MS) {
    data class Entry(
        val at: Long,
        val path: String,
        val status: Int,
        val handlerMs: Long,
        val totalMs: Long,
        val bytes: Long,
        val via: String,
    )

    private val entries = ArrayDeque<Entry>()

    fun isSlow(handlerMs: Long, totalMs: Long, bytes: Long): Boolean =
        handlerMs >= thresholdMs || (totalMs >= thresholdMs && bytes in 0..SMALL_BYTES)

    @Synchronized
    fun offer(e: Entry): Boolean {
        if (!isSlow(e.handlerMs, e.totalMs, e.bytes)) return false
        entries.addLast(e)
        while (entries.size > capacity) entries.removeFirst()
        return true
    }

    // najnovšie prvé
    @Synchronized
    fun list(): List<Entry> = entries.reversed()

    companion object {
        const val DEFAULT_CAPACITY = 20
        const val DEFAULT_THRESHOLD_MS = 500L
        const val SMALL_BYTES = 1024L * 1024
    }
}

// Počítadlá servera pre /api/stats (všetko lacné, bez zámkov okrem bufferov).
class ServerStats {
    val latency = LatencyStats()
    val slow = SlowLog()
    val inFlight = AtomicInteger(0)
    val total = AtomicLong(0)
    val usb = AtomicLong(0)
    val lan = AtomicLong(0)
    val rejectedConnections = AtomicLong(0)
    private val rebuilds = AtomicLong(0)
    private val rebuildTotalMs = AtomicLong(0)
    private val rebuildMaxMs = AtomicLong(0)

    @Volatile
    var rebuildLastMs = 0L
        private set

    @Volatile
    var rebuildLastAt = 0L
        private set

    fun begin(via: String) {
        inFlight.incrementAndGet()
        total.incrementAndGet()
        (if (via == ClientKind.USB) usb else lan).incrementAndGet()
    }

    // požiadavka skončila výnimkou ešte pred odpoveďou
    fun abort() {
        if (inFlight.decrementAndGet() < 0) inFlight.set(0)
    }

    fun end(endpoint: String, entry: SlowLog.Entry) {
        if (inFlight.decrementAndGet() < 0) inFlight.set(0)
        latency.record(endpoint, entry.handlerMs, entry.totalMs)
        slow.offer(entry)
    }

    fun rebuilt(ms: Long, at: Long) {
        rebuilds.incrementAndGet()
        rebuildTotalMs.addAndGet(ms)
        rebuildMaxMs.accumulateAndGet(ms) { a, b -> maxOf(a, b) }
        rebuildLastMs = ms
        rebuildLastAt = at
    }

    val rebuildCount: Long get() = rebuilds.get()
    val rebuildMax: Long get() = rebuildMaxMs.get()
    val rebuildAvg: Long get() = rebuilds.get().let { if (it == 0L) 0L else rebuildTotalMs.get() / it }
}

// odkiaľ prišla požiadavka: 127.0.0.1 / ::1 = USB kábel (adb forward/reverse), inak LAN (Wi-Fi)
object ClientKind {
    const val USB = "usb"
    const val LAN = "lan"

    fun of(remoteAddr: String?): String {
        val a = remoteAddr?.trim()?.removePrefix("/")?.lowercase().orEmpty()
        val loop = a.startsWith("127.") || a == "::1" || a == "0:0:0:0:0:0:0:1" || a == "localhost" ||
            a.startsWith("::ffff:127.")
        return if (loop) USB else LAN
    }
}

// Hlavička Server-Timing pre jednu požiadavku: app = celá obsluha v serve(), ďalšie časti pridá kód
// (q = čakanie vo fronte dekódovania, dec = dekódovanie, jpg = kompresia) a cache = mem/disk/miss.
// Jedna inštancia na vlákno požiadavky (ThreadLocal).
class RequestTiming(private val startNs: Long) {
    private val parts = LinkedHashMap<String, Double>()
    private var cache: String? = null

    fun add(name: String, ms: Double) {
        parts[name] = (parts[name] ?: 0.0) + ms
    }

    fun cache(desc: String) {
        cache = desc
    }

    fun header(nowNs: Long): String {
        val sb = StringBuilder("app;dur=").append(fmt((nowNs - startNs) / NS_PER_MS))
        parts.forEach { (k, v) -> sb.append(", ").append(k).append(";dur=").append(fmt(v)) }
        cache?.let { sb.append(", cache;desc=").append(it) }
        return sb.toString()
    }

    companion object {
        private const val NS_PER_MS = 1_000_000.0
        private val current = ThreadLocal<RequestTiming?>()

        private fun fmt(ms: Double) = String.format(Locale.ROOT, "%.1f", ms)

        fun begin(startNs: Long): RequestTiming = RequestTiming(startNs).also { current.set(it) }

        fun end() = current.remove()

        fun current(): RequestTiming? = current.get()

        // zmeria blok a pripíše ho do Server-Timing aktuálnej požiadavky (mimo požiadavky len vykoná)
        fun <T> measure(name: String, block: () -> T): T {
            val t0 = System.nanoTime()
            try {
                return block()
            } finally {
                current()?.add(name, (System.nanoTime() - t0) / NS_PER_MS)
            }
        }
    }
}

// Prúd tela odpovede, ktorý po zatvorení (NanoHTTPD ho zatvorí po odoslaní, aj pri chybe spojenia)
// raz zavolá onDone s počtom odoslaných bajtov — koniec požiadavky pre štatistiky a ServerActivity.
class FinishStream(inner: InputStream, private val onDone: (Long) -> Unit) : FilterInputStream(inner) {
    private var bytes = 0L
    private var done = false

    override fun read(): Int {
        val r = super.read()
        if (r >= 0) bytes++
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) bytes += n
        return n
    }

    override fun close() {
        try {
            super.close()
        } finally {
            finish()
        }
    }

    @Synchronized
    private fun finish() {
        if (done) return
        done = true
        onDone(bytes)
    }
}

// Odklad akcie po sérii udalostí (obnova indexu po zmenách v MediaStore): spustí sa po quietMs ticha,
// najneskôr maxMs po prvej udalosti série. onEvent vráti, o koľko ms naplánovať beh.
class Debounce(private val quietMs: Long, private val maxMs: Long) {
    private var firstAt = NONE

    @Synchronized
    fun onEvent(now: Long): Long {
        if (firstAt == NONE) firstAt = now
        return minOf(quietMs, firstAt + maxMs - now).coerceAtLeast(0L)
    }

    // beh sa začal — ďalšia udalosť otvorí novú sériu
    @Synchronized
    fun onRun() {
        firstAt = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}

// Kedy držať WakeLock + WifiLock: len počas požiadaviek a holdMs po poslednej. Zámok sa berie
// s časovým limitom (poistka), počas činnosti sa najviac raz za rearmMs predĺži.
class LockHold(val holdMs: Long, private val rearmMs: Long) {
    enum class Action { NONE, ACQUIRE, REARM, RELEASE }

    var held = false
        private set
    private var lastRequest = 0L
    private var lastArm = 0L

    @Synchronized
    fun onRequest(now: Long): Action {
        lastRequest = now
        return when {
            !held -> {
                held = true
                lastArm = now
                Action.ACQUIRE
            }
            now - lastArm >= rearmMs -> {
                lastArm = now
                Action.REARM
            }
            else -> Action.NONE
        }
    }

    // pravidelná kontrola: bežiaca požiadavka (veľký prenos) zámok predĺži, ticho dlhšie ako holdMs ho uvoľní
    @Synchronized
    fun onCheck(now: Long, inFlight: Int): Action = when {
        !held -> Action.NONE
        inFlight > 0 -> {
            lastRequest = now
            if (now - lastArm >= rearmMs) {
                lastArm = now
                Action.REARM
            } else {
                Action.NONE
            }
        }
        now - lastRequest >= holdMs -> {
            held = false
            Action.RELEASE
        }
        else -> Action.NONE
    }

    // o koľko ms sa pozrieť znova (najskôr o minMs)
    @Synchronized
    fun nextCheckIn(now: Long, minMs: Long): Long = (lastRequest + holdMs - now).coerceIn(minMs, holdMs)

    @Synchronized
    fun released() {
        held = false
    }

    @Synchronized
    fun idleMs(now: Long): Long = if (held) now - lastRequest else -1L
}
