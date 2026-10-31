package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.fossify.gallery.compare.CompareTexts
import org.fossify.gallery.faces.PhotoQuality
import org.fossify.gallery.faces.UprightDecoder
import java.io.File
import java.io.IOException

// Spoločný výpočet skóre porovnávača pre obrazovku porovnania, zoznam skupín aj porovnávač na PC.
// Volať z vlákna na pozadí. Každá fotka sa dekóduje RAZ (UprightDecoder, ≤ 800 px) a z jednej bitmapy
// sa zmeria ostrosť, expozícia aj tváre. Model tvárí (PhotoQuality) sa vytvorí raz na beh, až keď ho
// treba, po získaní miesta v HeavyGate — ak ho drží spracovanie knižnice dlhšie ako FACE_WAIT_MS, beh
// pokračuje bez tvárí (facesChecked = false). Merania sa pamätajú podľa cesty + času zmeny + veľkosti
// (CompareCache v pamäti a malý súbor v cacheDir, nie v people/faces/extras.db).
object CompareAnalysis {
    private const val CACHE_FILE = "compare_analysis.tsv"
    private const val FACE_WAIT_MS = 2500L
    private const val ROTATED = 90

    private val cache = CompareCache()
    private val diskLock = Any()

    @Volatile
    private var diskLoaded = false

    // priebežné výsledky a čakanie na model tvárí (voliteľné)
    interface Listener {
        fun onPartial(scores: Map<String, PhotoScore>) {}

        fun onWaitingForFaces() {}
    }

    fun analyze(
        context: Context,
        paths: List<String>,
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean,
        listener: Listener? = null,
    ): Map<String, PhotoScore> = CompareScoring.score(measure(context, paths, onProgress, isCancelled, listener))

    // surové merania (bez skóre) — v poradí vstupu, bez fotiek, ktoré sa nedali prečítať
    fun measure(
        context: Context,
        paths: List<String>,
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean,
        listener: Listener? = null,
    ): List<PhotoMeasure> {
        val app = context.applicationContext
        loadDisk(app)
        val out = ArrayList<PhotoMeasure>(paths.size)
        FaceRun(app, isCancelled, listener).use { run ->
            for ((i, path) in paths.withIndex()) {
                if (isCancelled()) break
                val file = File(path)
                val modified = file.lastModified()
                val size = file.length()
                val cached = cache.get(path, modified, size)
                val reuse = cached != null && (cached.facesChecked || run.unavailable)
                val m = if (reuse) cached else measureOne(path, modified, run)
                if (m != null) {
                    out.add(m)
                    cache.put(m, size)
                }
                onProgress(i + 1, paths.size)
                listener?.onPartial(CompareScoring.score(out))
            }
        }
        saveDisk(app)
        return out
    }

    // čitateľné zdôvodnenie skóre („ostrejšia o 18 %, všetci 3 majú otvorené oči“)
    fun describe(context: Context, score: PhotoScore, path: String): String = CompareTexts.reasons(context, score, path)

    private fun measureOne(path: String, modified: Long, run: FaceRun): PhotoMeasure? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = try {
            UprightDecoder.decode(path, PhotoQuality.DECODE_SIZE)
        } catch (ignored: OutOfMemoryError) {
            null
        } ?: return null
        val rotated = decoded.rotationDegrees % (2 * ROTATED) == ROTATED
        val w = if (rotated) bounds.outHeight else bounds.outWidth
        val h = if (rotated) bounds.outWidth else bounds.outHeight
        val bmp: Bitmap = decoded.bitmap
        return try {
            val gray = Sharpness.grayOf(bmp)
            val faces = run.faces(bmp)
            PhotoMeasure(
                path = path,
                sharpness = Sharpness.ofGray(gray),
                exposure = ImageStats.exposure(gray.pixels),
                faces = faces?.faces ?: 0,
                closedEyes = faces?.closedEyes ?: 0,
                smiles = faces?.smiles ?: 0,
                facesChecked = faces != null,
                width = w,
                height = h,
                modified = modified,
            )
        } finally {
            bmp.recycle()
        }
    }

    private fun loadDisk(context: Context) {
        if (diskLoaded) return
        synchronized(diskLock) {
            if (diskLoaded) return
            diskLoaded = true
            try {
                val f = File(context.cacheDir, CACHE_FILE)
                if (f.exists()) cache.load(f.readLines())
            } catch (ignored: IOException) {
            }
        }
    }

    private fun saveDisk(context: Context) {
        synchronized(diskLock) {
            try {
                val tmp = File(context.cacheDir, "$CACHE_FILE.tmp")
                tmp.writeText(cache.dump().joinToString("\n"))
                tmp.renameTo(File(context.cacheDir, CACHE_FILE))
            } catch (ignored: IOException) {
            }
        }
    }

    // model tvárí jedného behu: lenivo, cez HeavyGate, s krátkym čakaním; zatvára sa v close() na tom istom vlákne
    private class FaceRun(
        private val context: Context,
        isCancelled: () -> Boolean,
        listener: Listener?,
    ) : AutoCloseable {
        private var deadline = 0L
        private val slot = HeavySlot(
            active = { !isCancelled() && System.currentTimeMillis() < deadline },
            onWait = { listener?.onWaitingForFaces() },
        )
        private var quality: PhotoQuality? = null

        // model nie je k dispozícii (nenačítal sa alebo HeavyGate bola obsadená) — beh ide bez tvárí
        var unavailable = false
            private set

        fun faces(bmp: Bitmap): PhotoQuality.Faces? {
            if (unavailable) return null
            val q = quality ?: open() ?: return null
            return q.faces(bmp)
        }

        private fun open(): PhotoQuality? {
            deadline = System.currentTimeMillis() + FACE_WAIT_MS
            if (!slot.ensure()) {
                unavailable = true
                return null
            }
            val q = PhotoQuality(context)
            if (!q.isAvailable) {
                q.close()
                slot.close()
                unavailable = true
                return null
            }
            quality = q
            return q
        }

        override fun close() {
            quality?.close()
            quality = null
            slot.close()
        }
    }
}
