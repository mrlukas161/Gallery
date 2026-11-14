package org.fossify.gallery.safety

import android.content.Context
import android.util.Log
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.models.Favorite
import java.io.File
import java.io.IOException

// Obľúbené v automatickej zálohe (N13): zoznam ciest z gallery.db do ZIP-u zálohy (FavoritesBackup.ENTRY)
// a doplnenie chýbajúcich pri obnove. Schéma gallery.db sa nemení. Blokuje → len z pozadia.
object AutoBackupFavorites {
    private const val TAG = "AutoBackupFavorites"

    // obľúbené z gallery.db; fotky zo zamknutých priečinkov nie (záloha leží v Documents)
    @Suppress("TooGenericExceptionCaught")
    fun paths(context: Context): List<String> {
        val app = context.applicationContext
        val all = try {
            app.favoritesDB.getAllFavoritePaths()
        } catch (e: RuntimeException) {
            Log.w(TAG, "obľúbené sa nepodarilo načítať", e)
            return emptyList()
        }
        val config = app.config
        return all.filter { path ->
            try {
                !config.isFolderProtected(path.substringBeforeLast('/'))
            } catch (e: RuntimeException) {
                Log.w(TAG, "zámok priečinka sa nepodarilo overiť", e)
                false
            }
        }
    }

    // obľúbené zo ZIP-u zálohy (null = záloha ich neobsahuje, napr. zo staršej verzie appky)
    @Throws(IOException::class)
    fun read(backups: AutoBackupStore, zip: AutoBackupStore.Item): List<String>? =
        AutoBackupMeta.readTexts(backups, zip)[FavoritesBackup.ENTRY]?.let { FavoritesBackup.decode(it) }

    // chýbajúce obľúbené zo zálohy, ktorých fotka na telefóne existuje (prázdne = nič na doplnenie)
    @Suppress("TooGenericExceptionCaught")
    fun missing(context: Context, backup: List<String>): List<String> {
        val current = try {
            context.applicationContext.favoritesDB.getAllFavoritePaths()
        } catch (e: RuntimeException) {
            Log.w(TAG, "obľúbené sa nepodarilo načítať", e)
            return emptyList()
        }
        return FavoritesBackup.missing(backup, current) { File(it).exists() }
    }

    // Doplní obľúbené (nič neodoberá). Vráti počet doplnených.
    @Suppress("TooGenericExceptionCaught")
    fun restore(context: Context, paths: List<String>): Int {
        val app = context.applicationContext
        var done = 0
        paths.forEach { path ->
            try {
                val favorite = Favorite(null, path, path.substringAfterLast('/'), path.substringBeforeLast('/'))
                app.favoritesDB.insert(favorite)
                app.mediaDB.updateFavorite(path, true)
                done++
            } catch (e: RuntimeException) {
                Log.w(TAG, "obľúbenú fotku sa nepodarilo vrátiť", e)
            }
        }
        return done
    }
}
