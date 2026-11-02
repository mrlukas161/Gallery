package org.fossify.gallery.sync

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

// chyba požiadavky, ktorú MediaServer.serve() premení na JSON odpoveď {"error": code}
class HttpError(val status: Status, val code: String, val close: Boolean = false) : Exception(code)

// Odpovede servera: chyby vždy ako JSON cez JSONObject (nikdy ručne skladaný reťazec).
object Http {
    private const val CODE_LENGTH_REQUIRED = 411
    private const val CODE_TOO_LARGE = 413

    fun param(session: IHTTPSession, name: String): String? = session.parameters[name]?.firstOrNull()

    // telo JSON s limitom veľkosti PRED parseBody (ten číta celé telo bez limitu); pri odmietnutí
    // ostáva telo neprečítané -> spojenie zavrieť, inak by zvyšok tela skazil ďalšiu požiadavku na keep-alive
    fun readJson(session: IHTTPSession, max: Long): JSONObject {
        val reject = when (IndexDiff.deleteBodyReject(session.headers["content-length"], max)) {
            CODE_LENGTH_REQUIRED -> HttpError(Status.LENGTH_REQUIRED, "missing Content-Length", close = true)
            CODE_TOO_LARGE -> HttpError(Status.PAYLOAD_TOO_LARGE, "body too large", close = true)
            else -> null
        }
        if (reject != null) throw reject
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"].orEmpty().ifBlank { "{}" }
        // StackOverflowError: hlboko vnorený JSON by inak zhodil vlákno servera aj appku
        return try {
            JSONObject(raw)
        } catch (ignored: org.json.JSONException) {
            null
        } catch (ignored: StackOverflowError) {
            null
        } ?: throw HttpError(Status.BAD_REQUEST, "bad body")
    }

    fun json(status: Status, body: JSONObject): Response = json(status, body.toString())

    // telo formulára (application/x-www-form-urlencoded) s limitom veľkosti pred parseBody — polia ako
    // session.parameters (aj z adresy); pri odmietnutí sa spojenie zavrie (neprečítané telo)
    fun readForm(session: IHTTPSession, max: Long): Map<String, List<String>> {
        val reject = when (IndexDiff.deleteBodyReject(session.headers["content-length"], max)) {
            CODE_LENGTH_REQUIRED -> HttpError(Status.LENGTH_REQUIRED, "missing Content-Length", close = true)
            CODE_TOO_LARGE -> HttpError(Status.PAYLOAD_TOO_LARGE, "body too large", close = true)
            else -> null
        }
        if (reject != null) throw reject
        session.parseBody(HashMap())
        return session.parameters.orEmpty()
    }

    fun json(status: Status, body: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    fun error(status: Status, code: String): Response = json(status, JSONObject().put("error", code))

    fun notModified(etag: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(Status.NOT_MODIFIED, "text/plain", "")
        r.addHeader("ETag", etag)
        return r
    }

    fun bytes(mime: String, data: ByteArray): Response =
        NanoHTTPD.newFixedLengthResponse(Status.OK, mime, ByteArrayInputStream(data), data.size.toLong())

    // hlavičky pre každú odpoveď: bez MIME hádania, bez Referer (token v adrese), bez vkladania do cudzích stránok;
    // stránka si Referrer-Policy nastavuje sama (same-origin kvôli formuláru /api/zip)
    fun secure(r: Response): Response {
        r.addHeader("X-Content-Type-Options", "nosniff")
        if (r.getHeader("Referrer-Policy") == null) r.addHeader("Referrer-Policy", "no-referrer")
        r.addHeader("X-Frame-Options", "DENY")
        r.addHeader("Cross-Origin-Resource-Policy", "same-origin")
        return r
    }
}

// Súbory, náhľady a EXIF pre LAN server: ETag, Cache-Control: private, Range (aj video), cache
// náhľadov podľa id+mtime a obmedzený počet súbežných dekódovaní (pamäť telefónu).
// server-rychlost: cache v ServerThumbCache (pamäť malé/veľké + disk), dekódovanie cez ServerDecoder
// (fronta s limitom, 503 + Retry-After, zahodenie zrušených požiadaviek).
class ServerMedia(private val appContext: Context) {
    companion object {
        const val REDUCED_DEFAULT = 2048
        const val REDUCED_MIN = 256
        const val REDUCED_MAX = 4096
        private const val SMALL_QUALITY = 80
        private const val IMMUTABLE = "private, max-age=604800, immutable"
        private const val REVALIDATE = "private, no-cache"
    }

    val cache = ServerThumbCache(appContext)
    private val decoder = ServerDecoder()
    private val resolver get() = appContext.contentResolver

    // originál (Range, ETag) alebo na požiadanie zmenšený JPEG (q=kvalita, max=dlhá hrana).
    // dl=1 -> príloha; onFullDownload sa zavolá po odoslaní CELÉHO originálu („✓ v PC")
    fun file(session: IHTTPSession, item: MediaServer.Item, onFullDownload: () -> Unit): Response {
        val dl = Http.param(session, "dl") == "1"
        val q = Http.param(session, "q")?.toIntOrNull()
        if (q != null && item.mime.startsWith("image/")) return reduced(session, item, q, dl)
        val pfd = OriginalMedia.openFd(resolver, item.uri) ?: return Http.error(Status.NOT_FOUND, "open failed")
        // skutočná veľkosť súboru (MediaStore SIZE môže byť zastaraná a pevná dĺžka by potom nesedela)
        val size = pfd.statSize.takeIf { it >= 0 } ?: item.size
        val etag = ETags.of("f", item.id, item.mtime, size)
        val h = session.headers
        if (ETags.matches(h["if-none-match"], etag)) {
            pfd.close()
            return Http.notModified(etag)
        }
        val stream: InputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val resp = when (val range = HttpRange.parse(h["range"], size, h["if-range"], etag)) {
            is HttpRange.Result.Partial -> {
                skipFully(stream, range.start)
                NanoHTTPD.newFixedLengthResponse(Status.PARTIAL_CONTENT, item.mime, stream, range.length).also {
                    it.addHeader("Content-Range", HttpRange.contentRange(range, size))
                }
            }
            HttpRange.Result.Unsatisfiable -> {
                stream.close()
                Http.error(Status.RANGE_NOT_SATISFIABLE, "range").also {
                    it.addHeader("Content-Range", HttpRange.unsatisfiedRange(size))
                }
            }
            HttpRange.Result.Full -> {
                val body = if (dl) CompletionStream(stream, size, onFullDownload) else stream
                NanoHTTPD.newFixedLengthResponse(Status.OK, item.mime, body, size)
            }
        }
        resp.addHeader("ETag", etag)
        resp.addHeader("Cache-Control", REVALIDATE)
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("Content-Disposition", ContentDisposition.of(if (dl) "attachment" else "inline", item.name))
        return resp
    }

    private fun skipFully(stream: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = stream.skip(left)
            if (s <= 0) throw java.io.IOException("skip failed")
            left -= s
        }
    }

    private fun reduced(session: IHTTPSession, item: MediaServer.Item, q: Int, dl: Boolean): Response {
        val max = (Http.param(session, "max")?.toIntOrNull() ?: REDUCED_DEFAULT).coerceIn(REDUCED_MIN, REDUCED_MAX)
        val etag = ETags.of("r", item.id, item.mtime, item.size, q, max)
        if (ETags.matches(session.headers["if-none-match"], etag)) return Http.notModified(etag)
        val key = "r:${item.id}:${item.mtime}:${item.size}:$max:q$q"
        val bytes = cache.reduced(key, max) ?: run {
            val out = decoder.decode(session, max) { ImageDecode.sampled(resolver, item.uri, max) }
            if (out is ServerDecoder.Result.Fail) return out.response
            decoder.jpeg((out as ServerDecoder.Result.Ok).bmp, q).also { cache.putReduced(key, max, it) }
        }
        val r = Http.bytes("image/jpeg", bytes)
        r.addHeader("ETag", etag)
        r.addHeader("Cache-Control", REVALIDATE)
        val name = ContentDisposition.jpegName(item.name)
        r.addHeader("Content-Disposition", ContentDisposition.of(if (dl) "attachment" else "inline", name))
        return r
    }

    // náhľad: s=64..1024 (mriežka), max=64..8192 (porovnávač 2560, HEIC/DNG v plnom rozlíšení); v=mtime -> dlhá cache
    fun thumb(session: IHTTPSession, item: MediaServer.Item): Response {
        val px = thumbPx(session)
        val etag = ETags.of("t", item.id, item.mtime, item.size, px)
        if (ETags.matches(session.headers["if-none-match"], etag)) return Http.notModified(etag)
        val key = "${item.id}:${item.mtime}:${item.size}:$px"
        val bytes = cache.thumb(key, px) ?: run {
            val out = renderThumb(session, item, px)
            if (out is ServerDecoder.Result.Fail) return out.response
            val bmp = (out as ServerDecoder.Result.Ok).bmp
            decoder.jpeg(bmp, ThumbSize.quality(px)).also { cache.putThumb(key, px, it) }
        }
        val r = Http.bytes("image/jpeg", bytes)
        r.addHeader("ETag", etag)
        r.addHeader("Cache-Control", if (Http.param(session, "v") == item.mtime.toString()) IMMUTABLE else REVALIDATE)
        return r
    }

    fun binThumb(session: IHTTPSession, file: File, key: String): Response {
        val px = thumbPx(session)
        val etag = ETags.of("k", key, file.lastModified(), px)
        if (ETags.matches(session.headers["if-none-match"], etag)) return Http.notModified(etag)
        val out = decoder.decode(session, px) { ImageDecode.sampledFile(file, px) }
        if (out is ServerDecoder.Result.Fail) return out.response
        val r = Http.bytes("image/jpeg", decoder.jpeg((out as ServerDecoder.Result.Ok).bmp, SMALL_QUALITY))
        r.addHeader("ETag", etag)
        r.addHeader("Cache-Control", REVALIDATE)
        return r
    }

    private fun thumbPx(session: IHTTPSession): Int = ThumbSize.px(Http.param(session, "max"), Http.param(session, "s"))

    private fun renderThumb(session: IHTTPSession, item: MediaServer.Item, px: Int): ServerDecoder.Result {
        val video = item.mime.startsWith("video/")
        // Mriežka so zväčšenými miniatúrami (s= nad 512 px, web pri Ctrl + koliesku): najprv systémový náhľad
        // (MediaProvider, mimo pamäte appky) v malej fronte. Inak by stránka plná miniatúr zahltila veľkú frontu
        // (2 + 6 miest) a väčšina z nich by dostala 503 busy. Až keď systém náhľad nedá, dekóduje sa ako doteraz.
        if (!video && px > ServerThumbCache.SMALL_MAX && Http.param(session, "max") == null) {
            // systémový náhľad môže byť menší než žiadané px; ak má aspoň polovicu, stačí (bunka ho orezáva)
            val sys = decoder.decode(session, px, small = true) {
                ImageDecode.systemThumb(resolver, item.uri, px)?.let { b ->
                    if (maxOf(b.width, b.height) * 2 >= px) {
                        ImageDecode.scaleToMax(b, px)
                    } else {
                        b.recycle()
                        null
                    }
                }
            }
            if (!ServerDecoder.isDecodeMiss(sys)) return sys
        }
        return decoder.decode(session, px) {
            val small = px <= ServerThumbCache.SMALL_MAX
            val sys = if (small || video) ImageDecode.systemThumb(resolver, item.uri, px) else null
            when {
                sys != null -> ImageDecode.scaleToMax(sys, px)
                video -> ImageDecode.videoFrame(appContext, item.uri, px)
                else -> ImageDecode.sampled(resolver, item.uri, px)
            }
        }
    }

    fun stats(): JSONObject = cache.json().put("decode", decoder.json())
}

// Cache náhľadov (server-rychlost): oddelené pamäťové cache pre malé (mriežka, ≤ 512 px) a veľké
// náhľady a disková cache (cacheDir/pc_thumbs, LRU ~300 MB) pre náhľady ≥ 1024 px a zmenšené JPEG.
class ServerThumbCache(appContext: Context) {
    companion object {
        const val SMALL_MAX = ThumbSize.SMALL
        const val DISK_MIN_PX = 1024
        const val DISK_DIR = "pc_thumbs"
        private const val SMALL_CACHE_BYTES = 16L * 1024 * 1024
        private const val LARGE_CACHE_BYTES = 32L * 1024 * 1024
        private const val DISK_CACHE_BYTES = 300L * 1024 * 1024
    }

    private val smallMem = ByteLru(SMALL_CACHE_BYTES)
    private val largeMem = ByteLru(LARGE_CACHE_BYTES)
    private val disk = ThumbDiskCache(File(appContext.cacheDir, DISK_DIR), DISK_CACHE_BYTES)
    private val memHits = AtomicLong(0)
    private val memMisses = AtomicLong(0)

    // pri štarte servera (na pozadí): zmaže nedopísané súbory a oreže diskovú cache na limit
    fun init() = disk.init()

    // pamäťové cache sa pri zastavení servera vyprázdnia, disková ostáva
    fun clear() {
        smallMem.clear()
        largeMem.clear()
    }

    private fun memOf(px: Int) = if (px <= SMALL_MAX) smallMem else largeMem

    // pamäť, potom disk (len veľké); null = treba dekódovať
    fun thumb(key: String, px: Int): ByteArray? {
        memOf(px).get(key)?.let {
            memHits.incrementAndGet()
            RequestTiming.current()?.cache("mem")
            return it
        }
        memMisses.incrementAndGet()
        val b = if (px >= DISK_MIN_PX) disk.get("t:$key") else null
        RequestTiming.current()?.cache(if (b != null) "disk" else "miss")
        if (b != null) memOf(px).put(key, b)
        return b
    }

    fun putThumb(key: String, px: Int, bytes: ByteArray) {
        memOf(px).put(key, bytes)
        if (px >= DISK_MIN_PX) disk.put("t:$key", bytes)
    }

    // zmenšené JPEG (/api/file?q=) len z disku — sú veľké a pamäť patrí náhľadom
    fun reduced(key: String, max: Int): ByteArray? {
        val b = if (max >= DISK_MIN_PX) disk.get(key) else null
        RequestTiming.current()?.cache(if (b != null) "disk" else "miss")
        return b
    }

    fun putReduced(key: String, max: Int, bytes: ByteArray) {
        if (max >= DISK_MIN_PX) disk.put(key, bytes)
    }

    fun json(): JSONObject = JSONObject()
        .put(
            "mem",
            JSONObject().put("hits", memHits.get()).put("misses", memMisses.get())
                .put("smallBytes", smallMem.size()).put("largeBytes", largeMem.size()),
        )
        .put(
            "disk",
            JSONObject().put("hits", disk.hits.get()).put("misses", disk.misses.get())
                .put("files", disk.files()).put("bytes", disk.bytes()).put("maxBytes", DISK_CACHE_BYTES),
        )
}

// Dekódovanie náhľadov (server-rychlost): veľké (stovky MB pamäte) najviac po dvoch a 6 čakajúcich
// (inak 503 + Retry-After), malé po štyroch; plné rozlíšenie nad 4096 px obsadí obe veľké miesta (beží
// samo); požiadavka, ktorej klient zavrel spojenie, sa nedekóduje.
// Časy idú do Server-Timing (q = čakanie vo fronte, dec = dekódovanie, jpg = kompresia).
class ServerDecoder {
    companion object {
        private const val LARGE_PARALLEL = 2
        private const val LARGE_WAITING = 6
        private const val SMALL_PARALLEL = 4
        private const val SMALL_WAITING = 64
        private const val RETRY_AFTER_S = "1"
        private const val NS_PER_MS = 1_000_000.0

        // dekóder nevrátil obrázok (nie fronta ani zrušenie) – volajúci môže skúsiť inú cestu
        // (decode() pri null vracia 404 „decode failed“)
        fun isDecodeMiss(r: Result): Boolean = r is Result.Fail && r.response.status == Status.NOT_FOUND
    }

    sealed class Result {
        class Ok(val bmp: Bitmap) : Result()

        class Fail(val response: Response) : Result()
    }

    private val largeQueue = DecodeQueue(LARGE_PARALLEL, LARGE_WAITING)
    private val smallQueue = DecodeQueue(SMALL_PARALLEL, SMALL_WAITING)

    // small = true: lacný systémový náhľad aj pre väčšie px (beží v MediaProvider, nie v pamäti appky)
    fun decode(session: IHTTPSession, px: Int, small: Boolean = false, block: () -> Bitmap?): Result {
        val queue = if (px > ServerThumbCache.SMALL_MAX && !small) largeQueue else smallQueue
        val t0 = System.nanoTime()
        val outcome = queue.run({ ClientConn.gone(session) }, exclusive = px > ThumbSize.EXCLUSIVE) {
            RequestTiming.current()?.add("q", (System.nanoTime() - t0) / NS_PER_MS)
            RequestTiming.measure("dec") { block() }
        }
        return when (outcome) {
            is DecodeQueue.Outcome.Done -> outcome.value?.let { Result.Ok(it) }
                ?: Result.Fail(Http.error(Status.NOT_FOUND, "decode failed"))
            DecodeQueue.Outcome.Rejected -> Result.Fail(
                Http.error(Status.SERVICE_UNAVAILABLE, "busy").also { it.addHeader("Retry-After", RETRY_AFTER_S) },
            )
            DecodeQueue.Outcome.Cancelled -> Result.Fail(
                Http.error(Status.SERVICE_UNAVAILABLE, "client_gone").also { it.closeConnection(true) },
            )
        }
    }

    // JPEG bajty (bitmapu recykluje)
    fun jpeg(bmp: Bitmap, q: Int): ByteArray = RequestTiming.measure("jpg") { ImageDecode.jpeg(bmp, q) }

    fun json(): JSONObject = JSONObject()
        .put("large", queueJson(largeQueue, LARGE_PARALLEL, LARGE_WAITING))
        .put("small", queueJson(smallQueue, SMALL_PARALLEL, SMALL_WAITING))

    private fun queueJson(q: DecodeQueue, parallel: Int, maxWaiting: Int) = JSONObject()
        .put("running", q.running).put("waiting", q.waiting).put("parallel", parallel).put("maxWaiting", maxWaiting)
        .put("done", q.completed.get()).put("rejected", q.rejected.get()).put("cancelled", q.cancelled.get())
}

// EXIF pre porovnávač na webe (/api/meta): fotoaparát, objektív, ohnisko, clona, čas, ISO, blesk,
// rozlíšenie, formát, GPS áno/nie, softvér. Súbor sa otvorí raz cez deskriptor (ExifInterface potrebuje
// posúvanie pri HEIC). Čísla surové (formátuje web), chýbajúce polia sa vynechajú.
object MediaMeta {
    fun of(resolver: ContentResolver, item: MediaServer.Item): JSONObject {
        val o = JSONObject()
            .put("id", item.id)
            .put("format", formatOf(item.mime, item.name))
            .put("w", item.w)
            .put("h", item.h)
            .put("orient", item.orient)
            .put("size", item.size)
        if (item.dur > 0) o.put("dur", item.dur)
        if (!item.mime.startsWith("image/")) return o
        try {
            OriginalMedia.openFd(resolver, item.uri)?.use { pfd -> exifInto(ExifInterface(pfd.fileDescriptor), o) }
        } catch (ignored: Throwable) {
        }
        return o
    }

    private fun exifInto(e: ExifInterface, o: JSONObject) {
        o.putOpt("make", e.getAttribute(ExifInterface.TAG_MAKE)?.trim())
        o.putOpt("model", e.getAttribute(ExifInterface.TAG_MODEL)?.trim())
        o.putOpt("lens", e.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim())
        o.putOpt("software", e.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim())
        o.putOpt("taken", e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0).takeIf { it > 0 }?.let { o.put("focal", it) }
        e.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 }
            ?.let { o.put("focal35", it) }
        e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0).takeIf { it > 0 }?.let { o.put("fnum", it) }
        e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0).takeIf { it > 0 }?.let { o.put("exp", it) }
        e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.let { o.put("iso", it) }
        if (e.getAttribute(ExifInterface.TAG_FLASH) != null) {
            o.put("flash", e.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1 == 1)
        }
        o.put("gps", e.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null)
        val ew = e.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
        val eh = e.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
        if (o.optInt("w") <= 0 && ew > 0 && eh > 0) o.put("w", ew).put("h", eh)
    }

    private fun formatOf(mime: String, name: String): String {
        val sub = mime.substringAfter('/', "").substringBefore(';').lowercase()
        return when {
            sub == "jpeg" -> "JPEG"
            sub.startsWith("x-") || sub.isEmpty() || sub == "*" -> name.substringAfterLast('.', "?").uppercase()
            else -> sub.uppercase()
        }
    }
}
