package org.fossify.gallery.sync

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

// Perzistentný stav PC sync manažéra: fotky označené z PC na zmazanie + evidencia už stiahnutých do PC.
// Kľúč = plná cesta súboru (stabilná naprieč reštartami). Číta ho MediaServer (API) aj MainActivity
// (potvrdenie zmazania na telefóne, PcMarkedPrompt). Každá zmena zvýši state_gen — web podľa neho
// (cez /api/ping) vie, že má obnoviť značky.
//
// server-rychlost: „✓ v PC“ drží v pamäti SyncedStore (nižšie). Zmeny sa zapisujú aj do StateLog
// (/api/state?since).
object SyncStore {
    const val PREFS = "galeria_pcsync"
    private const val KEY_MARKED = "marked_delete"
    internal const val KEY_SYNCED = "synced_json"
    internal const val KEY_STATE_GEN = "state_gen"

    // nový beh servera posunie stateGen o toľko — generácia, ktorú klient videl pred pádom procesu
    // (zápis apply() sa nestihol), sa tak nikdy nezhoduje s novou
    private const val NEW_RUN_GAP = 100L

    internal val log = StateLog()

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun stateGen(p: SharedPreferences): Long = p.getLong(KEY_STATE_GEN, 0L)

    @Synchronized
    fun markedPaths(p: SharedPreferences): Set<String> =
        p.getStringSet(KEY_MARKED, emptySet()) ?: emptySet()

    // dávkové označenie (web posiela naraz celý výber / skupinu); vráti cesty, ktorým sa stav naozaj zmenil
    @Synchronized
    fun setMarked(p: SharedPreferences, paths: Collection<String>, marked: Boolean): List<String> {
        val cur = HashSet(markedPaths(p))
        val changed = paths.filter { if (marked) cur.add(it) else cur.remove(it) }
        if (changed.isNotEmpty()) p.edit().putStringSet(KEY_MARKED, cur).bumpGen(p, changed).apply()
        return changed
    }

    @Synchronized
    fun removeMarked(p: SharedPreferences, paths: Collection<String>) {
        val cur = HashSet(markedPaths(p))
        val gone = paths.filter { cur.remove(it) }
        if (gone.isNotEmpty()) p.edit().putStringSet(KEY_MARKED, cur).bumpGen(p, gone).apply()
    }

    @Synchronized
    fun clearMarked(p: SharedPreferences) {
        p.edit().putStringSet(KEY_MARKED, emptySet()).bumpGen(p, null).apply()
    }

    // nový beh servera (aj po reštarte procesu): história zmien je prázdna a stateGen poskočí
    @Synchronized
    fun startNewRun(p: SharedPreferences) {
        log.clear()
        p.edit().putLong(KEY_STATE_GEN, stateGen(p) + NEW_RUN_GAP).apply()
    }

    // cesty zmenené od generácie `from` (null = treba celý stav)
    @Synchronized
    fun changedSince(p: SharedPreferences, from: Long): Set<String>? = log.since(from, stateGen(p))
}

// server-rychlost: „✓ v PC“ v pamäti (živá mapa, načíta sa z prefs raz). Stiahnutý originál sa len
// zaradí do fronty (queueSynced) a do prefs ide dávka naraz (flushSynced, MediaServer každé ~3 s
// a pri zastavení) — JSON všetkých ciest sa skladá MIMO zámku SyncStore, takže ping, list, state ani
// mark naň nečakajú. stateGen sa zvýši raz za dávku.
object SyncedStore {
    @Volatile
    private var synced: ConcurrentHashMap<String, Long>? = null
    private val loadLock = Any()
    private val flushLock = Any()
    private val pending = SyncedBatch()

    // živá mapa „✓ v PC“ (cesta -> čas ms); číta sa bez zámku
    fun syncedMap(p: SharedPreferences): Map<String, Long> = live(p)

    private fun live(p: SharedPreferences): ConcurrentHashMap<String, Long> {
        synced?.let { return it }
        synchronized(loadLock) {
            synced?.let { return it }
            val raw = synchronized(SyncStore) { p.getString(SyncStore.KEY_SYNCED, null) }
            return ConcurrentHashMap(SyncedJson.decode(raw)).also { synced = it }
        }
    }

    // „✓ v PC“ hneď do pamäti, do prefs až dávkou (flushSynced)
    fun queueSynced(p: SharedPreferences, paths: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (paths.isEmpty()) return
        val m = live(p)
        paths.forEach { if (it.isNotEmpty()) m[it] = now }
        pending.add(paths, now)
    }

    fun pendingSynced(): Int = pending.size()

    // zapíše čakajúcu dávku do prefs; vráti jej veľkosť (0 = nebolo čo zapísať)
    fun flushSynced(p: SharedPreferences): Int {
        synchronized(flushLock) {
            val batch = pending.drain()
            if (batch.isEmpty()) return 0
            val json = SyncedJson.encode(live(p))
            synchronized(SyncStore) {
                p.edit().putString(SyncStore.KEY_SYNCED, json).bumpGen(p, batch.keys).apply()
            }
            return batch.size
        }
    }

    // zápis „stiahnuté do PC“ od PC programu (/api/synced) — hneď, nie až dávkou
    fun addSynced(p: SharedPreferences, paths: Collection<String>) {
        if (paths.isEmpty()) return
        queueSynced(p, paths)
        flushSynced(p)
    }
}

// každá zmena stavu zvýši počítadlo a zapíše sa do histórie (volať pod zámkom SyncStore);
// changed = zmenené cesty, null = zmenilo sa všetko, prázdne = zmena bez ciest (stav skupín)
internal fun SharedPreferences.Editor.bumpGen(
    p: SharedPreferences,
    changed: Collection<String>? = emptyList(),
): SharedPreferences.Editor {
    val gen = p.getLong(SyncStore.KEY_STATE_GEN, 0L) + 1
    SyncStore.log.record(gen, changed)
    return putLong(SyncStore.KEY_STATE_GEN, gen)
}

// Stav prechádzania skupín na webe (spracovaná/preskočená) a dávky mazania do Koša (pre „Vrátiť").
// V tých istých prefs a pod tým istým zámkom ako SyncStore (zdieľajú počítadlo state_gen).
object SyncExtras {
    private const val KEY_GROUPSTATE = "group_state_json"
    private const val KEY_BIN_BATCHES = "bin_batches_json"

    fun groupStates(p: SharedPreferences): LinkedHashMap<String, GroupStates.Entry> = synchronized(SyncStore) {
        GroupStates.parse(p.getString(KEY_GROUPSTATE, null))
    }

    // zmena stavu skupín: update dostane aktuálnu mapu a vráti false, ak sa nemá nič uložiť
    fun updateGroupStates(
        p: SharedPreferences,
        update: (MutableMap<String, GroupStates.Entry>) -> Boolean,
    ): Map<String, GroupStates.Entry> = synchronized(SyncStore) {
        val cur = groupStates(p)
        if (update(cur)) p.edit().putString(KEY_GROUPSTATE, GroupStates.toJson(cur).toString()).bumpGen(p).apply()
        cur
    }

    fun binBatchesRaw(p: SharedPreferences): String? = synchronized(SyncStore) {
        p.getString(KEY_BIN_BATCHES, null)
    }

    fun addBinBatch(p: SharedPreferences, id: String, paths: List<String>) = synchronized(SyncStore) {
        val raw = BinBatches.add(binBatchesRaw(p), id, System.currentTimeMillis(), paths)
        p.edit().putString(KEY_BIN_BATCHES, raw).apply()
    }
}
