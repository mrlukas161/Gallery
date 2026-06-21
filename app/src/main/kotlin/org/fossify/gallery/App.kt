package org.fossify.gallery

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

    override fun onCreate() {
        super.onCreate()
        // Legitímny fork (vlastný podpis aj balík), nie pirátska kópia — vypni Fossify anti-fork kontrolu.
        baseConfig.appSideloadingStatus = SIDELOADING_FALSE
        Reprint.initialize(this)
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
    }
}
