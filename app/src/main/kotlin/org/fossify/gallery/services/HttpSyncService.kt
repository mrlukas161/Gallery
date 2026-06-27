package org.fossify.gallery.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.sync.MediaServer
import org.fossify.gallery.sync.NetUtil
import org.nanohttpd.protocols.http.NanoHTTPD
import java.security.SecureRandom

// Foreground služba (typ specialUse) bežiaca lokálny HTTP server pre PC sync cez WiFi LAN.
// Štandardne VYPNUTÁ; spúšťa ju používateľ v Nastaveniach. Chránená 6-miestnym PIN-om (nový pri každom štarte).
// specialUse zvolené zámerne: nepodlieha 6h/24h limitu typu dataSync (Android 15) -> neumrie pri zhasnutej obrazovke.
class HttpSyncService : Service() {
    @Volatile
    private var server: MediaServer? = null

    @Volatile
    private var starting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        ensureChannel()
        val pin = intent?.getStringExtra(EXTRA_PIN) ?: currentPin ?: newPin()
        val port = MediaServer.DEFAULT_PORT
        val ip = NetUtil.getWifiIpv4(this) ?: "?"
        currentPin = pin
        currentUrl = "http://$ip:$port"

        startForegroundCompat(buildNotification(currentUrl ?: "", pin))

        if (server == null && !starting) {
            starting = true
            isRunning = true
            ensureBackgroundThread {
                try {
                    val s = MediaServer(applicationContext, pin, port)
                    s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    server = s
                } catch (e: Throwable) {
                    Handler(Looper.getMainLooper()).post { stopEverything() }
                } finally {
                    starting = false
                }
            }
        }
        return START_STICKY
    }

    private fun stopEverything() {
        try {
            server?.stop()
        } catch (ignored: Throwable) {
        }
        server = null
        isRunning = false
        currentUrl = null
        currentPin = null
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        try {
            server?.stop()
        } catch (ignored: Throwable) {
        }
        server = null
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(url: String, pin: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HttpSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.pc_server_notif_title))
            .setContentText("$url  •  PIN $pin")
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.pc_server_notif_text, url, pin)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.pc_server_stop), stopIntent)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ignored: Throwable) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "PC sync server", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        private const val ACTION_STOP = "stop"
        private const val EXTRA_PIN = "pin"
        private const val CHANNEL_ID = "pc_sync_server"
        private const val NOTIF_ID = 49250

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var currentPin: String? = null
            private set

        @Volatile
        var currentUrl: String? = null
            private set

        private fun newPin(): String {
            val r = SecureRandom()
            return (100000 + r.nextInt(900000)).toString()
        }

        fun start(context: Context) {
            val pin = newPin()
            currentPin = pin
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, HttpSyncService::class.java).putExtra(EXTRA_PIN, pin),
                )
            } catch (ignored: Throwable) {
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, HttpSyncService::class.java).setAction(ACTION_STOP))
            } catch (ignored: Throwable) {
            }
        }
    }
}
