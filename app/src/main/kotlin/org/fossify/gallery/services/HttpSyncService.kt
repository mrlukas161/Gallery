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
import org.fossify.gallery.sync.LinkKind
import org.fossify.gallery.sync.LinkKinds
import org.fossify.gallery.sync.MediaServer
import org.fossify.gallery.sync.NetUtil
import org.fossify.gallery.sync.ServerTransport
import java.security.SecureRandom

// Foreground služba (typ specialUse) bežiaca lokálny HTTP server pre PC sync cez WiFi LAN.
// Štandardne VYPNUTÁ; spúšťa ju používateľ v Nastaveniach. Chránená 6-miestnym PIN-om (nový pri každom
// ručnom štarte); po 20 rôznych zlých PIN-och sa server sám zastaví (ochrana pred hádaním na LAN).
// server-rychlost: PIN a epoch sú v prefs — keď systém službu reštartuje (START_STICKY), server beží
// s tými istými (web ani PC sa neodhlásia). Výslovné zastavenie (aj zámok po hádaní PIN-u) ich zmaže.
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

    // zmeny v MediaStore (nová fotka z Fotoaparátu, zmazanie) -> obnov zoznam servera, aby PC videlo
    // novú fotku hneď (bez toho server poznal len fotky spred svojho spustenia). Server sériu zmien
    // spojí (debounce 2,5 s, najviac 10 s) a prepočíta na pozadí.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaObserver: ContentObserver? = null

    private fun registerMediaObserver() {
        if (mediaObserver != null) return
        val o = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                server?.requestRebuild()
            }
        }
        try {
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, o)
            mediaObserver = o
        } catch (ignored: Throwable) {
        }
    }

    // Adresa sa môže objaviť až po štarte (Lukáš zapne Zdieľanie pripojenia cez USB alebo Wi-Fi) alebo zmeniť.
    // Len kým server beží: každé 3 s (keď adresu ešte nemá), inak každých 10 s sa pozrie na rozhrania a pri zmene
    // obnoví adresu aj notifikáciu. Zastavením servera končí.
    private val linkWatch = object : Runnable {
        override fun run() {
            if (stopped || !isRunning) return
            refreshLink()
            mainHandler.postDelayed(this, if (currentUrl == null) LINK_POLL_NO_ADDR_MS else LINK_POLL_MS)
        }
    }

    private fun refreshLink() {
        val best = NetUtil.addresses(this).firstOrNull()
        val url = best?.let { LinkKinds.url(it.second, MediaServer.DEFAULT_PORT) }
        if (url == currentUrl && best?.first == currentKind) return
        currentUrl = url
        currentKind = best?.first
        val pin = currentPin ?: return
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(url, pin))
        } catch (ignored: Throwable) {
        }
    }

    private fun unregisterMediaObserver() {
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
        // ručný štart nesie nový PIN; reštart systémom (intent null) vezme uložený PIN aj epoch
        val explicit = intent?.getStringExtra(EXTRA_PIN)
        val stored = if (explicit == null) Persisted.load(this) else null
        val pin = explicit ?: currentPin ?: stored?.pin ?: newPin()
        val epoch = stored?.let { st -> st.epoch.takeIf { pin == st.pin } } ?: currentEpoch ?: MediaServer.newEpoch()
        val resumed = explicit == null && stored != null && pin == stored.pin
        currentEpoch = epoch
        Persisted.save(this, pin, epoch)
        val port = MediaServer.DEFAULT_PORT
        val best = NetUtil.addresses(this).firstOrNull()
        currentPin = pin
        // bez Wi-Fi server beží tiež: PC sa pripojí káblom cez Zdieľanie pripojenia cez USB (adresa sa ukáže, keď ho
        // Lukáš zapne – linkWatch), pre pokročilých aj cez adb forward na localhost
        currentUrl = best?.let { LinkKinds.url(it.second, port) }
        currentKind = best?.first
        startForegroundCompat(buildNotification(currentUrl, pin))
        mainHandler.removeCallbacks(linkWatch)
        mainHandler.postDelayed(linkWatch, LINK_POLL_NO_ADDR_MS)

        lastError = null
        if (server == null && !starting) {
            starting = true
            isRunning = true
            ensureBackgroundThread {
                try {
                    // hádanie PIN-u na sieti -> server sa zamkne a tu sa zastaví s viditeľným dôvodom
                    val s = MediaServer(applicationContext, pin, port, epoch, resumed) {
                        Handler(Looper.getMainLooper()).post {
                            failWith(getString(R.string.c1_pc_server_locked))
                        }
                    }
                    s.start(ServerTransport.READ_TIMEOUT_MS, false)
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
                            s.refreshIndex()
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
        mainHandler.removeCallbacks(linkWatch)
        unregisterMediaObserver()
        try {
            server?.stop()
        } catch (ignored: Throwable) {
        }
        server = null
        isRunning = false
        currentUrl = null
        currentKind = null
        currentPin = null
        currentEpoch = null
        // výslovné zastavenie (alebo zámok po hádaní PIN-u): ďalší štart = nový PIN a nový epoch
        Persisted.clear(this)
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        mainHandler.removeCallbacks(linkWatch)
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
        // bez adresy (žiadna Wi-Fi ani zdieľanie cez USB) -> v notifikácii návod, ako PC pripojiť káblom, a PIN
        val shortText = if (fullLink != null) {
            kindLabel(this, currentKind) + ": " + fullLink
        } else {
            getString(R.string.porovnavac_server_len_kabel, pin)
        }
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

    // PIN a epoch bežiaceho servera v súkromných prefs (len kým ho používateľ nezastaví)
    private object Persisted {
        private const val PREFS = "galeria_pcserver"
        private const val KEY_PIN = "pin"
        private const val KEY_EPOCH = "epoch"

        class State(val pin: String, val epoch: String)

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(c: Context): State? {
            val p = prefs(c)
            val pin = p.getString(KEY_PIN, null) ?: return null
            val epoch = p.getString(KEY_EPOCH, null) ?: return null
            return State(pin, epoch)
        }

        fun save(c: Context, pin: String, epoch: String) {
            prefs(c).edit().putString(KEY_PIN, pin).putString(KEY_EPOCH, epoch).apply()
        }

        fun clear(c: Context) {
            prefs(c).edit().clear().apply()
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

        // cez čo sa PC pripája (Wi-Fi, USB kábel = zdieľanie pripojenia cez USB, hotspot…); null = zatiaľ nijako
        @Volatile
        var currentKind: LinkKind? = null
            private set

        @Volatile
        private var currentEpoch: String? = null

        private const val LINK_POLL_NO_ADDR_MS = 3_000L
        private const val LINK_POLL_MS = 10_000L

        fun kindLabel(c: Context, kind: LinkKind?): String = c.getString(
            when (kind) {
                LinkKind.USB -> R.string.pc_link_usb
                LinkKind.HOTSPOT -> R.string.pc_link_hotspot
                LinkKind.ETHERNET -> R.string.pc_link_eth
                LinkKind.OTHER -> R.string.pc_link_other
                else -> R.string.pc_link_wifi
            }
        )

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
            currentEpoch = MediaServer.newEpoch()
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
