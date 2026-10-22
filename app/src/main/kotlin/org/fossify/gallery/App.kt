package org.fossify.gallery

import android.content.ComponentCallbacks2
import android.content.Context
import com.github.ajalt.reprint.core.Reprint
import com.squareup.picasso.Downloader
import com.squareup.picasso.LruCache
import com.squareup.picasso.Picasso
import okhttp3.Request
import okhttp3.Response
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.SIDELOADING_FALSE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.family.BundleHandler
import org.fossify.gallery.family.FamilyHandlers
import org.fossify.gallery.family.OpLogHandler
import org.fossify.gallery.helpers.MemoryPressure
import java.util.concurrent.atomic.AtomicBoolean

class App : FossifyApp() {

    override val isAppLockFeatureAvailable = true

    // pamäťová cache Picassa (predvolená veľkosť ako doteraz) — onTrimMemory ju vyprázdni
    private var picassoCache: LruCache? = null

    // uvoľňovanie na pozadí beží najviac raz naraz (onTrimMemory chodí v sériách)
    private val trimming = AtomicBoolean(false)

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
        // A3: núdzová obnova DB potvrdená pred reštartom sa nasadí skôr, než sa ktorákoľvek DB otvorí
        org.fossify.gallery.safety.DbRestore.applyPending(this)
        Reprint.initialize(this)
        // verzia uložených odtlačkov tvárí (FaceEmbedder.MODEL_ID) — skôr než ju prečíta rodinné prepojenie
        org.fossify.gallery.faces.FaceEmbedVersion.init(this)
        // rodinné prepojenie: op-log osôb sa aplikuje po prijatí z druhého telefónu
        FamilyHandlers.register(OpLogHandler(this))
        // … a fotky s hotovými výsledkami (F4: odoslanie fronty, príjem balíkov)
        FamilyHandlers.register(BundleHandler(this))
        val cache = LruCache(this).also { picassoCache = it }
        Picasso.setSingletonInstance(Picasso.Builder(this).memoryCache(cache).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
    }

    // Appka v pozadí (UI_HIDDEN a vyššie) alebo málo pamäte: uvoľni, čo sa dá kedykoľvek znova načítať —
    // CLIP hľadanie (enkodéry + vektory), Tesseract pre Live Text, výrezy tvárí, snímok Ľudí, Picasso.
    // Pri kritickom nedostatku (RUNNING_CRITICAL, COMPLETE) sa navyše zastavia automaticky spustené
    // ťažké indexery (IndexingService ich odloží, ProcessingPolicy ich pustí neskôr).
    // Všetko je bezpečné voči súbehu (počítadlá používania, zámky); zatváranie modelov beží na pozadí,
    // aby hlavné vlákno nečakalo na prebiehajúce hľadanie alebo výpočet snímku.
    // TRIM_MEMORY_RUNNING_CRITICAL/COMPLETE sú zastarané od API 34, staršie Androidy ich posielajú
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !critical) return
        // len nastaví príznaky zastavenia (rýchle, bez čakania)
        if (critical) MemoryPressure.notifyCritical()
        org.fossify.gallery.faces.FaceCropLoader.clear()
        org.fossify.gallery.faces.FaceContextLoader.clear()
        picassoCache?.clear()
        if (!trimming.compareAndSet(false, true)) return
        ensureBackgroundThread {
            try {
                org.fossify.gallery.clip.ClipSearch.release()
                org.fossify.gallery.faces.OcrIndexer.releaseLiveEngine()
                org.fossify.gallery.faces.PeopleIndex.release()
            } finally {
                trimming.set(false)
            }
        }
    }
}
