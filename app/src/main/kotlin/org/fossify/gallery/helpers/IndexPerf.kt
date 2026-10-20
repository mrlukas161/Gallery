package org.fossify.gallery.helpers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

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

    // Stav batérie pre ProcessingPolicy: percentá (alebo ProcessingPolicy.UNKNOWN) a či je telefón
    // pripojený na nabíjačku (aj keď je už nabitý — isCharging vtedy na niektorých telefónoch vracia false).
    data class Battery(val charging: Boolean, val percent: Int)

    private const val PERCENT = 100

    fun battery(context: Context): Battery = try {
        // sticky broadcast: bez prijímača sa len prečíta posledný stav
        batteryFrom(
            ContextCompat.registerReceiver(
                context.applicationContext, null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        )
    } catch (ignored: Throwable) {
        Battery(isCharging(context), ProcessingPolicy.UNKNOWN)
    }

    fun batteryFrom(intent: Intent?): Battery {
        if (intent == null) return Battery(false, ProcessingPolicy.UNKNOWN)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * PERCENT / scale else ProcessingPolicy.UNKNOWN
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return Battery(plugged, percent)
    }
}
