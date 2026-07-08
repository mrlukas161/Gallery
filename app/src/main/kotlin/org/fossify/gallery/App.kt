package org.fossify.gallery

import android.content.Context
import com.github.ajalt.reprint.core.Reprint
import com.squareup.picasso.Downloader
import com.squareup.picasso.Picasso
import okhttp3.Request
import okhttp3.Response
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.SIDELOADING_FALSE

class App : FossifyApp() {

    override val isAppLockFeatureAvailable = true

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // NAJSKORŠÍ možný bod (pred onCreate aj pred akoukoľvek Activity). Synchronne (.commit) prepíš
        // prípadný STARÝ zapamätaný stav „sideloaded=TRUE" (prežije aj update appky) na FALSE, aby sa
        // Fossify anti-fork „fake app" dialóg nemohol ukázať. Kľúče overené z commons 6.1.6.
        try {
            base.getSharedPreferences("Prefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("app_sideloading_status", SIDELOADING_FALSE)
                .commit()
        } catch (ignored: Throwable) {
        }
    }

    override fun onCreate() {
        // Legitímny fork (vlastný podpis aj balík), nie pirátska kópia — vypni Fossify anti-fork kontrolu.
        // Nastav EŠTE PRED super, aby prípadný starý zapamätaný stav TRUE nič nevyvolal.
        baseConfig.appSideloadingStatus = SIDELOADING_FALSE
        super.onCreate()
        Reprint.initialize(this)
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
    }
}
