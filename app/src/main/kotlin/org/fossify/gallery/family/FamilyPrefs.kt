package org.fossify.gallery.family

import android.content.Context
import android.content.SharedPreferences

// Drobný stav rodinného prepojenia mimo family.db (nie je nenahraditeľný): posledná známa adresa
// telefónov, telefóny čakajúce na oznámenie o zrušení prepojenia a ladiaca ručná adresa.
class FamilyPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // zrušené prepojenia, o ktorých sa druhý telefón ešte nedozvedel (kľúč sa drží len na toto oznámenie)
    fun pendingUnpairs(): Set<String> = prefs.getStringSet(KEY_UNPAIR_PENDING, null)?.toSet().orEmpty()

    fun isPendingUnpair(deviceId: String): Boolean = deviceId in pendingUnpairs()

    fun setPendingUnpair(deviceId: String, pending: Boolean) {
        val set = pendingUnpairs().toMutableSet()
        if (pending) set.add(deviceId) else set.remove(deviceId)
        prefs.edit().putStringSet(KEY_UNPAIR_PENDING, set).apply()
    }

    fun lastAddress(deviceId: String): FamilyEndpoint? =
        FamilyEndpoint.parse(prefs.getString(KEY_ADDR + deviceId, null))

    fun rememberAddress(deviceId: String, endpoint: FamilyEndpoint?) {
        prefs.edit().apply {
            if (endpoint == null) remove(KEY_ADDR + deviceId) else putString(KEY_ADDR + deviceId, endpoint.toString())
        }.apply()
    }

    // Najvyššia hodnota lamport hodín, akú tento telefón kedy rozdal vlastnej operácii. Je MIMO family.db, aby
    // po obnove staršej family.db (záloha) nové operácie nedostali lamport, ktorý druhá strana už „má“ — inak by
    // si ich nikdy nevypýtala (kurzor prenosu je lamport).
    var lamportHighWater: Long
        get() = prefs.getLong(KEY_LAMPORT_HIGH, 0)
        set(value) {
            if (value > lamportHighWater) prefs.edit().putLong(KEY_LAMPORT_HIGH, value).apply()
        }

    // posledné pokusy pri otvorení appky skončili bez spojenia (predlžovanie intervalu, FamilySchedule)
    var resumeFailures: Int
        get() = prefs.getInt(KEY_RESUME_FAILURES, 0)
        set(value) = prefs.edit().putInt(KEY_RESUME_FAILURES, value).apply()

    // ladenie (len debug build): ručná IP:port namiesto NSD, napr. emulátor + `adb forward`
    var manualEndpoint: String
        get() = prefs.getString(KEY_MANUAL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MANUAL, value.trim()).apply()

    companion object {
        const val PREFS = "galeria_family"
        private const val KEY_UNPAIR_PENDING = "unpair_pending"
        private const val KEY_ADDR = "addr_"
        private const val KEY_MANUAL = "debug_manual_endpoint"
        private const val KEY_LAMPORT_HIGH = "lamport_high_water"
        private const val KEY_RESUME_FAILURES = "resume_failures"
    }
}
