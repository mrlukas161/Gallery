package org.fossify.gallery.compare

import android.app.Activity
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.helpers.CompareAnalysis
import org.fossify.gallery.helpers.PhotoScore

// Údaje o fotkách (PhotoInfoLoader) a skóre (CompareAnalysis) na vlákne na pozadí; výsledky na hlavné vlákno.
// Nový beh zruší starší (po zmazaní / vrátení z Koša), stop() pri zatvorení obrazovky ukončí beh po aktuálnej
// fotke — model tvárí sa zatvára vo vlákne behu (CompareAnalysis), nie tu.
class CompareRunner(private val activity: Activity, private val callback: Callback) {
    interface Callback {
        fun onInfos(infos: Map<String, PhotoInfo>)

        fun onProgress(done: Int, total: Int, waiting: Boolean)

        fun onScores(scores: Map<String, PhotoScore>, final: Boolean)
    }

    @Volatile
    private var generation = 0

    @Volatile
    private var stopped = false

    fun start(paths: List<String>, withInfos: Boolean) {
        val gen = ++generation
        val current = { !stopped && gen == generation }
        callback.onProgress(0, paths.size, false)
        ensureBackgroundThread {
            if (withInfos) {
                val infos = paths.associateWith { PhotoInfoLoader.load(it) }
                post(current) { callback.onInfos(infos) }
            }
            val result = CompareAnalysis.analyze(
                activity.applicationContext,
                paths,
                { done, total -> post(current) { callback.onProgress(done, total, false) } },
                { !current() },
                object : CompareAnalysis.Listener {
                    override fun onPartial(scores: Map<String, PhotoScore>) =
                        post(current) { callback.onScores(scores, false) }

                    override fun onWaitingForFaces() = post(current) { callback.onProgress(0, paths.size, true) }
                },
            )
            post(current) { callback.onScores(result, true) }
        }
    }

    fun stop() {
        stopped = true
    }

    private fun post(current: () -> Boolean, action: () -> Unit) {
        activity.runOnUiThread { if (current() && !activity.isDestroyed) action() }
    }
}
