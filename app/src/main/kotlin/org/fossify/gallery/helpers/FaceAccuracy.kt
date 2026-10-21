package org.fossify.gallery.helpers

import android.content.Context
import android.database.SQLException
import android.os.BatteryManager
import android.util.Log
import androidx.annotation.WorkerThread
import org.fossify.gallery.R
import org.fossify.gallery.faces.AccuracyEstimate
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.PeopleIndex
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

// C10 — odhad presnosti rozpoznávania tvárí (AccuracyEstimate, leave-one-out) nad jedným zdrojom
// pravdy (PeopleIndex). Výsledok sa ukladá do SharedPreferences. Počíta sa:
//  - na požiadanie (ťuk v Nastaveniach);
//  - pri nabíjaní, keď je odhad starší ako MAX_AGE_MS — spúšťa ho obrazovka Ľudia / Nastavenia jedným
//    krátkym vláknom (nič nebeží trvalo na pozadí, žiadna služba ani plánovaná úloha).
object FaceAccuracy {
    private const val PREFS = "galeria_faces"
    private const val KEY = "tc2_accuracy"
    private const val TAG = "FaceAccuracy"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val PERCENT = 100

    private val running = AtomicBoolean(false)

    fun load(context: Context): AccuracyEstimate.Result? =
        AccuracyEstimate.decode(prefs(context).getString(KEY, null))

    // Prepočet a uloženie. Vráti null, keď nie je čo hodnotiť (žiadna potvrdená tvár) alebo už beží iný výpočet.
    @WorkerThread
    fun compute(context: Context): AccuracyEstimate.Result? {
        if (!running.compareAndSet(false, true)) return null
        try {
            val snapshot = PeopleIndex.get(context)
            val model = snapshot.model ?: return null
            // vzorky = vlastné ručne potvrdené tváre (rodinné priradenia sa nehodnotia)
            val samples = snapshot.persons.flatMap { p ->
                val pid = p.id ?: return@flatMap emptyList()
                p.faces.mapNotNull { f ->
                    val id = f.id ?: return@mapNotNull null
                    val e = f.embedding ?: return@mapNotNull null
                    if (!p.manualFaceIds.contains(id) || p.familyFaceIds.contains(id)) return@mapNotNull null
                    AccuracyEstimate.Sample(id, pid, FaceEmbedder.toFloats(e))
                }
            }
            if (samples.isEmpty()) return null
            val now = System.currentTimeMillis()
            val result =
                AccuracyEstimate.run(model, AccuracyEstimate.sample(samples), snapshot.threshold, FaceAuto.MARGIN, now)
            prefs(context).edit().putString(KEY, AccuracyEstimate.encode(result)).apply()
            return result
        } finally {
            running.set(false)
        }
    }

    // pri nabíjaní a starom (alebo žiadnom) odhade prepočíta na jednom krátkom vlákne; [onDone] na pozadí
    fun refreshWhileCharging(context: Context, onDone: (AccuracyEstimate.Result) -> Unit) {
        val app = context.applicationContext
        val last = load(app)
        if (last != null && System.currentTimeMillis() - last.computedAt < MAX_AGE_MS) return
        if (!isCharging(app) || running.get()) return
        Thread({
            val result = try {
                compute(app)
            } catch (e: SQLException) {
                Log.w(TAG, "odhad presnosti zlyhal", e)
                null
            } catch (e: IllegalStateException) {
                Log.w(TAG, "odhad presnosti zlyhal", e)
                null
            }
            result?.let(onDone)
        }, TAG).start()
    }

    // „Odhad presnosti: 96 % (z 1 234 potvrdených tvárí)“ + riadok s podielom neistých a odporúčaným prahom
    fun describe(context: Context, r: AccuracyEstimate.Result): String {
        val res = context.resources
        val precision = r.precision
        val first = if (precision == null) {
            context.getString(R.string.tc2_accuracy_no_auto)
        } else {
            val count = NumberFormat.getIntegerInstance().format(r.evaluated)
            res.getQuantityString(R.plurals.tc2_accuracy_value, r.evaluated, percent(precision), count)
        }
        val uncertain = percent(r.uncertainShare)
        val recommended = r.recommended
        val second = if (recommended == null) {
            context.getString(R.string.tc2_accuracy_details_no_rec, uncertain)
        } else {
            context.getString(R.string.tc2_accuracy_details, uncertain, decimal(recommended), decimal(r.threshold))
        }
        return "$first\n$second"
    }

    private fun percent(v: Float): Int = (v * PERCENT).roundToInt()

    private fun decimal(v: Float): String = String.format(Locale.getDefault(), "%.2f", v)

    private fun isCharging(context: Context): Boolean =
        context.getSystemService(BatteryManager::class.java)?.isCharging == true

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
