package org.fossify.gallery.jobs

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import org.fossify.gallery.clip.ClipModels
import org.fossify.gallery.family.FamilyAutoSync
import org.fossify.gallery.family.FamilySchedule
import org.fossify.gallery.helpers.IndexPerf
import org.fossify.gallery.services.IndexingService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Okno pri nabíjaní (F5 bod 3b, spec Časť 1 „Kedy“ bod 2): doma na Wi-Fi a pri nabíjaní je appka až
// 1 h pripravená na synchronizáciu a sama ju skúša, aby sa telefóny v noci stretli. V tom istom behu
// sa dobehne spracovanie knižnice odložené na nabíjanie (IndexingService TASK_AUTO, rozhoduje
// ProcessingPolicy).
//
// Prečo bežný job a nie user-initiated job ani foreground služba (Android 14–16):
//  - setUserInitiated je len pre prenosy, ktoré používateľ práve spustil (treba povolenie
//    RUN_USER_INITIATED_JOBS, naplánovať sa dá len z viditeľnej appky a musí ukazovať notifikáciu).
//    Automatické nočné okno tieto pravidlá nespĺňa.
//  - Foreground službu sa z pozadia spustiť nedá (Android 12+) a dataSync má na Androide 15+ limit
//    6 h za 24 h, ktorý patrí spracovaniu knižnice.
//  - Bežný job má limit 10 minút na beh. Okno sa preto skladá z behov po najviac 9 minútach
//    (FamilySchedule.SEGMENT_MS), ktoré na seba nadväzujú, kým neuplynie hodina alebo kým sa
//    nestihne synchronizovať so všetkými telefónmi. Ak systém beh zastaví skôr (odpojená nabíjačka,
//    Wi-Fi, kvóta jobov na Androide 16), onStopJob server hneď vypne a job sa zopakuje neskôr.
//  - Po okne ďalšie najskôr o 6 h. Keď nie je nič spárované a nič nečaká na nabíjanie, job sa zruší.
// Server (FamilyServer) beží len počas behu jobu (acquire/release), nikdy trvalo.
class FamilySyncJob : JobService() {
    @Volatile
    private var stopSignal: CountDownLatch? = null

    override fun onStartJob(params: JobParameters): Boolean {
        // A4: dáta z novšej verzie appky — nič neotvárať (Room by vyhodil výnimku a appka by spadla)
        if (org.fossify.gallery.safety.DbVersionGuard.hasNewerData(applicationContext)) return false
        val stop = CountDownLatch(1)
        stopSignal = stop
        running = true
        Thread({ work(params, stop) }, "FamilySyncJob").start()
        return true
    }

    // systém beh zastavil (podmienky, časový limit, kvóta) → true = zopakuje ho neskôr sám
    override fun onStopJob(params: JobParameters): Boolean {
        stopSignal?.countDown()
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    private fun work(params: JobParameters, stop: CountDownLatch) {
        val app = applicationContext
        var next: FamilySchedule.Next = FamilySchedule.Next.Later(FamilySchedule.NEXT_WINDOW_DELAY_MS)
        try {
            next = runSegment(app, stop)
        } catch (e: RuntimeException) {
            Log.w(TAG, "okno synchronizácie zlyhalo", e)
        } finally {
            running = false
        }
        if (stop.count == 0L) return // zastavil systém, zopakuje sám
        jobFinished(params, false)
        // až po jobFinished: naplánovanie jobu s rovnakým id by bežiaci beh zastavilo
        when (next) {
            FamilySchedule.Next.Continue -> schedule(app, 0, paired = true)
            is FamilySchedule.Next.Later -> {
                windowPrefs(app).edit().putLong(KEY_WINDOW_START, 0).apply()
                schedule(app, next.delayMs, isPaired(app))
            }

            FamilySchedule.Next.Cancel -> cancel(app)
        }
    }

    private fun runSegment(app: Context, stop: CountDownLatch): FamilySchedule.Next {
        val now = System.currentTimeMillis()
        val prefs = windowPrefs(app)
        val windowStart = FamilySchedule.windowStart(prefs.getLong(KEY_WINDOW_START, 0), now)
        prefs.edit().putLong(KEY_WINDOW_START, windowStart).apply()

        val plan = FamilySchedule.plan(conditions(app))
        if (plan.process && !IndexingService.start(app, IndexingService.TASK_AUTO)) {
            // Android 12+ nedovolí spustiť foreground službu z pozadia (okrem výnimiek, napr. vypnutá
            // optimalizácia batérie) → spracovanie ostáva odložené a dobehne pri otvorení appky na nabíjačke
            Log.i(TAG, "spracovanie knižnice sa z pozadia nedalo spustiť")
        }
        var allSynced = false
        if (plan.sync) {
            allSynced = syncUntil(app, FamilySchedule.segmentEnd(windowStart, now), stop)
            FamilyAutoSync.retryPendingIfDue(app)
        }
        if (stop.count == 0L) return FamilySchedule.Next.Later(FamilySchedule.NEXT_WINDOW_DELAY_MS)
        val after = conditions(app)
        return FamilySchedule.next(
            paired = after.paired,
            processingPending = after.processingPending,
            windowStart = windowStart,
            now = System.currentTimeMillis(),
            allSynced = allSynced,
            attempted = plan.sync,
        )
    }

    // Server je počas behu prijímateľný a synchronizácia sa opakuje každé ~2 min do konca behu.
    // true = so všetkými telefónmi prebehla celá synchronizácia.
    private fun syncUntil(app: Context, end: Long, stop: CountDownLatch): Boolean {
        org.fossify.gallery.family.FamilyServer.acquire(app, FamilyAutoSync.OWNER_WINDOW)
        try {
            while (stop.count > 0) {
                if (FamilyAutoSync.syncNow(app, FamilyAutoSync.OWNER_WINDOW_CLIENT, FamilyAutoSync.WINDOW_TIMEOUT_MS)) {
                    return true
                }
                val wait = minOf(FamilySchedule.RETRY_INTERVAL_MS, end - System.currentTimeMillis())
                if (wait <= 0 || stop.await(wait, TimeUnit.MILLISECONDS)) return false
                val battery = IndexPerf.battery(app)
                if (FamilySchedule.lowBattery(battery.charging, battery.percent)) return false
            }
            return false
        } finally {
            org.fossify.gallery.family.FamilyServer.release(FamilyAutoSync.OWNER_WINDOW)
        }
    }

    companion object {
        const val JOB_ID = 2
        private const val TAG = "FamilySyncJob"
        private const val PREFS = "galeria_family_job"
        private const val KEY_WINDOW_START = "window_start"
        private const val EXTRA_PAIRED = "paired"

        // práve beží beh jobu (plánovanie z iných miest ho nesmie prerušiť)
        @Volatile
        var running = false
            private set

        private fun windowPrefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Volať len na pozadí (Room). Pri štarte appky, po spárovaní / zrušení prepojenia a keď
        // spracovanie knižnice niečo odloží na nabíjanie: naplánuje job, ak má zmysel, inak ho zruší.
        // Už naplánovaný job s rovnakými podmienkami nechá tak (neposúva čakanie po okne).
        fun refresh(context: Context) {
            val app = context.applicationContext
            if (running) return // koniec behu rozhodne sám
            val paired = isPaired(app)
            if (!FamilySchedule.shouldSchedule(paired, IndexingService.deferredWorkPending(app))) {
                cancel(app)
                return
            }
            val pending = scheduler(app)?.getPendingJob(JOB_ID)
            val pendingPaired = pending?.extras?.getInt(EXTRA_PAIRED, 0) == 1
            if (pending == null || pendingPaired != paired) schedule(app, 0, paired)
        }

        private fun schedule(context: Context, delayMs: Long, paired: Boolean) {
            val constraints = FamilySchedule.constraints(paired)
            val network =
                if (constraints.requiresUnmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_NONE
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, FamilySyncJob::class.java))
                .setRequiresCharging(constraints.requiresCharging)
                .setRequiredNetworkType(network)
                .setPersisted(true)
                .setExtras(PersistableBundle().apply { putInt(EXTRA_PAIRED, if (paired) 1 else 0) })
                .apply { if (delayMs > 0) setMinimumLatency(delayMs) }
                .build()
            try {
                scheduler(context)?.schedule(info)
            } catch (ignored: RuntimeException) {
                // napr. príliš veľa naplánovaných jobov — skúsi sa pri ďalšom štarte appky
            }
        }

        fun cancel(context: Context) {
            try {
                scheduler(context)?.cancel(JOB_ID)
            } catch (ignored: RuntimeException) {
            }
        }

        private fun scheduler(context: Context): JobScheduler? =
            context.applicationContext.getSystemService(JobScheduler::class.java)

        private fun isPaired(context: Context): Boolean = FamilyAutoSync.hasPartners(context)

        private fun conditions(context: Context): FamilySchedule.Conditions {
            val battery = IndexPerf.battery(context)
            return FamilySchedule.Conditions(
                paired = isPaired(context),
                processingPending = IndexingService.deferredWorkPending(context),
                charging = battery.charging,
                batteryPct = battery.percent,
                unmetered = !ClipModels.isMeteredNetwork(context),
            )
        }
    }
}
