package org.fossify.gallery.sync

import java.io.File
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

// Čistá logika cache a dekódovania pre LAN server (server-rychlost) — bez Android tried
// (ServerCachesTest). Disková cache používa len java.io.

// Fronta dekódovaní s limitom: najviac `parallel` naraz, najviac `maxWaiting` čaká; ďalšia požiadavka
// je hneď odmietnutá (server odpovie 503 + Retry-After). Čakajúci, ktorého klient medzitým zavrel
// spojenie (cancelled), sa vzdá ešte pred dekódovaním — zrušený náhľad nezaberie CPU ani pamäť.
class DecodeQueue(private val parallel: Int, private val maxWaiting: Int, private val pollMs: Long = DEFAULT_POLL_MS) {
    sealed class Outcome<out T> {
        class Done<T>(val value: T) : Outcome<T>()

        object Rejected : Outcome<Nothing>()

        object Cancelled : Outcome<Nothing>()
    }

    private val permits = Semaphore(parallel, true)
    private val waitingNow = AtomicInteger(0)
    val rejected = AtomicLong(0)
    val cancelled = AtomicLong(0)
    val completed = AtomicLong(0)

    val running: Int get() = parallel - permits.availablePermits()
    val waiting: Int get() = waitingNow.get()

    fun <T> run(isCancelled: () -> Boolean, block: () -> T): Outcome<T> {
        if (!permits.tryAcquire(0, TimeUnit.MILLISECONDS)) {
            if (waitingNow.incrementAndGet() > maxWaiting) {
                waitingNow.decrementAndGet()
                rejected.incrementAndGet()
                return Outcome.Rejected
            }
            try {
                if (!awaitPermit(isCancelled)) {
                    cancelled.incrementAndGet()
                    return Outcome.Cancelled
                }
            } finally {
                waitingNow.decrementAndGet()
            }
        }
        try {
            if (isCancelled()) {
                cancelled.incrementAndGet()
                return Outcome.Cancelled
            }
            val v = block()
            completed.incrementAndGet()
            return Outcome.Done(v)
        } finally {
            permits.release()
        }
    }

    // false = zrušené počas čakania (miesto sa nevzalo)
    private fun awaitPermit(isCancelled: () -> Boolean): Boolean {
        try {
            while (!permits.tryAcquire(pollMs, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) return false
            }
            return true
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }

    companion object {
        const val DEFAULT_POLL_MS = 100L
    }
}

// Ako dekódovať obrázok na dlhú hranu maxPx: presná cieľová veľkosť (bez zväčšovania) a vzorkovanie
// (mocnina 2) tak, aby dekódovaný medzivýsledok nebol menší ako cieľ. Ochrana pamäte: keby medzivýsledok
// mal viac ako guardPx pixelov (HEIC 50 Mpx = ~200 MB), vzorkuje sa ešte raz a cieľ sa zmenší —
// výsledok potom môže byť o kúsok menší ako maxPx (8160 px -> 4080 namiesto 4096), nikdy väčší.
data class DecodePlan(val sample: Int, val width: Int, val height: Int) {
    companion object {
        const val GUARD_PX = 25_000_000L

        // rozmery w×h zmenšené tak, aby dlhá hrana bola najviac maxPx (pomer strán zachovaný, aspoň 1 px)
        fun fit(w: Int, h: Int, maxPx: Int): Pair<Int, Int> {
            val long = maxOf(w, h)
            if (long <= maxPx || long <= 0) return w to h
            val sc = maxPx.toDouble() / long
            val fw = (w * sc).roundToLong().toInt().coerceIn(1, maxPx)
            val fh = (h * sc).roundToLong().toInt().coerceIn(1, maxPx)
            return fw to fh
        }

        fun of(w: Int, h: Int, maxPx: Int, guardPx: Long = GUARD_PX): DecodePlan {
            if (w <= 0 || h <= 0 || maxPx <= 0) return DecodePlan(1, w, h)
            var (tw, th) = fit(w, h, maxPx)
            var s = 1
            while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
            while ((w / s).toLong() * (h / s) > guardPx && w / (s * 2) > 0 && h / (s * 2) > 0) {
                s *= 2
                val f = fit(w / s, h / s, maxPx)
                tw = minOf(tw, f.first)
                th = minOf(th, f.second)
            }
            return DecodePlan(s, tw, th)
        }
    }
}

// Poradie súborov diskovej cache (LRU) a ich súčet: čo zmazať, keď sa prekročí maxBytes.
class DiskLruIndex(private val maxBytes: Long) {
    private val map = LinkedHashMap<String, Long>(INITIAL, LOAD, true)
    private var total = 0L

    // existujúce súbory od najstaršie použitého; vráti tie, ktoré sa nezmestia (zmazať)
    @Synchronized
    fun load(entries: List<Pair<String, Long>>): List<String> {
        map.clear()
        total = 0
        entries.forEach { (name, size) -> add(name, size) }
        return trim(null)
    }

    // true = súbor je v cache (a je teraz najnovšie použitý)
    @Synchronized
    fun touch(name: String): Boolean = map[name] != null

    // nový súbor; vráti mená na zmazanie (najstaršie). Súbor väčší ako celá cache sa nezapíše (vráti sa sám).
    @Synchronized
    fun put(name: String, size: Long): List<String> {
        if (size > maxBytes) return listOf(name)
        add(name, size)
        return trim(name)
    }

    @Synchronized
    fun remove(name: String) {
        map.remove(name)?.let { total -= it }
    }

    @Synchronized
    fun totalBytes(): Long = total

    @Synchronized
    fun count(): Int = map.size

    private fun add(name: String, size: Long) {
        map.remove(name)?.let { total -= it }
        map[name] = size
        total += size
    }

    private fun trim(keep: String?): List<String> {
        val out = ArrayList<String>()
        val it = map.entries.iterator()
        while (total > maxBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == keep) continue
            total -= e.value
            out.add(e.key)
            it.remove()
        }
        return out
    }

    private companion object {
        const val INITIAL = 64
        const val LOAD = 0.75f
    }
}

// Disková cache veľkých náhľadov a zmenšených JPEG (cacheDir/pc_thumbs): kľúč id+mtime+size+max+q,
// LRU podľa DiskLruIndex. init() pri štarte servera (na pozadí) zmaže nedopísané súbory a oreže cache
// na limit. Chyby disku sa len prejavia ako miss — server dekóduje znova.
class ThumbDiskCache(
    private val dir: File,
    maxBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val index = DiskLruIndex(maxBytes)

    @Volatile
    private var ready = false
    val hits = AtomicLong(0)
    val misses = AtomicLong(0)
    private val tmpSeq = AtomicLong(0)

    fun init() {
        try {
            dir.mkdirs()
            val files = dir.listFiles().orEmpty()
            files.filter { it.name.endsWith(TMP) }.forEach { it.delete() }
            val entries = files.filter { it.isFile && it.name.endsWith(EXT) }
                .sortedBy { it.lastModified() }
                .map { it.name to it.length() }
            index.load(entries).forEach { File(dir, it).delete() }
            ready = true
        } catch (ignored: SecurityException) {
        }
    }

    fun get(key: String): ByteArray? {
        val name = nameOf(key)
        if (!ready || !index.touch(name)) {
            misses.incrementAndGet()
            return null
        }
        val f = File(dir, name)
        val bytes = try {
            f.readBytes()
        } catch (ignored: IOException) {
            null
        }
        if (bytes == null) {
            index.remove(name)
            misses.incrementAndGet()
            return null
        }
        // poradie LRU prežije reštart cez čas súboru (zapisuje sa najviac raz za hodinu)
        val now = clock()
        if (now - f.lastModified() > TOUCH_EVERY_MS) f.setLastModified(now)
        hits.incrementAndGet()
        return bytes
    }

    fun put(key: String, bytes: ByteArray) {
        if (!ready) return
        val name = nameOf(key)
        val tmp = File(dir, "$name.${tmpSeq.incrementAndGet()}$TMP")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(dir, name))) {
                tmp.delete()
                return
            }
        } catch (ignored: IOException) {
            tmp.delete()
            return
        }
        index.put(name, bytes.size.toLong()).forEach { File(dir, it).delete() }
    }

    fun files(): Int = index.count()

    fun bytes(): Long = index.totalBytes()

    companion object {
        const val EXT = ".jpg"
        const val TMP = ".tmp"
        private const val NAME_LEN = 32
        private const val TOUCH_EVERY_MS = 60L * 60 * 1000

        fun nameOf(key: String): String = sha1Hex(key).take(NAME_LEN) + EXT
    }
}

// Kľúč odpovede /api/groups: keď sa nezmenil index servera (gen), výsledok CompareRepository,
// skryté skupiny ani cache skóre porovnávača, telo je rovnaké -> ETag sedí -> 304 bez výpočtu.
data class GroupsKey(
    val epoch: String,
    val gen: Long,
    val repo: Long,
    val hidden: Long,
    val scores: Long,
    val v2: Boolean,
) {
    fun etag(): String = ETags.of("g", epoch, gen, repo, hidden, scores, if (v2) "2" else "1")
}

// posledné telo /api/groups pre každý formát (v=2 aj pôvodný)
class GroupsCache {
    private val bodies = HashMap<Boolean, Pair<GroupsKey, String>>()
    val hits = AtomicLong(0)
    val misses = AtomicLong(0)

    @Synchronized
    fun get(key: GroupsKey): String? {
        val body = bodies[key.v2]?.takeIf { it.first == key }?.second
        (if (body != null) hits else misses).incrementAndGet()
        return body
    }

    @Synchronized
    fun put(key: GroupsKey, body: String) {
        bodies[key.v2] = key to body
    }
}

// verzia podľa identity objektu: iný objekt (prepočítané skupiny, zmenené skryté skupiny) = nová verzia
class IdentityVersion {
    private var last: Any? = null
    private var version = 0L

    @Synchronized
    fun of(obj: Any?): Long {
        if (version == 0L || obj !== last) {
            last = obj
            version++
        }
        return version
    }
}
