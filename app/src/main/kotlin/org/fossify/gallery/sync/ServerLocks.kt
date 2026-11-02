package org.fossify.gallery.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// WakeLock (PARTIAL) + WifiLock pre LAN server (server-rychlost): bez nich sa pri zhasnutej obrazovke
// uspáva CPU aj Wi-Fi a PC čaká na každú odpoveď. Držia sa LEN počas požiadaviek a HOLD_MS po poslednej
// (LockHold); WakeLock má navyše časový limit WAKE_TIMEOUT_MS ako poistku. Bez požiadaviek sa oba
// uvoľnia do ~2 min. release() volá MediaServer.stop() (služba v stop/onDestroy).
class ServerLocks(
    context: Context,
    private val scheduler: ScheduledExecutorService,
    private val inFlight: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val HOLD_MS = 90_000L
        private const val REARM_MS = 20_000L
        private const val WAKE_TIMEOUT_MS = 130_000L
        private const val MIN_CHECK_MS = 5_000L
        private const val TAG = "Galeria:pcserver"
    }

    private val app = context.applicationContext
    private val hold = LockHold(HOLD_MS, REARM_MS)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var check: ScheduledFuture<*>? = null

    @Volatile
    private var closed = false

    @Volatile
    private var heldSince = 0L

    // každá požiadavka (z vlákna požiadavky) — väčšinou len zapíše čas
    fun onRequest() {
        if (closed) return
        when (hold.onRequest(clock())) {
            LockHold.Action.ACQUIRE -> synchronized(this) { acquire() }
            LockHold.Action.REARM -> synchronized(this) { armWake() }
            else -> Unit
        }
    }

    private fun acquire() {
        if (closed) return
        heldSince = clock()
        armWake()
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi ?: wm?.createWifiLock(wifiMode(), TAG)?.also {
                it.setReferenceCounted(false)
                wifi = it
            }
            if (lock != null && !lock.isHeld) lock.acquire()
        } catch (ignored: RuntimeException) {
        }
        scheduleCheck(HOLD_MS)
    }

    // non-reference-counted: ďalšie acquire(timeout) len posunie časový limit
    private fun armWake() {
        if (closed) return
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = wake ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).also {
                it.setReferenceCounted(false)
                wake = it
            }
            lock.acquire(WAKE_TIMEOUT_MS)
        } catch (ignored: RuntimeException) {
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiMode(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
    } else {
        WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }

    @Synchronized
    private fun scheduleCheck(delayMs: Long) {
        if (closed) return
        check?.cancel(false)
        check = try {
            scheduler.schedule({ onCheck() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (ignored: RuntimeException) {
            null
        }
    }

    private fun onCheck() {
        val now = clock()
        when (hold.onCheck(now, inFlight())) {
            LockHold.Action.RELEASE -> synchronized(this) { releaseLocks() }
            LockHold.Action.REARM -> {
                synchronized(this) { armWake() }
                scheduleCheck(hold.nextCheckIn(now, MIN_CHECK_MS))
            }
            else -> if (hold.held) scheduleCheck(hold.nextCheckIn(now, MIN_CHECK_MS))
        }
    }

    private fun releaseLocks() {
        try {
            wake?.takeIf { it.isHeld }?.release()
        } catch (ignored: RuntimeException) {
        }
        try {
            wifi?.takeIf { it.isHeld }?.release()
        } catch (ignored: RuntimeException) {
        }
        hold.released()
        heldSince = 0L
    }

    // zastavenie servera: uvoľniť hneď a nič ďalšie nebrať
    @Synchronized
    fun release() {
        closed = true
        check?.cancel(false)
        check = null
        releaseLocks()
    }

    fun json(): JSONObject {
        val now = clock()
        return JSONObject()
            .put("wake", wake?.isHeld == true)
            .put("wifi", wifi?.isHeld == true)
            .put("wifiMode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "low_latency" else "high_perf")
            .put("heldMs", if (heldSince > 0) now - heldSince else 0L)
            .put("idleMs", hold.idleMs(now))
            .put("holdMs", HOLD_MS)
    }
}
