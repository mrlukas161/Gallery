package org.fossify.gallery.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.sync.MediaServer
import org.fossify.gallery.sync.NetUtil
import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom

// Foreground služba (typ specialUse) bežiaca lokálny HTTP server pre PC sync cez WiFi LAN.
// Štandardne VYPNUTÁ; spúšťa ju používateľ v Nastaveniach. Chránená 6-miestnym PIN-om (nový pri každom štarte);
// po 20 rôznych zlých PIN-och sa server sám zastaví (ochrana pred hádaním na LAN).
// specialUse zvolené zámerne: nepodlieha 6h/24h limitu typu dataSync (Android 15) -> neumrie pri zhasnutej obrazovke.
class HttpSyncService : Service() {
    @Volatile
    private var server: MediaServer? = null

    @Volatile
    private var starting = false

    // táto inštancia služby už bola zastavená (stopEverything / onDestroy); globálne isRunning patrí aj
    // novej inštancii, takže štartovacie vlákno staršej inštancie sa naň spoliehať nesmie
    @Volatile
    private var stopped = false

    // zmeny v MediaStore (nová fotka z Fotoaparátu, zmazanie) -> po 1 s ticha obnov zoznam servera,
    // aby PC videlo novú fotku hneď (bez toho server poznal len fotky spred svojho spustenia)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaObserver: ContentObserver? = null
    private val refreshRunnable = Runnable {
        val s = server ?: return@Runnable
        ensureBackgroundThread {
            try {
                s.refreshIndex()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun registerMediaObserver() {
        if (mediaObserver != null) return
        val o = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                mainHandler.removeCallbacks(refreshRunnable)
                mainHandler.postDelayed(refreshRunnable, 1000)
            }
        }
        try {
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, o)
            mediaObserver = o
        } catch (ignored: Throwable) {
        }
    }

    private fun unregisterMediaObserver() {
        mainHandler.removeCallbacks(refreshRunnable)
        mediaObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (ignored: Throwable) {
            }
        }
        mediaObserver = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            lastError = null
            stopEverything()
            return START_NOT_STICKY
        }
        ensureChannel()
        val pin = intent?.getStringExtra(EXTRA_PIN) ?: currentPin ?: newPin()
        val port = MediaServer.DEFAULT_PORT
        val ip = NetUtil.getWifiIpv4(this)
        currentPin = pin
        // bez Wi-Fi sa dá spojiť USB káblom (PC si port presmeruje cez adb) -> server spustiť aj tak
        currentUrl = if (ip != null) "http://$ip:$port" else null
        startForegroundCompat(buildNotification(currentUrl, pin))

        lastError = null
        if (server == null && !starting) {
            starting = true
            isRunning = true
            ensureBackgroundThread {
                try {
                    // hádanie PIN-u na sieti -> server sa zamkne a tu sa zastaví s viditeľným dôvodom
                    val s = MediaServer(applicationContext, pin, port) {
                        Handler(Looper.getMainLooper()).post {
                            failWith(getString(R.string.c1_pc_server_locked))
                        }
                    }
                    s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    // štart trvá sekundy (prvý prepočet indexu) — medzitým sa služba mohla zastaviť (Štart -> Stop);
                    // server vtedy nepriraďovať ani nič nehlásiť, inak by osirel na porte a rušil nový beh
                    if (stopped) {
                        try {
                            s.stop()
                        } catch (ignored: Throwable) {
                        }
                        return@ensureBackgroundThread
                    }
                    server = s
                    mainHandler.post {
                        if (server === s && !stopped) {
                            registerMediaObserver()
                            // fotka nasnímaná medzi prvým prepočtom a registráciou observera by sa inak neukázala
                            ensureBackgroundThread {
                                try {
                                    s.refreshIndex()
                                } catch (ignored: Throwable) {
                                }
                            }
                        } else {
                            // služba sa medzičasom zastavila (ACTION_STOP / zámok) -> server nenechať bežať osirelý
                            try {
                                s.stop()
                            } catch (ignored: Throwable) {
                            }
                            if (server === s) server = null
                        }
                    }
                } catch (e: Throwable) {
                    // obsadený port, zakázaný socket… — NIE ticho zhasnúť, ale povedať prečo
                    val reason = e.message?.take(120) ?: e.javaClass.simpleName
                    Handler(Looper.getMainLooper()).post {
                        // zastavená inštancia nemá prepisovať stav novej
                        if (!stopped) failWith(getString(R.string.a3_pc_server_start_failed, reason))
                    }
                } finally {
                    starting = false
                }
            }
        }
        return START_STICKY
    }

    // server nebeží a používateľ to MUSÍ vidieť: toast + notifikácia s dôvodom (namiesto toho,
    // aby foreground notifikácia len ticho zmizla); chybu si prečítajú aj Nastavenia (lastError)
    private fun failWith(msg: String) {
        stopEverything()
        lastError = msg
        try {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
        } catch (ignored: Throwable) {
        }
        try {
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(getString(R.string.pc_server_title))
                .setContentText(msg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID + 1, n)
        } catch (ignored: Throwable) {
        }
    }

    private fun stopEverything() {
        stopped = true
        unregisterMediaObserver()
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
        stopped = true
        unregisterMediaObserver()
        try {
            server?.stop()
        } catch (ignored: Throwable) {
        }
        server = null
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(url: String?, pin: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HttpSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fullLink = if (url != null) "$url/?token=$pin" else null
        // bez Wi-Fi adresy niet -> v notifikácii len PIN a poznámka, že spojenie ide káblom
        val shortText = fullLink ?: getString(R.string.porovnavac_server_len_kabel, pin)
        val longText = if (fullLink != null) getString(R.string.pc_server_notif_text, fullLink, pin) else shortText
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.pc_server_notif_title))
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(longText))
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
            // vždy (aj keď kanál existuje) — systém tak premenuje kanál vytvorený staršou verziou
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.q1_channel_pc_server), NotificationManager.IMPORTANCE_LOW)
            )
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

        // prečo sa server naposledy nespustil (zobrazujú Nastavenia); null = žiadna chyba
        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var currentPin: String? = null
            private set

        @Volatile
        var currentUrl: String? = null
            private set

        // hotová adresa pre PC prehliadač — stačí prilepiť (Ctrl+V), token je v nej
        fun shareUrl(): String? {
            val u = currentUrl ?: return null
            val p = currentPin ?: return null
            return "$u/?token=$p"
        }

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
