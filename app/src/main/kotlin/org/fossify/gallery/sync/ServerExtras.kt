package org.fossify.gallery.sync

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.getFavoriteFromPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.helpers.MotionPhoto
import org.fossify.gallery.helpers.SpecialPhoto
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.LocalDateTime
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Obľúbené zdieľané s appkou (favoritesDB + mediaDB.is_favorite): GET /api/favorites, POST /api/favorite.
// Množina ciest sa načíta pri obnove indexu (a najviac raz za RELOAD_MS pri GET), zápis z PC ju mení hneď.
// Zmena posunie favGen (ping) a cez onChanged aj gen zoznamu, takže /api/list?sinceGen vráti nové "fav".
class ServerFavorites(
    private val appContext: Context,
    private val index: () -> Map<Long, MediaServer.Item>,
    private val onChanged: (Collection<Long>) -> Unit,
) {
    companion object {
        const val MAX_IDS = 2000
        private const val MAX_BODY_BYTES = 64L * 1024
        private const val RELOAD_MS = 15_000L
    }

    // normalizované cesty (FavMatch.norm); gen začína časom štartu — po reštarte servera sa nezhoduje so starým
    @Volatile
    private var paths: Set<String> = emptySet()
    private val gen = AtomicLong(System.currentTimeMillis())
    private val lock = Any()

    @Volatile
    private var loadedAt = 0L

    fun gen(): Long = gen.get()

    fun isFav(path: String): Boolean {
        val p = paths
        return p.isNotEmpty() && FavMatch.norm(path) in p
    }

    // načíta obľúbené z DB appky (volať mimo hlavného vlákna); vráti id, ktorým sa stav zmenil
    fun reload(): Set<Long> {
        val fresh = try {
            FavMatch.normalize(appContext.favoritesDB.getValidFavoritePaths())
        } catch (ignored: RuntimeException) {
            return emptySet()
        }
        val changed = synchronized(lock) {
            val first = loadedAt == 0L
            val diff = if (first) emptySet() else FavMatch.changed(paths, fresh)
            paths = fresh
            loadedAt = System.currentTimeMillis()
            diff
        }
        if (changed.isEmpty()) return emptySet()
        gen.incrementAndGet()
        return index().values.filter { FavMatch.norm(it.path) in changed }.mapTo(HashSet()) { it.id }
    }

    // {"ids":[…], "gen":…} — obľúbené fotky a videá z indexu servera (bez zamknutých/vylúčených priečinkov)
    fun get(): Response {
        if (System.currentTimeMillis() - loadedAt > RELOAD_MS) reload().takeIf { it.isNotEmpty() }?.let(onChanged)
        val ids = JSONArray()
        index().values.forEach { if (isFav(it.path)) ids.put(it.id) }
        return Http.json(Status.OK, JSONObject().put("ids", ids).put("gen", gen.get()))
    }

    // {"ids":[…], "fav":true|false} -> {"changed":[…], "fav":…, "gen":…}; neznáme id sa vynechajú
    fun post(session: IHTTPSession): Response {
        val body = Http.readJson(session, MAX_BODY_BYTES)
        val count = body.optJSONArray("ids")?.length() ?: 0
        val ids = RequestGuard.parseIds(body, MAX_IDS)
        val code = when {
            count > MAX_IDS -> "too_many"
            ids == null -> "bad ids"
            !body.has("fav") -> "bad fav"
            else -> null
        }
        if (code != null || ids == null) throw HttpError(Status.BAD_REQUEST, code ?: "bad ids")
        val fav = body.optBoolean("fav", true)
        val changed = apply(ids, fav)
        if (changed.isNotEmpty()) {
            gen.incrementAndGet()
            onChanged(changed)
        }
        val o = JSONObject().put("changed", JSONArray(changed)).put("fav", fav).put("gen", gen.get())
        return Http.json(Status.OK, o)
    }

    // zapíše zmenu len fotkám, ktorým sa stav naozaj mení; vráti ich id
    private fun apply(ids: List<Long>, fav: Boolean): List<Long> {
        val idx = index()
        val changed = ArrayList<Long>()
        synchronized(lock) {
            val cur = HashSet(paths)
            // len známe fotky, ktorým sa stav naozaj mení
            val todo = ids.mapNotNull { id -> idx[id]?.takeIf { it.path.isNotEmpty() } }
                .filter { (FavMatch.norm(it.path) in cur) != fav }
            todo.forEach { item ->
                if (write(item, fav)) {
                    val key = FavMatch.norm(item.path)
                    if (fav) cur.add(key) else cur.remove(key)
                    changed.add(item.id)
                }
            }
            paths = cur
        }
        return changed
    }

    // rovnaký zápis ako appka (bez Context.updateFavorite — ten pri chybe ukazuje toast)
    private fun write(item: MediaServer.Item, fav: Boolean): Boolean = try {
        if (fav) {
            appContext.favoritesDB.insert(appContext.getFavoriteFromPath(item.path))
        } else {
            appContext.favoritesDB.deleteFavoritePath(item.path)
        }
        appContext.mediaDB.updateFavorite(item.path, fav)
        mirrorToMediaStore(item, fav)
        true
    } catch (ignored: RuntimeException) {
        false
    }

    // bonus pre iné galérie (Android 11+): MediaStore IS_FAVORITE, len ak to systém dovolí bez dialógu
    private fun mirrorToMediaStore(item: MediaServer.Item, fav: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.IS_FAVORITE, if (fav) 1 else 0)
            appContext.contentResolver.update(item.uri, v, null, null)
        } catch (ignored: SecurityException) {
        } catch (ignored: RuntimeException) {
        }
    }
}

// Pohyblivé fotky (MotionPhoto): "motion" v /api/list z trvalej cache skenu „Špeciálnych fotiek“ (bez čítania
// súborov), "motion":{"len":…} v /api/meta a video výrez cez GET /api/motion?id= (Range, 206).
class ServerMotion(private val appContext: Context) {
    companion object {
        private const val CACHE_CONTROL = "private, max-age=3600"
    }

    @Volatile
    private var known: Map<String, Pair<Long, Long>> = emptyMap()

    @Volatile
    private var stamp = -1L

    // pri obnove indexu: cache skenu sa číta znova len po zmene jej súboru; vráti cesty so zmeneným stavom
    fun reload(): Set<String> {
        val st = SpecialPhoto.ScanCache.stamp(appContext)
        if (st == stamp) return emptySet()
        val fresh = if (st <= 0L) {
            emptyMap()
        } else {
            try {
                SpecialPhoto.ScanCache.open(appContext).entriesWith(SpecialPhoto.Kind.MOTION)
            } catch (ignored: RuntimeException) {
                return emptySet()
            }
        }
        val old = known
        val first = stamp == -1L
        known = fresh
        stamp = st
        if (first) return emptySet()
        return (old.keys + fresh.keys).filterTo(HashSet()) { old[it] != fresh[it] }
    }

    fun isMotion(item: MediaServer.Item): Boolean {
        val e = known[item.path] ?: return false
        return MotionCacheMatch.matches(e.first, e.second, item.mtime, item.size)
    }

    // /api/meta: dĺžka vloženého videa (MotionPhoto číta hlavičku súboru a výsledok si pamätá)
    fun metaInto(o: JSONObject, item: MediaServer.Item) {
        if (!item.mime.startsWith("image/") || item.path.isEmpty()) return
        val info = MotionPhoto.readCached(item.path) ?: return
        o.put("motion", JSONObject().put("len", info.videoLength))
    }

    // video/mp4 výrez [videoOffset, videoOffset+videoLength) s Range voči dĺžke výrezu; neoznačuje „✓ v PC“
    fun handle(session: IHTTPSession, item: MediaServer.Item): Response {
        val image = item.mime.startsWith("image/") && item.path.isNotEmpty()
        val info = (if (image) MotionPhoto.readCached(item.path) else null)
            ?: return Http.error(Status.NOT_FOUND, "not_motion")
        val etag = ETags.of("m", item.id, item.mtime, item.size, info.videoOffset, info.videoLength)
        val h = session.headers
        if (ETags.matches(h["if-none-match"], etag)) return Http.notModified(etag)
        val resp = when (val s = MotionSlice.of(h["range"], info.videoOffset, info.videoLength, h["if-range"], etag)) {
            is MotionSlice.Result.Unsatisfiable -> Http.error(Status.RANGE_NOT_SATISFIABLE, "range").also {
                it.addHeader("Content-Range", s.contentRange)
            }
            is MotionSlice.Result.Full -> slice(item, s.absStart, s.len, Status.OK)
            is MotionSlice.Result.Partial -> slice(item, s.absStart, s.len, Status.PARTIAL_CONTENT).also {
                it.addHeader("Content-Range", s.contentRange)
            }
        }
        resp.addHeader("ETag", etag)
        resp.addHeader("Cache-Control", CACHE_CONTROL)
        resp.addHeader("Accept-Ranges", "bytes")
        return resp
    }

    private fun slice(item: MediaServer.Item, start: Long, len: Long, status: Status): Response {
        val pfd = appContext.contentResolver.openFileDescriptor(item.uri, "r")
            ?: return Http.error(Status.NOT_FOUND, "open failed")
        val stream: InputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        try {
            var left = start
            while (left > 0) {
                val n = stream.skip(left)
                if (n <= 0) throw IOException("skip failed")
                left -= n
            }
        } catch (e: IOException) {
            stream.close()
            throw e
        }
        return NanoHTTPD.newFixedLengthResponse(status, "video/mp4", stream, len)
    }
}

// POST /api/zip: viac súborov ako jeden ZIP, streamovaný (chunked) z vlastného vlákna cez rúru — nič sa
// nenačíta do pamäte. ZIP bez kompresie (fotky a videá sa ďalej nezmenšia), data descriptor = netreba CRC
// vopred. Najviac MAX_PARALLEL ZIP-ov naraz (každý = 1 spojenie), inak 503 busy. „✓ v PC“ až keď prúd
// skončil celý a bez chyby, len pre súbory, ktoré v ňom celé sú.
class ServerZip(
    private val appContext: Context,
    private val index: () -> Map<Long, MediaServer.Item>,
    private val onSynced: (List<String>) -> Unit,
) {
    companion object {
        const val MAX_PARALLEL = 2
        private const val BUFFER = 64 * 1024
        private const val PIPE = 256 * 1024
        private const val RETRY_AFTER_S = "2"
        private const val MS = 1000L
    }

    private val slots = Semaphore(MAX_PARALLEL)

    fun handle(ids: List<Long>, now: LocalDateTime): Response {
        if (!slots.tryAcquire()) {
            return Http.error(Status.SERVICE_UNAVAILABLE, "busy").also { it.addHeader("Retry-After", RETRY_AFTER_S) }
        }
        val idx = index()
        val job = Job(ids.mapNotNull { idx[it] }, ids.filter { it !in idx })
        val pipeIn = PipedInputStream(PIPE)
        val pipeOut = try {
            PipedOutputStream(pipeIn)
        } catch (e: IOException) {
            slots.release()
            throw e
        }
        val body = EndStream(pipeIn, { job.failed }) { complete ->
            slots.release()
            if (complete && job.ok) onSynced(job.done)
        }
        val t = Thread({ job.write(pipeOut) }, "galeria-zip")
        t.isDaemon = true
        t.start()
        val r = NanoHTTPD.newChunkedResponse(Status.OK, "application/zip", body)
        r.addHeader("Content-Disposition", ContentDisposition.of("attachment", ZipRequest.fileName(now)))
        r.addHeader("Cache-Control", "no-store")
        // po ZIP-e spojenie zavrieť (pri chybe musí prehliadač vidieť nedokončené sťahovanie)
        r.closeConnection(true)
        return r
    }

    private inner class Job(private val items: List<MediaServer.Item>, private val unknown: List<Long>) {
        @Volatile
        var ok = false

        @Volatile
        var failed = false
        val done = ArrayList<String>()

        fun write(out: OutputStream) {
            val zos = ZipOutputStream(BufferedOutputStream(out, BUFFER))
            try {
                zos.setMethod(ZipOutputStream.DEFLATED)
                zos.setLevel(Deflater.NO_COMPRESSION)
                val names = ZipNames()
                val unreadable = ArrayList<String>()
                items.forEach { if (add(zos, names, it)) done.add(it.path) else unreadable.add(it.name) }
                ZipRequest.missingText(unknown, unreadable)?.let { txt ->
                    zos.putNextEntry(ZipEntry(names.unique(ZipRequest.MISSING_NAME)))
                    zos.write(txt.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                // ok ešte pred zavretím rúry — čitateľ hneď po konci prúdu podľa neho zapíše „✓ v PC“
                zos.finish()
                zos.flush()
                ok = true
                zos.close()
            } catch (ignored: IOException) {
                abort(out, zos)
            } catch (ignored: RuntimeException) {
                abort(out, zos)
            }
        }

        // klient zavrel spojenie alebo sa súbor nedal dočítať: najprv zavrieť rúru (bez konca ZIP-u —
        // prehliadač uvidí nedokončené sťahovanie), potom ZipOutputStream (uvoľní Deflater), nič sa neoznačí
        private fun abort(out: OutputStream, zos: ZipOutputStream) {
            failed = true
            for (c in listOf(out, zos)) {
                try {
                    c.close()
                } catch (ignored: IOException) {
                }
            }
        }

        // false = súbor sa nedal otvoriť (preskočí sa a pôjde do chybajuce.txt)
        private fun add(zos: ZipOutputStream, names: ZipNames, item: MediaServer.Item): Boolean {
            val input = try {
                OriginalMedia.openStream(appContext.contentResolver, item.uri)
            } catch (ignored: IOException) {
                null
            } catch (ignored: SecurityException) {
                null
            } ?: return false
            input.use { ins ->
                val e = ZipEntry(names.unique(item.name))
                val modified = if (item.path.isNotEmpty()) File(item.path).lastModified() else 0L
                e.time = if (modified > 0) modified else item.mtime * MS
                zos.putNextEntry(e)
                ins.copyTo(zos, BUFFER)
                zos.closeEntry()
            }
            return true
        }
    }

    // telo odpovede: po zatvorení raz zavolá onEnd(complete) — complete = prečítané až po koniec prúdu;
    // keď zápis ZIP-u zlyhal, koniec prúdu je chyba (NanoHTTPD neukončí chunked odpoveď)
    private class EndStream(
        inner: InputStream,
        private val failed: () -> Boolean,
        private val onEnd: (Boolean) -> Unit,
    ) : FilterInputStream(inner) {
        private var eof = false
        private var ended = false

        override fun read(): Int = check(super.read())

        override fun read(b: ByteArray, off: Int, len: Int): Int = check(super.read(b, off, len))

        private fun check(n: Int): Int {
            if (n >= 0) return n
            if (failed()) throw IOException("zip failed")
            eof = true
            return n
        }

        override fun close() {
            try {
                super.close()
            } finally {
                end()
            }
        }

        @Synchronized
        private fun end() {
            if (ended) return
            ended = true
            onEnd(eof)
        }
    }
}
