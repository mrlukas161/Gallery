package org.fossify.gallery.sync

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import org.fossify.gallery.faces.IndexScope

// Telefónny LAN media server (NanoHTTPD). Sprístupní LEN MediaStore Images+Videos, adresované cez
// nepriehľadné MediaStore _ID (žiadne cesty od klienta -> ../ traversal je nemožný). Chránené PIN tokenom.
// onTooManyBadPins: volá sa (raz, z vlákna požiadavky), keď sa server zamkol po hádaní PIN-u.
class MediaServer(
    private val appContext: Context,
    private val pin: String,
    port: Int = DEFAULT_PORT,
    private val onTooManyBadPins: () -> Unit = {},
) : NanoHTTPD(null, port) {

    companion object {
        const val DEFAULT_PORT = 8089
        const val SERVER_NAME = "Galéria+"
        const val SERVER_VERSION = "1"
        private const val SAVE_SUBDIR = "Galéria"

        // po toľkých RÔZNYCH zlých tokenoch sa server zamkne (šanca uhádnuť 6-miestny PIN ~ 20 : 900 000)
        private const val MAX_BAD_PINS = 20

        // strop na jeden nahrávaný súbor a rezerva voľného miesta — upload nesmie zaplniť telefón
        private const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024
        private const val FREE_SPACE_RESERVE = 200L * 1024 * 1024
        private const val FREE_SPACE_CHECK_EVERY = 64L * 1024 * 1024
    }

    data class Item(
        val id: Long,
        val name: String,
        val relpath: String,
        val size: Long,
        val mtime: Long,
        val taken: Long,
        val bucket: String,
        val mime: String,
        val uri: Uri,
        val path: String,
    )

    @Volatile
    private var index: Map<Long, Item> = emptyMap()

    // Ochrana PIN-u pred hádaním na LAN: rátajú sa RÔZNE zlé tokeny (otvorená stará karta v PC
    // prehliadači posiela stále ten istý starý token a server by inak zbytočne zamkla); požiadavky
    // úplne bez tokenu (favicon…) nie sú hádanie. Po zamknutí sa odmietne všetko a služba server
    // zastaví — nový štart = nový PIN.
    private val badPins = HashSet<String>()

    @Volatile
    private var locked = false

    private val resolver: ContentResolver get() = appContext.contentResolver

    fun itemCount(): Int = index.size

    override fun start(timeout: Int, daemon: Boolean) {
        rebuildIndex()
        super.start(timeout, daemon)
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (!authorized(session)) {
                return if (locked) {
                    json(Status.FORBIDDEN, """{"error":"locked"}""")
                } else {
                    json(Status.FORBIDDEN, """{"error":"forbidden"}""")
                }
            }
            when (session.uri) {
                "/api/ping" -> handlePing()
                "/api/list" -> handleList()
                "/api/file" -> handleFile(session)
                "/api/thumb" -> handleThumb(session)
                "/api/upload" -> handleUpload(session)
                "/api/groups" -> handleGroups()
                "/api/state" -> handleState()
                "/api/mark" -> handleMark(session)
                "/api/synced" -> handleSynced(session)
                "/api/clearmarks" -> handleClearMarks()
                "/" -> handleRoot(session)
                else -> json(Status.NOT_FOUND, """{"error":"not_found"}""")
            }
        } catch (e: Exception) {
            json(Status.INTERNAL_ERROR, """{"error":"${(e.message ?: "error").replace("\"", "'")}"}""")
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        if (locked) return false
        val headerTok = session.headers["x-auth-token"]
        val queryTok = session.parameters["token"]?.firstOrNull()
        val tok = headerTok ?: queryTok ?: return false
        if (constantTimeEquals(tok, pin)) return true
        val lockNow = synchronized(badPins) {
            if (locked) {
                false
            } else {
                badPins.add(tok.take(64))
                if (badPins.size >= MAX_BAD_PINS) {
                    locked = true
                    true
                } else {
                    false
                }
            }
        }
        if (lockNow) {
            try {
                onTooManyBadPins()
            } catch (ignored: Throwable) {
            }
        }
        return false
    }

    private fun handlePing(): Response {
        val o = JSONObject()
            .put("name", SERVER_NAME)
            .put("version", SERVER_VERSION)
            .put("count", index.size)
        return json(Status.OK, o.toString())
    }

    private fun handleList(): Response {
        val arr = JSONArray()
        for (it in index.values) {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("relpath", it.relpath)
                    .put("name", it.name)
                    .put("size", it.size)
                    .put("mtime", it.mtime)
                    .put("taken", it.taken)
                    .put("bucket", it.bucket)
                    .put("mime", it.mime)
            )
        }
        return json(Status.OK, arr.toString())
    }

    private fun handleFile(session: IHTTPSession): Response {
        val idStr = session.parameters["id"]?.firstOrNull()
            ?: return json(Status.BAD_REQUEST, """{"error":"missing id"}""")
        val id = idStr.toLongOrNull()
            ?: return json(Status.BAD_REQUEST, """{"error":"bad id"}""")
        // KĽÚČOVÉ: servovať možno len id prítomné v MediaStore indexe.
        val item = index[id]
            ?: return json(Status.NOT_FOUND, """{"error":"unknown id"}""")
        // voliteľne zmenšená/komprimovaná verzia (q=kvalita %, max=dlhá hrana px) — len obrázky
        val q = session.parameters["q"]?.firstOrNull()?.toIntOrNull()
        val max = session.parameters["max"]?.firstOrNull()?.toIntOrNull()
        if (q != null && item.mime.startsWith("image/")) {
            val bmp = decodeSampled(item.uri, (max ?: 2048).coerceIn(256, 8192))
            if (bmp != null) return jpegResponse(bmp, q)
        }
        val stream: InputStream = resolver.openInputStream(item.uri)
            ?: return json(Status.NOT_FOUND, """{"error":"open failed"}""")
        val resp = NanoHTTPD.newFixedLengthResponse(Status.OK, item.mime, stream, item.size)
        resp.addHeader("Content-Disposition", "inline; filename=\"${item.name}\"")
        resp.addHeader("Accept-Ranges", "none")
        return resp
    }

    private fun handleUpload(session: IHTTPSession): Response {
        if (session.method != Method.POST && session.method != Method.PUT) {
            return json(Status.METHOD_NOT_ALLOWED, """{"error":"use POST"}""")
        }
        val ctype = session.headers["content-type"].orEmpty().lowercase()
        return if (ctype.startsWith("multipart/form-data")) {
            uploadMultipart(session)
        } else {
            uploadRaw(session)
        }
    }

    // Hlavná cesta (PC nástroj): multipart/form-data -> parseBody spooluje časť do dočasného súboru.
    private fun uploadMultipart(session: IHTTPSession): Response {
        val declared = session.headers["content-length"]?.toLongOrNull()
            ?: return json(Status.LENGTH_REQUIRED, """{"error":"missing Content-Length"}""")
        // parseBody najprv celé telo zapíše do dočasného súboru, potom sa kopíruje do MediaStore
        rejectUpload(declared, copies = 2)?.let { return it }
        val files = HashMap<String, String>()
        session.parseBody(files)
        val firstPart = files.entries.firstOrNull()
            ?: return json(Status.BAD_REQUEST, """{"error":"no file part"}""")
        val tmpPath = firstPart.value
        val name = session.parameters[firstPart.key]?.firstOrNull()
            ?: ("upload_" + System.currentTimeMillis())
        val mime = guessMime(name)
        val tmp = File(tmpPath)
        val saved = saveToMediaStore(name, mime, tmp.length()) { out ->
            tmp.inputStream().use { it.copyTo(out) }
        } ?: return json(Status.INTERNAL_ERROR, """{"error":"save failed"}""")
        rebuildIndex()
        return json(Status.OK, JSONObject().put("saved", saved.first).put("uri", saved.second).toString())
    }

    // Fallback: surové bajty v tele + X-Filename hlavička (názov je URL-kódovaný).
    private fun uploadRaw(session: IHTTPSession): Response {
        val rawName = session.headers["x-filename"]
            ?: return json(Status.BAD_REQUEST, """{"error":"missing X-Filename"}""")
        val name = try {
            URLDecoder.decode(rawName, "UTF-8")
        } catch (e: Exception) {
            rawName
        }
        val mime = session.headers["x-mime"] ?: guessMime(name)
        val lenHdr = session.headers["content-length"]?.toLongOrNull() ?: -1L
        rejectUpload(lenHdr, copies = 1)?.let { return it }
        val body = session.inputStream
        val saved = saveToMediaStore(name, mime, lenHdr) { out ->
            copyExactly(body, out, lenHdr)
        } ?: return json(Status.INTERNAL_ERROR, """{"error":"save failed"}""")
        rebuildIndex()
        return json(Status.OK, JSONObject().put("saved", saved.first).put("uri", saved.second).toString())
    }

    // ---------- SYNC MANAŽÉR: skupiny, značky na zmazanie, evidencia stiahnutých ----------
    // Perzistentné v prefs "galeria_pcsync": marked_delete = StringSet ciest; synced_json = {cesta: čas_ms}.
    // Kľúč = cesta (stabilná aj po reštarte servera/telefónu); klientovi sa mapuje na aktuálne id.

    private val syncPrefs get() = appContext.getSharedPreferences(SyncStore.PREFS, Context.MODE_PRIVATE)

    // burst série (rovnaký priečinok, odstup ≤3 s) + duplikáty podľa pHash (ak je index)
    private fun handleGroups(): Response {
        val o = JSONObject()
        // burst: len obrázky, zoradené podľa bucket+taken
        val burst = JSONArray()
        val images = index.values.filter { it.mime.startsWith("image/") }.sortedWith(compareBy({ it.bucket }, { it.taken }))
        var cur = ArrayList<Long>()
        var prevBucket = ""
        var prevTaken = 0L
        for (it in images) {
            if (cur.isNotEmpty() && it.bucket == prevBucket && it.taken > 0 && prevTaken > 0 && it.taken - prevTaken <= 3000) {
                cur.add(it.id)
            } else {
                if (cur.size >= 2) burst.put(JSONArray(cur))
                cur = arrayListOf(it.id)
            }
            prevBucket = it.bucket
            prevTaken = it.taken
        }
        if (cur.size >= 2) burst.put(JSONArray(cur))
        o.put("burst", burst)
        // duplikáty: pHash skupiny (cesty -> id cez reverzný index)
        val similar = JSONArray()
        try {
            val byPath = index.values.associateBy { it.path }
            val hashes = org.fossify.gallery.faces.PhashDatabase.getInstance(appContext).PhashDao().getAllHashes()
            val groups = org.fossify.gallery.faces.PhashGrouper.groupBySimilarity(hashes)
            for (g in groups) {
                val ids = g.mapNotNull { byPath[it]?.id }
                if (ids.size >= 2) similar.put(JSONArray(ids))
            }
        } catch (ignored: Throwable) {
        }
        o.put("similar", similar)
        return json(Status.OK, o.toString())
    }

    // aktuálny stav značiek pre klienta: marked=[id...], synced={id: čas_ms}
    private fun handleState(): Response {
        val marked = SyncStore.markedPaths(syncPrefs)
        val synced = SyncStore.syncedMap(syncPrefs)
        val mArr = JSONArray()
        val sObj = JSONObject()
        for (it in index.values) {
            if (it.path in marked) mArr.put(it.id)
            synced[it.path]?.let { ts -> sObj.put(it.id.toString(), ts) }
        }
        return json(Status.OK, JSONObject().put("marked", mArr).put("synced", sObj).toString())
    }

    private fun handleMark(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return json(Status.BAD_REQUEST, """{"error":"bad id"}""")
        val item = index[id] ?: return json(Status.NOT_FOUND, """{"error":"unknown id"}""")
        if (item.path.isEmpty()) return json(Status.NOT_FOUND, """{"error":"no path"}""")
        val del = session.parameters["del"]?.firstOrNull() != "0"
        SyncStore.setMarked(syncPrefs, item.path, del)
        return json(Status.OK, """{"ok":true,"marked":${SyncStore.markedPaths(syncPrefs).size}}""")
    }

    private fun handleSynced(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return json(Status.BAD_REQUEST, """{"error":"bad id"}""")
        val item = index[id] ?: return json(Status.NOT_FOUND, """{"error":"unknown id"}""")
        if (item.path.isEmpty()) return json(Status.NOT_FOUND, """{"error":"no path"}""")
        SyncStore.addSynced(syncPrefs, item.path)
        return json(Status.OK, """{"ok":true}""")
    }

    private fun handleClearMarks(): Response {
        SyncStore.clearMarked(syncPrefs)
        return json(Status.OK, """{"ok":true}""")
    }

    private fun handleRoot(session: IHTTPSession): Response {
        return try {
            val html = appContext.assets.open("gallery.html").use { it.readBytes() }
            NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", ByteArrayInputStream(html), html.size.toLong())
        } catch (e: Throwable) {
            json(Status.INTERNAL_ERROR, """{"error":"gallery.html chýba"}""")
        }
    }

    private fun handleThumb(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return json(Status.BAD_REQUEST, """{"error":"bad id"}""")
        val item = index[id] ?: return json(Status.NOT_FOUND, """{"error":"unknown id"}""")
        val s = session.parameters["s"]?.firstOrNull()?.toIntOrNull()?.coerceIn(64, 1024) ?: 300
        val bmp = (if (item.mime.startsWith("video/")) videoFrame(item.uri, s) else decodeSampled(item.uri, s))
            ?: return json(Status.NOT_FOUND, """{"error":"decode failed"}""")
        return jpegResponse(bmp, 80)
    }

    // dekóduj obrázok z uri zmenšený (~maxPx dlhá hrana), pamäťovo šetrne cez inSampleSize
    private fun decodeSampled(uri: Uri, maxPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (w / sample > maxPx * 2 || h / sample > maxPx * 2) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            scaleToMax(bmp, maxPx)
        } catch (e: Throwable) {
            null
        }
    }

    private fun videoFrame(uri: Uri, maxPx: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(appContext, uri)
            val frame = mmr.frameAtTime ?: return null
            scaleToMax(frame, maxPx)
        } catch (e: Throwable) {
            null
        } finally {
            try {
                mmr.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun scaleToMax(bmp: Bitmap, maxPx: Int): Bitmap {
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge <= maxPx || longEdge <= 0) return bmp
        val sc = maxPx.toFloat() / longEdge
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun jpegResponse(bmp: Bitmap, quality: Int): Response {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 100), bos)
        bmp.recycle()
        val bytes = bos.toByteArray()
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
        r.addHeader("Cache-Control", "max-age=86400")
        return r
    }

    private fun rebuildIndex() {
        val map = LinkedHashMap<Long, Item>()
        // zamknuté a vylúčené priečinky PC nevidí (rozhodnutie Lukáša 20.10.2026)
        val scope = IndexScope(appContext)
        collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, map, scope)
        collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, map, scope)
        index = map
    }

    private fun collect(base: Uri, isVideo: Boolean, into: MutableMap<Long, Item>, scope: IndexScope) {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
        )
        resolver.query(base, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iNm = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSz = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMt = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iTk = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val iBk = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iMm = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iDt = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val path = (if (iDt >= 0) c.getString(iDt) else null) ?: ""
                // bez cesty sa nedá overiť priečinok -> radšej nesprístupniť (súkromie má prednosť)
                if (path.isEmpty() || !scope.allows(path)) continue
                val name = c.getString(iNm) ?: "$id"
                val bucket = c.getString(iBk) ?: "Camera"
                val mime = c.getString(iMm) ?: if (isVideo) "video/*" else "image/*"
                val uri = Uri.withAppendedPath(base, id.toString())
                val mtime = c.getLong(iMt)
                val taken = if (iTk >= 0) c.getLong(iTk).takeIf { it > 0 } ?: (mtime * 1000) else mtime * 1000
                into[id] = Item(
                    id = id, name = name, relpath = "$bucket/$name",
                    size = c.getLong(iSz), mtime = mtime, taken = taken,
                    bucket = bucket, mime = mime, uri = uri,
                    path = path,
                )
            }
        }
    }

    private fun json(status: Status, body: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        else -> "application/octet-stream"
    }

    // null = upload smie pokračovať, inak odpoveď s dôvodom; copies = koľkokrát dáta zaberú miesto
    private fun rejectUpload(length: Long, copies: Int): Response? {
        if (length > MAX_UPLOAD_BYTES) {
            return json(Status.PAYLOAD_TOO_LARGE, """{"error":"file too large"}""")
        }
        val free = freeBytes()
        if (free >= 0 && free < length.coerceAtLeast(0) * copies + FREE_SPACE_RESERVE) {
            return json(Status.PAYLOAD_TOO_LARGE, """{"error":"not enough free space"}""")
        }
        return null
    }

    // voľné miesto na primárnom úložisku (tam zapisuje saveMediaScoped); -1 = nedá sa zistiť
    private fun freeBytes(): Long = try {
        StatFs((appContext.getExternalFilesDir(null) ?: appContext.filesDir).path).availableBytes
    } catch (e: Throwable) {
        -1L
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, len: Long) {
        if (len < 0) {
            // bez Content-Length najviac MAX_UPLOAD_BYTES a priebežne kontroluj voľné miesto —
            // inak by klient vedel zaplniť úložisko; výnimka = saveMediaScoped položku zmaže
            val buf = ByteArray(64 * 1024)
            var copied = 0L
            var nextCheck = FREE_SPACE_CHECK_EVERY
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                copied += n
                if (copied > MAX_UPLOAD_BYTES) throw java.io.IOException("upload too large")
                if (copied >= nextCheck) {
                    nextCheck += FREE_SPACE_CHECK_EVERY
                    val free = freeBytes()
                    if (free in 0L until FREE_SPACE_RESERVE) throw java.io.IOException("not enough free space")
                }
                out.write(buf, 0, n)
            }
            return
        }
        val buf = ByteArray(64 * 1024)
        var remaining = len
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var r = 0
        for (i in x.indices) r = r or (x[i].toInt() xor y[i].toInt())
        return r == 0
    }

    private fun saveToMediaStore(
        name: String, mime: String, sizeHint: Long,
        writer: (java.io.OutputStream) -> Unit,
    ): Pair<String, String>? = saveMediaScoped(appContext, name, mime, SAVE_SUBDIR, writer)
}
