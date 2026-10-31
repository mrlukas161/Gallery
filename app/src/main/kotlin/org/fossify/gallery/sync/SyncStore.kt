package org.fossify.gallery.sync

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

// Perzistentný stav PC sync manažéra: fotky označené z PC na zmazanie + evidencia už stiahnutých do PC.
// Kľúč = plná cesta súboru (stabilná naprieč reštartami). Číta ho MediaServer (API) aj MainActivity
// (potvrdenie zmazania na telefóne, PcMarkedPrompt). Každá zmena zvýši state_gen — web podľa neho
// (cez /api/ping) vie, že má obnoviť značky.
object SyncStore {
    const val PREFS = "galeria_pcsync"
    private const val KEY_MARKED = "marked_delete"
    private const val KEY_SYNCED = "synced_json"
    internal const val KEY_STATE_GEN = "state_gen"

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
        if (changed.isNotEmpty()) p.edit().putStringSet(KEY_MARKED, cur).bumpGen(p).apply()
        return changed
    }

    @Synchronized
    fun removeMarked(p: SharedPreferences, paths: Collection<String>) {
        val cur = HashSet(markedPaths(p))
        if (cur.removeAll(paths.toSet())) p.edit().putStringSet(KEY_MARKED, cur).bumpGen(p).apply()
    }

    @Synchronized
    fun clearMarked(p: SharedPreferences) {
        p.edit().putStringSet(KEY_MARKED, emptySet()).bumpGen(p).apply()
    }

    @Synchronized
    fun syncedMap(p: SharedPreferences): Map<String, Long> {
        val raw = p.getString(KEY_SYNCED, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = HashMap<String, Long>(o.length())
            o.keys().forEach { k -> out[k] = o.optLong(k) }
            out
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    @Synchronized
    fun addSynced(p: SharedPreferences, paths: Collection<String>) {
        if (paths.isEmpty()) return
        val cur = syncedMap(p)
        val o = JSONObject()
        cur.forEach { (k, v) -> o.put(k, v) }
        val now = System.currentTimeMillis()
        paths.forEach { o.put(it, now) }
        p.edit().putString(KEY_SYNCED, o.toString()).bumpGen(p).apply()
    }
}

// každá zmena stavu zvýši počítadlo (volať pod zámkom SyncStore)
internal fun SharedPreferences.Editor.bumpGen(p: SharedPreferences): SharedPreferences.Editor =
    putLong(SyncStore.KEY_STATE_GEN, p.getLong(SyncStore.KEY_STATE_GEN, 0L) + 1)

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
