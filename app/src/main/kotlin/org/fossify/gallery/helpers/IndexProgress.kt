package org.fossify.gallery.helpers

// Priebeh jednej úlohy spracovania knižnice pre notifikáciu: počty, percentá a odhad zostávajúceho času.
// Čistá logika bez Android tried (testovateľná). Odhad sa počíta z rýchlosti od prvého hlásenia priebehu,
// až keď je z čoho (aspoň MIN_DONE_FOR_ETA spracovaných a MIN_ELAPSED_FOR_ETA_MS času).
class IndexProgress(val name: String, private val clock: () -> Long = System::currentTimeMillis) {
    var done = 0
        private set
    var total = 0
        private set
    var waiting = false
        private set
    private var startMs = 0L
    private var hasStart = false
    private var startDone = 0

    fun update(done: Int, total: Int) {
        if (!hasStart || done < this.done) {
            hasStart = true
            startMs = clock()
            startDone = done
        }
        this.done = done
        this.total = total
        waiting = false
    }

    fun markWaiting() {
        waiting = true
    }

    val started: Boolean get() = total > 0

    val percent: Int get() = if (total <= 0) 0 else (done.toLong() * PERCENT / total).toInt().coerceIn(0, PERCENT)

    // zostávajúci čas v ms, alebo null keď ešte nie je z čoho odhadnúť
    fun remainingMs(): Long? {
        val processed = done - startDone
        val elapsed = clock() - startMs
        if (!hasStart || total <= 0) return null
        if (processed < MIN_DONE_FOR_ETA || elapsed < MIN_ELAPSED_FOR_ETA_MS) return null
        return elapsed * (total - done).coerceAtLeast(0) / processed
    }

    companion object {
        const val PERCENT = 100
        const val MIN_DONE_FOR_ETA = 5
        const val MIN_ELAPSED_FOR_ETA_MS = 20_000L
        private const val MINUTE_MS = 60_000L
        private const val MINUTES_PER_HOUR = 60

        // „<1 min“, „~12 min“, „~2 h 10 min“ — formát čísiel bez Android zdrojov, texty dodá volajúci
        fun formatEta(
            ms: Long,
            lessThanMinute: String,
            minutes: (Int) -> String,
            hoursMinutes: (Int, Int) -> String,
        ): String {
            val totalMin = ((ms + MINUTE_MS - 1) / MINUTE_MS).toInt()
            return when {
                ms < MINUTE_MS -> lessThanMinute
                totalMin < MINUTES_PER_HOUR -> minutes(totalMin)
                else -> hoursMinutes(totalMin / MINUTES_PER_HOUR, totalMin % MINUTES_PER_HOUR)
            }
        }
    }
}
