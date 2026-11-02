package org.fossify.gallery.sync

import org.json.JSONException
import org.json.JSONObject

// Čistá logika stavu PC sync manažéra (server-rychlost) — bez Android tried (SyncLogicTest).
// SyncStore ju používa pod svojím zámkom; JSON operácie bežia mimo neho.

// História zmien stavu (značky na zmazanie, „✓ v PC“, stav skupín) po stateGen pre /api/state?since=N.
// Každé zvýšenie stateGen zapíše zmenené cesty; null = zmenilo sa všetko (napr. „zrušiť všetky značky“).
class StateLog(private val capacity: Int = DEFAULT_CAPACITY) {
    private class Entry(val gen: Long, val paths: Set<String>?)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(gen: Long, paths: Collection<String>?) {
        entries.addLast(Entry(gen, paths?.toHashSet()))
        while (entries.size > capacity) entries.removeFirst()
    }

    // cesty zmenené v generáciách (from, current]; null = klient si má vziať celý stav (stará, budúca
    // alebo neznáma generácia — napr. po reštarte servera —, chýba medzičlánok, „zmenilo sa všetko“)
    @Synchronized
    fun since(from: Long, current: Long): Set<String>? {
        if (from == current) return emptySet()
        if (from > current || from < 0) return null
        val newer = entries.filter { it.gen > from && it.gen <= current }
        if (newer.isEmpty() || newer.first().gen != from + 1 || newer.last().gen != current) return null
        val out = HashSet<String>()
        var prev = from
        for (e in newer) {
            if (e.gen != prev + 1) return null
            out.addAll(e.paths ?: return null)
            prev = e.gen
        }
        return out
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

// Fronta „✓ v PC“ v pamäti: posledný originál sa len zaradí (cesta -> čas), do prefs ide dávka naraz.
class SyncedBatch {
    private val pending = LinkedHashMap<String, Long>()

    @Synchronized
    fun add(paths: Collection<String>, ts: Long) {
        paths.forEach { if (it.isNotEmpty()) pending[it] = ts }
    }

    // vyberie celú dávku (a frontu vyprázdni)
    @Synchronized
    fun drain(): Map<String, Long> {
        if (pending.isEmpty()) return emptyMap()
        val out = LinkedHashMap(pending)
        pending.clear()
        return out
    }

    @Synchronized
    fun size(): Int = pending.size
}

// mapa „✓ v PC“ (cesta -> čas ms) <-> JSON v prefs (formát sa nemení: {"cesta": čas, …})
object SyncedJson {
    fun encode(map: Map<String, Long>): String {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        return o.toString()
    }

    fun decode(raw: String?): HashMap<String, Long> {
        if (raw.isNullOrEmpty()) return HashMap()
        return try {
            val o = JSONObject(raw)
            val out = HashMap<String, Long>(o.length())
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.optLong(k)
            }
            out
        } catch (ignored: JSONException) {
            HashMap()
        }
    }
}
