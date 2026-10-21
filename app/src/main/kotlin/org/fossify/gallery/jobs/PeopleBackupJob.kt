package org.fossify.gallery.jobs

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import org.fossify.gallery.safety.AutoBackup
import java.io.IOException

// Automatická záloha osôb (A3): periodický job raz za týždeň, len pri nabíjaní (vzor FamilySyncJob).
// Beží krátko (záloha trvá sekundy až pár minút), nič nedrží na pozadí. Vlastnú prácu robí AutoBackup;
// job ju spustí len keď je záloha zapnutá a od poslednej uplynul týždeň (ručná záloha z Nastavení
// sa počíta). Pri zlyhaní sa job zopakuje v ďalšej perióde, staré zálohy ostávajú.
class PeopleBackupJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        if (!AutoBackup.isDue(app)) return false
        Thread({ work(params) }, "PeopleBackupJob").start()
        return true
    }

    // systém beh zastavil (odpojená nabíjačka, limit) → rozbehnutý zápis dobehne sám (dočasný
    // súbor sa premenuje až po úspechu); ďalší pokus príde v ďalšej perióde
    override fun onStopJob(params: JobParameters): Boolean = false

    @Suppress("TooGenericExceptionCaught")
    private fun work(params: JobParameters) {
        try {
            AutoBackup.run(applicationContext)
        } catch (e: IOException) {
            Log.w(TAG, "automatická záloha osôb zlyhala", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "automatická záloha osôb zlyhala", e)
        }
        jobFinished(params, false)
    }

    companion object {
        const val JOB_ID = 3
        private const val TAG = "PeopleBackupJob"

        // pri štarte appky a po zmene v Nastaveniach: zapnutá záloha = naplánovaný job, vypnutá = zrušený
        @Suppress("TooGenericExceptionCaught")
        fun refresh(context: Context) {
            val app = context.applicationContext
            val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
            try {
                if (!AutoBackup.status(app).enabled) {
                    scheduler.cancel(JOB_ID)
                    return
                }
                if (scheduler.getPendingJob(JOB_ID) != null) return
                val info = JobInfo.Builder(JOB_ID, ComponentName(app, PeopleBackupJob::class.java))
                    .setRequiresCharging(true)
                    .setPeriodic(AutoBackup.INTERVAL_MS)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(info)
            } catch (e: RuntimeException) {
                // napr. príliš veľa naplánovaných jobov — skúsi sa pri ďalšom štarte appky
                Log.w(TAG, "automatickú zálohu sa nepodarilo naplánovať", e)
            }
        }
    }
}
