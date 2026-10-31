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
// porovnavac-W2: analyzeGroups = viac skupín v jednom behu (hromadné „Ponechať najlepšiu“, /api/quality),
// cachedState = čo je už spočítané (aj fotky, ktoré sa nedajú prečítať, aby sa nepočítali dokola).
object CompareAnalysis {
    private const val CACHE_FILE = "compare_analysis.tsv"
    private const val FACE_WAIT_MS = 2500L
    private const val ROTATED = 90
    private const val MAX_UNREADABLE = 500

    private val cache = CompareCache()
    private val diskLock = Any()

    // fotky, ktoré sa nedali prečítať — len v pamäti procesu
    private val unreadable = Unreadable()

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
        FaceRun(app, isCancelled, listener, FACE_WAIT_MS).use { run ->
            for ((i, path) in paths.withIndex()) {
                if (isCancelled()) break
                measureCached(path, run)?.let { out.add(it) }
                onProgress(i + 1, paths.size)
                listener?.onPartial(CompareScoring.score(out))
            }
        }
        saveDisk(app)
        return out
    }

    // porovnavac-W2: viac skupín v jednom behu — každá fotka sa zmeria raz (aj keď je vo viacerých
    // skupinách), model tvárí sa načíta raz a miesto v HeavyGate sa drží do konca behu (najviac
    // faceWaitMs sa naň čaká, potom beh ide bez tvárí). Skóre sa ráta zvlášť pre každú skupinu.
    // onGroup(hotové skupiny, všetky), onPhoto(zmerané fotky, všetky rôzne fotky). Výsledok má po zrušení
    // menej položiek než groups (len dokončené skupiny, v poradí vstupu).
    fun analyzeGroups(
        context: Context,
        groups: List<List<String>>,
        faceWaitMs: Long,
        onGroup: (done: Int, total: Int) -> Unit,
        onPhoto: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean,
        listener: Listener? = null,
    ): List<Map<String, PhotoScore>> {
        val app = context.applicationContext
        loadDisk(app)
        val out = ArrayList<Map<String, PhotoScore>>(groups.size)
        FaceRun(app, isCancelled, listener, faceWaitMs).use { run ->
            val batch = Batch(run, groups.flatMapTo(HashSet()) { it }.size, onPhoto)
            for ((gi, paths) in groups.withIndex()) {
                // lenivo: zrušenie sa prejaví po aktuálnej fotke
                val measures = paths.asSequence().takeWhile { !isCancelled() }.mapNotNull { batch.measure(it) }.toList()
                if (isCancelled()) break
                out.add(CompareScoring.score(measures))
                onGroup(gi + 1, groups.size)
            }
        }
        saveDisk(app)
        return out
    }

    // porovnavac-C (/api/groups pre web a PC): skóre LEN z cache, nič nové nepočíta;
    // null = niektorá fotka skupiny ešte nie je zmeraná (alebo sa zmenila). Fotky, ktoré sa nedali
    // prečítať, sa vynechajú (porovnajú sa ostatné).
    fun cachedScores(context: Context, paths: List<String>): Map<String, PhotoScore>? {
        val state = cachedState(context, paths)
        return if (state.missing == 0 && state.scores.isNotEmpty()) state.scores else null
    }

    // scores = skóre zo zmeraných fotiek (bez nečitateľných), missing = koľko fotiek ešte nie je zmeraných,
    // facesChecked = tváre sa hodnotili na všetkých zmeraných fotkách
    class CachedState(val scores: Map<String, PhotoScore>, val missing: Int, val unreadable: List<String>) {
        val facesChecked: Boolean get() = scores.values.all { it.facesChecked }
    }

    fun cachedState(context: Context, paths: List<String>): CachedState {
        loadDisk(context.applicationContext)
        val measures = ArrayList<PhotoMeasure>(paths.size)
        val bad = ArrayList<String>()
        var missing = 0
        for (path in paths) {
            val file = File(path)
            val modified = file.lastModified()
            val size = file.length()
            val m = cache.get(path, modified, size)
            when {
                m != null -> measures.add(m)
                unreadable.has(path, modified, size) -> bad.add(path)
                else -> missing++
            }
        }
        return CachedState(CompareScoring.score(measures), missing, bad)
    }

    // z cache, ak sa dá (tváre už hodnotené alebo model teraz nie je k dispozícii), inak zmerať a zapamätať
    private fun measureCached(path: String, run: FaceRun): PhotoMeasure? {
        val file = File(path)
        val modified = file.lastModified()
        val size = file.length()
        val cached = cache.get(path, modified, size)
        val reuse = cached != null && (cached.facesChecked || run.unavailable)
        val m = if (reuse) cached else measureOne(path, modified, run)
        if (m != null) cache.put(m, size) else unreadable.add(path, modified, size)
        return m
    }

    // fotky jedného behu analyzeGroups: každá sa zmeria najviac raz, aj keď je vo viacerých skupinách
    private class Batch(private val run: FaceRun, private val total: Int, private val onPhoto: (Int, Int) -> Unit) {
        private val memo = HashMap<String, PhotoMeasure?>()

        fun measure(path: String): PhotoMeasure? {
            if (memo.containsKey(path)) return memo[path]
            val m = measureCached(path, run)
            memo[path] = m
            onPhoto(memo.size, total)
            return m
        }
    }

    // nečitateľné fotky (cesta|čas zmeny|veľkosť), najviac MAX_UNREADABLE
    private class Unreadable {
        private val keys = LinkedHashSet<String>()

        @Synchronized
        fun has(path: String, modified: Long, size: Long): Boolean = "$path|$modified|$size" in keys

        @Synchronized
        fun add(path: String, modified: Long, size: Long) {
            keys.add("$path|$modified|$size")
            while (keys.size > MAX_UNREADABLE) keys.remove(keys.first())
        }
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
        private val waitMs: Long,
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
            deadline = System.currentTimeMillis() + waitMs
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
