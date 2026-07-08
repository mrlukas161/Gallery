package org.fossify.gallery.helpers

import android.content.Context
import android.os.BatteryManager

// Režim výkonu indexovania. Default MAX (prvý beh nech ide naplno, všetko naraz).
object IndexPerf {
    const val PREF = "galeria_faces"
    const val KEY = "perf_mode"

    const val LIMITED = 0 // vždy šetrne (jedna funkcia po druhej)
    const val CHARGER = 1 // naplno len pri nabíjaní
    const val MAX = 2 // vždy naplno

    fun mode(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY, MAX)

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply()
    }

    // majú indexery bežať PARALELNE (všetky naraz)?
    fun parallel(context: Context): Boolean = when (mode(context)) {
        MAX -> true
        CHARGER -> isCharging(context)
        else -> false
    }

    fun isCharging(context: Context): Boolean {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.isCharging ?: false
        } catch (e: Throwable) {
            false
        }
    }
}
