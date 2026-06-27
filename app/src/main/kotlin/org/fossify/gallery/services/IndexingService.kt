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
import org.fossify.gallery.faces.FaceIndexer
import org.fossify.gallery.faces.GeoIndexer
import org.fossify.gallery.faces.OcrIndexer
import org.fossify.gallery.faces.QrIndexer
import org.fossify.gallery.faces.ReindexFaces

// Foreground služba pre indexovanie (tváre / poloha / OCR). Drží proces nažive aj keď zhasne obrazovka
// alebo je apka na pozadí (HyperOS inak background prácu zabíja).
class IndexingService : Service() {
    @Volatile
    private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForegroundCompat(buildNotification(getString(R.string.indexing_running), -1, -1))
        if (working) return START_NOT_STICKY
        working = true
        when (intent?.getStringExtra(EXTRA_TASK)) {
            TASK_OCR -> runOcr { finish() }
            TASK_QR -> runQr { finish() }
            TASK_FACES -> runFaces { finish() }
            TASK_GEO -> runGeo { finish() }
            TASK_REEMBED -> runReembed { finish() }
            else -> runFaces { runGeo { runQr { finish() } } } // TASK_AUTO = tváre + poloha + QR
        }
        return START_NOT_STICKY
    }

    private fun runFaces(next: () -> Unit) {
        if (FaceIndexer.isRunning) {
            next()
            return
        }
        FaceIndexer.index(
            applicationContext, notify = false,
            onProgress = { d, t -> update(getString(R.string.indexing_faces), d, t) },
            onDone = { _, _, _ -> next() },
            onError = { next() },
        )
    }

    private fun runGeo(next: () -> Unit) {
        if (GeoIndexer.isRunning) {
            next()
            return
        }
        GeoIndexer.index(
            applicationContext,
            onProgress = { d, t -> update(getString(R.string.indexing_geo), d, t) },
            onDone = { _, _ -> next() },
            onError = { next() },
        )
    }

    private fun runQr(next: () -> Unit) {
        if (QrIndexer.isRunning) {
            next()
            return
        }
        QrIndexer.index(
            applicationContext, notify = false,
            onProgress = { d, t -> update(getString(R.string.indexing_qr), d, t) },
            onDone = { _, _ -> next() },
            onError = { next() },
        )
    }

    private fun runReembed(next: () -> Unit) {
        if (ReindexFaces.isRunning) {
            next()
            return
        }
        ReindexFaces.run(
            applicationContext,
            onProgress = { d, t -> update(getString(R.string.indexing_reembed), d, t) },
            onDone = { _ -> next() },
            onError = { next() },
        )
    }

    private fun runOcr(next: () -> Unit) {
        if (OcrIndexer.isRunning) {
            next()
            return
        }
        OcrIndexer.index(
            applicationContext, notify = false,
            onProgress = { d, t -> update(getString(R.string.indexing_ocr), d, t) },
            onDone = { _, _ -> next() },
            onError = { next() },
        )
    }

    private fun finish() {
        working = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun update(phase: String, done: Int, total: Int) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(phase, done, total))
        } catch (ignored: Throwable) {
        }
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name_brand))
            .setContentText(if (done >= 0 && total > 0) "$text $done / $total" else text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (done >= 0 && total > 0) builder.setProgress(total, done, false)
        return builder.build()
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
        const val TASK_FACES = "faces"
        const val TASK_GEO = "geo"
        const val TASK_OCR = "ocr"
        const val TASK_QR = "qr"
        const val TASK_REEMBED = "reembed"
        private const val CHANNEL_ID = "indexing_service"
        private const val NOTIF_ID = 49240

        @Volatile
        private var autoStarted = false

        // automatický štart raz za spustenie procesu (tváre + poloha)
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
