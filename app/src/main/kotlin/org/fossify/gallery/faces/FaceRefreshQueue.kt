package org.fossify.gallery.faces

import android.content.Context
import org.fossify.commons.helpers.ensureBackgroundThread
import java.util.concurrent.atomic.AtomicBoolean

// A6: fronta fotiek na prepracovanie tvárí (po otočení/prepísaní, keď sa rámčeky nedali otočiť
// bezpečne). Prežije reštart appky; spracuje sa hneď na pozadí (jednorazovo, nie trvalá úloha)
// a čo sa nestihne, dobehne pri ďalšom spracovaní knižnice (FaceRelinker.relink).
object FaceRefreshQueue {
    private const val PREFS = "galeria_faces"
    private const val KEY_PENDING = "faces_refresh_pending"

    private val processing = AtomicBoolean(false)
    private val lock = Any()

    fun add(context: Context, path: String) {
        val app = context.applicationContext
        update(app) { it.add(path) }
        processAsync(app)
    }

    // Pri bežiacom celom prepracovaní tvárí sa nerobí nič — fronta ostane na ďalší pokus.
    fun processAsync(context: Context) {
        val app = context.applicationContext
        if (RefreshFaces.isRunning || pending(app).isEmpty() || !processing.compareAndSet(false, true)) return
        ensureBackgroundThread {
            try {
                val done = RefreshFaces.refreshPaths(app, pending(app))
                if (done.isNotEmpty()) update(app) { it.removeAll(done) }
            } catch (ignored: Throwable) {
            } finally {
                processing.set(false)
            }
        }
    }

    private fun pending(app: Context): Set<String> = synchronized(lock) {
        HashSet(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_PENDING, null).orEmpty())
    }

    private fun update(app: Context, change: (MutableSet<String>) -> Boolean) {
        synchronized(lock) {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val set = HashSet(prefs.getStringSet(KEY_PENDING, null).orEmpty())
            if (change(set)) prefs.edit().putStringSet(KEY_PENDING, set).apply()
        }
    }
}
