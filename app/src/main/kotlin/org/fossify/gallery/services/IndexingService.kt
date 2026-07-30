package org.fossify.gallery.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.fossify.gallery.R
import org.fossify.gallery.clip.ClipIndexer
import org.fossify.gallery.clip.ClipSearch
import org.fossify.gallery.faces.FaceIndexer
import org.fossify.gallery.faces.GeoIndexer
import org.fossify.gallery.faces.OcrIndexer
import org.fossify.gallery.faces.PhashIndexer
import org.fossify.gallery.faces.QrIndexer
import org.fossify.gallery.helpers.IndexPerf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Foreground služba pre indexovanie. V režime Max/nabíjanie bežia funkcie PARALELNE (všetko naraz),
// v obmedzenom režime postupne. Notifikácia ukazuje priebeh KAŽDEJ bežiacej funkcie. Ref-counted:
// viac spustení (aj z rôznych tlačidiel) beží súčasne; služba skončí až keď dobehnú všetky.
class IndexingService : Service() {
    private val progressMap = ConcurrentHashMap<String, String>()
    private val active = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForegroundCompat(buildInitial())
        when (val task = intent?.getStringExtra(EXTRA_TASK)) {
            null, TASK_AUTO -> {
                val list = ArrayList<String>()
                list.add(TASK_FACES)
                list.add(TASK_GEO)
                list.add(TASK_QR)
                if (autoOcrEnabled()) list.add(TASK_OCR)
                launch(list, sequential = !IndexPerf.parallel(this))
            }

            TASK_ALL -> {
                // jedným ťukom rozbehni VŠETKO naraz (podľa perf režimu paralelne/postupne)
                val list = ArrayList<String>()
                list.add(TASK_FACES)
                list.add(TASK_GEO)
                list.add(TASK_QR)
                list.add(TASK_OCR)
                list.add(TASK_PHASH)
                if (org.fossify.gallery.clip.ClipModels.bothPresent(this)) list.add(TASK_CLIP)
                launch(list, sequential = !IndexPerf.parallel(this))
            }

            else -> launch(listOf(task), sequential = false)
        }
        return START_NOT_STICKY
    }

    // --- orchestrácia (ref-counted, paralelne alebo postupne) ---

    private fun launch(tasks: List<String>, sequential: Boolean) {
        val toRun = tasks.filter { !isTaskRunning(it) }
        if (toRun.isEmpty()) {
            maybeFinish()
            return
        }
        active.addAndGet(toRun.size)
        if (sequential) {
            chain(toRun, 0)
        } else {
            toRun.forEach { t -> runOne(t) { taskFinished() } }
        }
    }

    private fun chain(tasks: List<String>, i: Int) {
        if (i >= tasks.size) return
        runOne(tasks[i]) {
            taskFinished()
            chain(tasks, i + 1)
        }
    }

    private fun taskFinished() {
        active.decrementAndGet()
        maybeFinish()
    }

    private fun maybeFinish() {
        if (active.get() <= 0) finish()
    }

    private fun isTaskRunning(task: String): Boolean = when (task) {
        TASK_FACES -> FaceIndexer.isRunning
        TASK_GEO -> GeoIndexer.isRunning
        TASK_QR -> QrIndexer.isRunning
        TASK_OCR -> OcrIndexer.isRunning
        TASK_PHASH -> PhashIndexer.isRunning
        TASK_CLIP -> ClipIndexer.isRunning
        TASK_CLIP_ML -> org.fossify.gallery.clip.ClipMlModels.isRunning
        TASK_REEMBED -> org.fossify.gallery.faces.RefreshFaces.isRunning
        else -> false
    }

    private fun runOne(task: String, done: () -> Unit) {
        when (task) {
            TASK_FACES -> runFaces(done)
            TASK_GEO -> runGeo(done)
            TASK_QR -> runQr(done)
            TASK_OCR -> runOcr(done)
            TASK_PHASH -> runPhash(done)
            TASK_CLIP -> runClip(done)
            TASK_CLIP_ML -> runClipMl(done)
            TASK_REEMBED -> runReembed(done)
            else -> done()
        }
    }

    // --- jednotlivé indexery (bežia na vlastnom vlákne, hlásia progres kľúčom) ---

    private fun runFaces(next: () -> Unit) {
        if (FaceIndexer.isRunning) {
            next(); return
        }
        FaceIndexer.index(
            applicationContext, notify = false,
            onProgress = { d, t -> prog("faces", "Tváre $d/$t") },
            onDone = { _, _, _ -> clearProg("faces"); next() },
            onError = { clearProg("faces"); next() },
        )
    }

    private fun runGeo(next: () -> Unit) {
        if (GeoIndexer.isRunning) {
            next(); return
        }
        GeoIndexer.index(
            applicationContext,
            onProgress = { d, t -> prog("geo", "Poloha $d/$t") },
            onDone = { _, _ -> clearProg("geo"); next() },
            onError = { clearProg("geo"); next() },
        )
    }

    private fun runQr(next: () -> Unit) {
        if (QrIndexer.isRunning) {
            next(); return
        }
        QrIndexer.index(
            applicationContext, notify = false,
            onProgress = { d, t -> prog("qr", "QR kódy $d/$t") },
            onDone = { _, _ -> clearProg("qr"); next() },
            onError = { clearProg("qr"); next() },
        )
    }

    private fun runOcr(next: () -> Unit) {
        if (OcrIndexer.isRunning) {
            next(); return
        }
        OcrIndexer.index(
            applicationContext, notify = false,
            onProgress = { d, t -> prog("ocr", "OCR text $d/$t") },
            onDone = { _, _ -> clearProg("ocr"); next() },
            onError = { clearProg("ocr"); next() },
        )
    }

    private fun runPhash(next: () -> Unit) {
        if (PhashIndexer.isRunning) {
            next(); return
        }
        PhashIndexer.index(
            applicationContext, notify = false,
            onProgress = { d, t -> prog("phash", "Podobné $d/$t") },
            onDone = { _ -> clearProg("phash"); next() },
            onError = { clearProg("phash"); next() },
        )
    }

    private fun runClip(next: () -> Unit) {
        if (ClipIndexer.isRunning) {
            next(); return
        }
        ClipIndexer.index(
            applicationContext, notify = false,
            onProgress = { phase, d, t -> prog("clip", "$phase $d/$t") },
            onDone = { _ -> ClipSearch.invalidate(); clearProg("clip"); next() },
            onError = { clearProg("clip"); next() },
        )
    }

    private fun runClipMl(next: () -> Unit) {
        if (org.fossify.gallery.clip.ClipMlModels.isRunning) {
            next(); return
        }
        org.fossify.gallery.clip.ClipMlModels.download(
            applicationContext,
            onProgress = { phase, d, t -> prog("clipml", "SK model: $phase $d/$t") },
            onDone = { org.fossify.gallery.clip.ClipSearch.releaseMl(); clearProg("clipml"); next() },
            onError = { clearProg("clipml"); next() },
        )
    }

    // „Prepracovať tváre" — nahrádza staré prepočítanie odtlačkov: navyše opraví otočenie (EXIF)
    // a doplní predtým nenájdené (vzdialené) tváre, pričom menovky ostávajú zachované
    private fun runReembed(next: () -> Unit) {
        if (org.fossify.gallery.faces.RefreshFaces.isRunning) {
            next(); return
        }
        org.fossify.gallery.faces.RefreshFaces.run(
            applicationContext,
            onProgress = { d, t -> prog("reembed", "Prepracovanie tvárí $d/$t") },
            onDone = { _, _ -> clearProg("reembed"); next() },
            onError = { clearProg("reembed"); next() },
        )
    }

    private fun autoOcrEnabled(): Boolean =
        getSharedPreferences("galeria_faces", Context.MODE_PRIVATE).getBoolean("auto_ocr", false)

    // --- notifikácia s per-task priebehom ---

    private fun prog(key: String, text: String) {
        progressMap[key] = text
        refreshNotification()
    }

    private fun clearProg(key: String) {
        progressMap.remove(key)
        refreshNotification()
    }

    private fun refreshNotification() {
        try {
            val lines = progressMap.values.toList()
            val big = if (lines.isEmpty()) getString(R.string.indexing_running) else lines.joinToString("\n")
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(getString(R.string.app_name_brand))
                .setContentText(lines.firstOrNull() ?: getString(R.string.indexing_running))
                .setStyle(NotificationCompat.BigTextStyle().bigText(big))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
        } catch (ignored: Throwable) {
        }
    }

    private fun buildInitial(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name_brand))
            .setContentText(getString(R.string.indexing_running))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun finish() {
        progressMap.clear()
        stopForegroundCompat()
        stopSelf()
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Throwable) {
            try {
                startForeground(NOTIF_ID, n)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Indexovanie", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        const val EXTRA_TASK = "task"
        const val TASK_AUTO = "auto"
        const val TASK_ALL = "all"
        const val TASK_FACES = "faces"
        const val TASK_GEO = "geo"
        const val TASK_OCR = "ocr"
        const val TASK_QR = "qr"
        const val TASK_PHASH = "phash"
        const val TASK_CLIP = "clip"
        const val TASK_CLIP_ML = "clipml"
        const val TASK_REEMBED = "reembed"
        private const val CHANNEL_ID = "indexing_service"
        private const val NOTIF_ID = 49240

        @Volatile
        private var autoStarted = false

        fun startAutoOnce(context: Context) {
            if (autoStarted) return
            autoStarted = true
            start(context, TASK_AUTO)
        }

        fun start(context: Context, task: String) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, IndexingService::class.java).putExtra(EXTRA_TASK, task),
                )
            } catch (ignored: Throwable) {
            }
        }
    }
}
