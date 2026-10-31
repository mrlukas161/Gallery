package org.fossify.gallery.helpers

import android.content.Context
import org.json.JSONArray

// Skupiny skryté cez „Ponechať všetky“ / „Nie sú to duplikáty“ (pravidlo podmnožiny v HiddenGroups).
// Malé samostatné SharedPreferences „compare_hidden“: jeden JSON zoznam množín ciest. Nesúvisí
// s indexmi ani s people/faces/extras — zmazanie len vráti skupiny do zoznamu.
object CompareHiddenStore {
    private const val PREFS = "compare_hidden"
    private const val KEY_SETS = "sets"

    private val lock = Any()

    @Volatile
    private var cached: HiddenGroups? = null

    // prvé čítanie siaha na disk — prvýkrát volať na pozadí (CompareListActivity, HomeStats)
    fun get(context: Context): HiddenGroups = synchronized(lock) {
        cached ?: read(context).also { cached = it }
    }

    fun hide(context: Context, groups: Collection<Collection<String>>): HiddenGroups =
        update(context) { it.plus(groups) }

    fun unhide(context: Context, groups: Collection<Collection<String>>): HiddenGroups =
        update(context) { it.minus(groups) }

    fun clear(context: Context): HiddenGroups = update(context) { HiddenGroups(emptyList()) }

    private fun update(context: Context, change: (HiddenGroups) -> HiddenGroups): HiddenGroups = synchronized(lock) {
        val next = change(get(context))
        cached = next
        val json = JSONArray()
        next.sets.forEach { set -> json.put(JSONArray(set.sorted())) }
        prefs(context).edit().putString(KEY_SETS, json.toString()).apply()
        next
    }

    private fun read(context: Context): HiddenGroups {
        val sets = ArrayList<Set<String>>()
        try {
            val json = JSONArray(prefs(context).getString(KEY_SETS, null) ?: return HiddenGroups(emptyList()))
            for (i in 0 until json.length()) {
                json.optJSONArray(i)?.let { sets.add(readSet(it)) }
            }
        } catch (ignored: Throwable) {
            // poškodený záznam = nič skryté (skupiny sa len znova ukážu)
        }
        return HiddenGroups(sets)
    }

    private fun readSet(arr: JSONArray): Set<String> {
        val set = HashSet<String>()
        for (k in 0 until arr.length()) {
            val path = arr.optString(k)
            if (path.isNotEmpty()) set.add(path)
        }
        return set
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
