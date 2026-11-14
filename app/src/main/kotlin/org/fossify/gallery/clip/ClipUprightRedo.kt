package org.fossify.gallery.clip

import android.content.Context
import org.fossify.gallery.faces.UprightDecoder

// Postupný prepočet CLIP vektorov spočítaných staršou verziou BEZ otočenia podľa EXIF (revízia 11/2026).
// Schéma clip.db sa nemení: pri prvom behu sa do prefs zapíše čas začiatku prepočtu a vektor staršieho
// dáta z fotky s EXIF otočením/zrkadlením sa spočíta znova. Insert REPLACE ho prepíše novým časom, takže
// zastavený beh pokračuje tam, kde skončil. Starý vektor ostáva v indexe, kým ho nový nenahradí —
// hľadanie funguje celý čas. Po dobehnutí celého behu sa zapíše verzia a ďalej sa nič nekontroluje.
object ClipUprightRedo {
    private const val PREFS = "galeria_faces"
    private const val KEY_VERSION = "clip_upright_version"
    private const val KEY_SINCE = "clip_upright_since"
    private const val VERSION = 1

    // cesty na prepočet (len povolené = allowed); null = prepočet je hotový
    fun pending(context: Context, dao: ClipDao, allowed: Set<String>, running: () -> Boolean): List<String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= VERSION) return null
        var since = prefs.getLong(KEY_SINCE, 0L)
        if (since <= 0L) {
            since = System.currentTimeMillis()
            prefs.edit().putLong(KEY_SINCE, since).apply()
        }
        val redo = ArrayList<String>()
        for (path in dao.getPathsIndexedBefore(since)) {
            if (!running()) break
            if (path in allowed && UprightDecoder.isTransformed(path)) redo.add(path)
        }
        return redo
    }

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_VERSION, VERSION).apply()
    }
}
