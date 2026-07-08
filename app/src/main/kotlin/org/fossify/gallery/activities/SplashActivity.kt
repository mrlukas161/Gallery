package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.isAutoTheme
import org.fossify.commons.extensions.isSystemInDarkMode
import org.fossify.commons.extensions.syncGlobalConfig
import org.fossify.commons.helpers.SIDELOADING_FALSE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.getFavoriteFromPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.models.Favorite

// Galéria+: SplashActivity zámerne NEDEDÍ z commons BaseSplashActivity, aby sa vôbec NESPUSTILA
// jeho anti-sideloading kontrola (jediné miesto, kde sa ten „fake app" dialóg volá). Legitímny fork
// s vlastným podpisom aj balíkom — kontrola nemá zmysel. Zvyšok správania splashu je zreplikovaný 1:1.
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseConfig.appSideloadingStatus = SIDELOADING_FALSE
        syncGlobalConfig {
            if (isAutoTheme()) {
                val isDark = isSystemInDarkMode()
                baseConfig.textColor = resources.getColor(
                    if (isDark) org.fossify.commons.R.color.theme_dark_text_color else org.fossify.commons.R.color.theme_light_text_color
                )
                baseConfig.backgroundColor = resources.getColor(
                    if (isDark) org.fossify.commons.R.color.theme_dark_background_color else org.fossify.commons.R.color.theme_light_background_color
                )
            }
            initActivity()
        }
    }

    private fun initActivity() {
        if (config.wereFavoritesMigrated) {
            launchActivity()
        } else {
            if (config.appRunCount == 0) {
                config.wereFavoritesMigrated = true
                launchActivity()
            } else {
                config.wereFavoritesMigrated = true
                ensureBackgroundThread {
                    val favorites = ArrayList<Favorite>()
                    val favoritePaths = mediaDB.getFavorites().map { it.path }.toMutableList() as ArrayList<String>
                    favoritePaths.forEach {
                        favorites.add(getFavoriteFromPath(it))
                    }
                    favoritesDB.insertAll(favorites)

                    runOnUiThread {
                        launchActivity()
                    }
                }
            }
        }
    }

    private fun launchActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
