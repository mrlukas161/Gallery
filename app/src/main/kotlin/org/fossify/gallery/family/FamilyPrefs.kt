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

    // ladenie (len debug build): ručná IP:port namiesto NSD, napr. emulátor + `adb forward`
    var manualEndpoint: String
        get() = prefs.getString(KEY_MANUAL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MANUAL, value.trim()).apply()

    companion object {
        const val PREFS = "galeria_family"
        private const val KEY_UNPAIR_PENDING = "unpair_pending"
        private const val KEY_ADDR = "addr_"
        private const val KEY_MANUAL = "debug_manual_endpoint"
    }
}
