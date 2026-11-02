package org.fossify.gallery.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import org.fossify.gallery.helpers.HeavyGate
import org.fossify.gallery.helpers.IndexPerf
import org.fossify.gallery.helpers.ServerActivity
import org.fossify.gallery.helpers.ServerYield
import org.fossify.gallery.services.IndexingService
import org.json.JSONArray
import org.json.JSONObject

// Diagnostika pre GET /api/stats (server-rychlost): stav telefónu a spracovania knižnice, ktorý
// vysvetľuje „občas pomalý“ server — nabíjanie, teplota, Doze, šetrič batérie, výnimka z optimalizácie
// batérie, obrazovka, Wi-Fi signál. Všetko len čítanie, bez povolení navyše; čo sa nedá zistiť, chýba.
object ServerDiag {
    private const val TENTHS = 10.0

    // požiadavky, časy podľa endpointu a pomalé požiadavky
    fun base(stats: ServerStats, connections: Int, remoteAddr: String?): JSONObject {
        val endpoints = JSONObject()
        stats.latency.snapshot().forEach { (k, v) ->
            endpoints.put(
                k,
                JSONObject().put("count", v.count).put("p50", v.p50).put("p95", v.p95).put("max", v.max)
                    .put("p50Total", v.p50Total).put("p95Total", v.p95Total),
            )
        }
        val slow = JSONArray()
        stats.slow.list().forEach { e ->
            slow.put(
                JSONObject().put("at", e.at).put("path", e.path).put("status", e.status).put("ms", e.handlerMs)
                    .put("totalMs", e.totalMs).put("bytes", e.bytes).put("via", e.via),
            )
        }
        val requests = JSONObject()
            .put("inFlight", stats.inFlight.get())
            .put("connections", connections)
            .put("maxConnections", ServerTransport.MAX_CONNECTIONS)
            .put("rejectedConnections", stats.rejectedConnections.get())
            .put("total", stats.total.get())
            .put("usb", stats.usb.get())
            .put("lan", stats.lan.get())
        return JSONObject()
            .put("now", System.currentTimeMillis())
            .put("client", ClientKind.of(remoteAddr))
            .put("requests", requests)
            .put("endpoints", endpoints)
            .put("slow", slow)
    }

    fun index(stats: ServerStats, count: Int, gen: Long): JSONObject = JSONObject()
        .put("count", count)
        .put("gen", gen)
        .put("rebuilds", stats.rebuildCount)
        .put("rebuildLastMs", stats.rebuildLastMs)
        .put("rebuildAvgMs", stats.rebuildAvg)
        .put("rebuildMaxMs", stats.rebuildMax)
        .put("rebuildLastAt", stats.rebuildLastAt)

    fun processing(): JSONObject = JSONObject()
        .put("running", JSONArray(IndexingService.runningTasks()))
        .put("pausedForPc", JSONArray(ServerYield.SHARED.pausedTasks()))
        .put("heavyBusy", HeavyGate.SHARED.isBusy)
        .put("heavyWaiting", HeavyGate.SHARED.waiting)
        .put("serverIdleMs", ServerActivity.SHARED.idleMs().takeIf { it != Long.MAX_VALUE } ?: -1L)
        .put("autoStartAllowed", ServerActivity.autoStartAllowed(false))

    fun device(context: Context): JSONObject {
        val app = context.applicationContext
        val o = JSONObject()
        val battery = IndexPerf.battery(app)
        o.put("charging", battery.charging).put("batteryPct", battery.percent)
        batteryTemp(app)?.let { o.put("batteryTempC", it) }
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            o.put("screenOn", pm.isInteractive)
                .put("powerSave", pm.isPowerSaveMode)
                .put("deviceIdle", pm.isDeviceIdleMode)
                .put("ignoringBatteryOptimizations", pm.isIgnoringBatteryOptimizations(app.packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) o.put("thermalStatus", pm.currentThermalStatus)
        }
        return o
    }

    private fun batteryTemp(app: Context): Double? = try {
        val i: Intent? = ContextCompat.registerReceiver(
            app, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / TENTHS }
    } catch (ignored: RuntimeException) {
        null
    }

    // RSSI (dBm), rýchlosť linky (Mb/s) a frekvencia (MHz) aktívnej Wi-Fi; null = nie je na Wi-Fi / nedá sa zistiť
    fun wifi(context: Context): JSONObject? {
        val info = wifiInfo(context.applicationContext) ?: return null
        return JSONObject()
            .put("rssi", info.rssi)
            .put("linkMbps", info.linkSpeed)
            .put("freqMHz", info.frequency)
    }

    private fun wifiInfo(app: Context): WifiInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            if (onWifi) caps?.transportInfo as? WifiInfo else null
        } else {
            @Suppress("DEPRECATION")
            (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
        }
    } catch (ignored: RuntimeException) {
        null
    }
}
