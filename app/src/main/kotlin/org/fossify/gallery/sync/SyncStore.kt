package org.fossify.gallery.sync

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

// Perzistentný stav PC sync manažéra: fotky označené z PC na zmazanie + evidencia už stiahnutých do PC.
// Kľúč = plná cesta súboru (stabilná naprieč reštartami). Číta ho MediaServer (API) aj MainActivity
// (potvrdenie zmazania na telefóne cez MediaStore.createDeleteRequest).
object SyncStore {
    const val PREFS = "galeria_pcsync"
    private const val KEY_MARKED = "marked_delete"
    private const val KEY_SYNCED = "synced_json"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun markedPaths(p: SharedPreferences): Set<String> =
        p.getStringSet(KEY_MARKED, emptySet()) ?: emptySet()

    @Synchronized
    fun setMarked(p: SharedPreferences, path: String, marked: Boolean) {
        val cur = HashSet(markedPaths(p))
        if (marked) cur.add(path) else cur.remove(path)
        p.edit().putStringSet(KEY_MARKED, cur).apply()
    }

    @Synchronized
    fun removeMarked(p: SharedPreferences, paths: Collection<String>) {
        val cur = HashSet(markedPaths(p))
        cur.removeAll(paths.toSet())
        p.edit().putStringSet(KEY_MARKED, cur).apply()
    }

    @Synchronized
    fun clearMarked(p: SharedPreferences) {
        p.edit().putStringSet(KEY_MARKED, emptySet()).apply()
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
    fun addSynced(p: SharedPreferences, path: String) {
        val cur = syncedMap(p)
        val o = JSONObject()
        cur.forEach { (k, v) -> o.put(k, v) }
        o.put(path, System.currentTimeMillis())
        p.edit().putString(KEY_SYNCED, o.toString()).apply()
    }
}
