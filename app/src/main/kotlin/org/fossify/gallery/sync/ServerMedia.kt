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
import java.util.concurrent.Semaphore

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

    // hlavičky pre každú odpoveď: bez MIME hádania, bez Referer (token v adrese), bez vkladania do cudzích stránok
    fun secure(r: Response): Response {
        r.addHeader("X-Content-Type-Options", "nosniff")
        r.addHeader("Referrer-Policy", "no-referrer")
        r.addHeader("X-Frame-Options", "DENY")
        r.addHeader("Cross-Origin-Resource-Policy", "same-origin")
        return r
    }
}

// Súbory, náhľady a EXIF pre LAN server: ETag, Cache-Control: private, Range (aj video), cache
// malých náhľadov podľa id+mtime a obmedzený počet súbežných veľkých dekódovaní (pamäť telefónu).
class ServerMedia(private val appContext: Context) {
    companion object {
        const val DEFAULT_THUMB = 300
        const val MAX_S = 1024
        const val MIN_PX = 64
        const val MAX_THUMB_PX = 4096
        const val SMALL_MAX = 512
        const val REDUCED_DEFAULT = 2048
        const val REDUCED_MIN = 256
        private const val SMALL_QUALITY = 80
        private const val LARGE_QUALITY = 85
        private const val CACHE_BYTES = 32L * 1024 * 1024
        private const val LARGE_PARALLEL = 2
        private const val SMALL_PARALLEL = 4
        private const val IMMUTABLE = "private, max-age=604800, immutable"
        private const val REVALIDATE = "private, no-cache"
    }

    private val cache = ByteLru(CACHE_BYTES)
    private val largeGate = Semaphore(LARGE_PARALLEL)
    private val smallGate = Semaphore(SMALL_PARALLEL)
    private val resolver get() = appContext.contentResolver

    fun clearCache() = cache.clear()

    private fun param(session: IHTTPSession, name: String): String? = session.parameters[name]?.firstOrNull()

    // originál (Range, ETag) alebo na požiadanie zmenšený JPEG (q=kvalita, max=dlhá hrana).
    // dl=1 -> príloha; onFullDownload sa zavolá po odoslaní CELÉHO originálu („✓ v PC")
    fun file(session: IHTTPSession, item: MediaServer.Item, onFullDownload: () -> Unit): Response {
        val dl = param(session, "dl") == "1"
        val q = param(session, "q")?.toIntOrNull()
        if (q != null && item.mime.startsWith("image/")) return reduced(session, item, q, dl)
        val pfd = resolver.openFileDescriptor(item.uri, "r") ?: return Http.error(Status.NOT_FOUND, "open failed")
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
        val max = (param(session, "max")?.toIntOrNull() ?: REDUCED_DEFAULT).coerceIn(REDUCED_MIN, MAX_THUMB_PX)
        val etag = ETags.of("r", item.id, item.mtime, item.size, q, max)
        if (ETags.matches(session.headers["if-none-match"], etag)) return Http.notModified(etag)
        val bytes = gated(max) { ImageDecode.sampled(resolver, item.uri, max) }?.let { ImageDecode.jpeg(it, q) }
            ?: return Http.error(Status.NOT_FOUND, "decode failed")
        val r = Http.bytes("image/jpeg", bytes)
        r.addHeader("ETag", etag)
        r.addHeader("Cache-Control", REVALIDATE)
        val name = ContentDisposition.jpegName(item.name)
        r.addHeader("Content-Disposition", ContentDisposition.of(if (dl) "attachment" else "inline", name))
        return r
    }

    // náhľad: s=64..1024 (mriežka), max=64..4096 (porovnávač, napr. 2560); v=mtime -> dlhá cache
    fun thumb(session: IHTTPSession, item: MediaServer.Item): Response {
        val px = thumbPx(session)
        val etag = ETags.of("t", item.id, item.mtime, item.size, px)
        if (ETags.matches(session.headers["if-none-match"], etag)) return Http.notModified(etag)
        val key = "${item.id}:${item.mtime}:${item.size}:$px"
        val bytes = cache.get(key) ?: render(item, px)?.also { cache.put(key, it) }
            ?: return Http.error(Status.NOT_FOUND, "decode failed")
        val r = Http.bytes("image/jpeg", bytes)
        r.addHeader("ETag", etag)
        r.addHeader("Cache-Control", if (param(session, "v") == item.mtime.toString()) IMMUTABLE else REVALIDATE)
        return r
    }

    fun binThumb(session: IHTTPSession, file: File, key: String): Response {
        val px = thumbPx(session)
        val etag = ETags.of("k", key, file.lastModified(), px)
        if (ETags.matches(session.headers["if-none-match"], etag)) return Http.notModified(etag)
        val bmp = gated(px) { ImageDecode.sampledFile(file, px) }
            ?: return Http.error(Status.NOT_FOUND, "decode failed")
        val r = Http.bytes("image/jpeg", ImageDecode.jpeg(bmp, SMALL_QUALITY))
        r.addHeader("ETag", etag)
        r.addHeader("Cache-Control", REVALIDATE)
        return r
    }

    private fun thumbPx(session: IHTTPSession): Int {
        val max = param(session, "max")?.toIntOrNull()?.coerceIn(MIN_PX, MAX_THUMB_PX)
        return max ?: param(session, "s")?.toIntOrNull()?.coerceIn(MIN_PX, MAX_S) ?: DEFAULT_THUMB
    }

    private fun render(item: MediaServer.Item, px: Int): ByteArray? {
        val video = item.mime.startsWith("video/")
        val bmp: Bitmap? = gated(px) {
            val sys = if (px <= SMALL_MAX || video) ImageDecode.systemThumb(resolver, item.uri, px) else null
            when {
                sys != null -> ImageDecode.scaleToMax(sys, px)
                video -> ImageDecode.videoFrame(appContext, item.uri, px)
                else -> ImageDecode.sampled(resolver, item.uri, px)
            }
        }
        return bmp?.let { ImageDecode.jpeg(it, if (px <= SMALL_MAX) SMALL_QUALITY else LARGE_QUALITY) }
    }

    // veľké dekódovania (stovky MB pamäte) najviac po dvoch, malé po štyroch
    private fun <T> gated(px: Int, block: () -> T): T {
        val gate = if (px > SMALL_MAX) largeGate else smallGate
        gate.acquire()
        try {
            return block()
        } finally {
            gate.release()
        }
    }
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
            resolver.openFileDescriptor(item.uri, "r")?.use { pfd -> exifInto(ExifInterface(pfd.fileDescriptor), o) }
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
