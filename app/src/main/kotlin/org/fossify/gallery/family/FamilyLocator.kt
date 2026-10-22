package org.fossify.gallery.family

import android.content.Context
import org.fossify.gallery.BuildConfig

// Kde hľadať spárovaný telefón: ručná adresa (len debug build) → posledná známa adresa → NSD.
class FamilyLocator(context: Context, private val prefs: FamilyPrefs) {
    private val appContext = context.applicationContext

    // ladiaca ručná IP:port; v release builde vždy null
    fun manual(): FamilyEndpoint? = if (BuildConfig.DEBUG) FamilyEndpoint.parse(prefs.manualEndpoint) else null

    // adresy na vyskúšanie; NSD sa spustí, až keď predchádzajúce nevyšli (sequence je lenivá)
    fun candidates(deviceId: String, publicTag: String, timeoutMs: Long): Sequence<FamilyEndpoint> = sequence {
        val manual = manual()
        if (manual != null) {
            yield(manual)
        } else {
            val last = prefs.lastAddress(deviceId)
            if (last != null) yield(last)
            val found = FamilyDiscovery(appContext).find(publicTag, timeoutMs)
            if (found != null && found != last) yield(found)
        }
    }
}
