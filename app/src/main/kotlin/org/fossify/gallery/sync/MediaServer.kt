package org.fossify.gallery.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.request.Method
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.File
import java.io.InputStream
import java.net.URLDecoder

// Telefónny LAN media server (NanoHTTPD). Sprístupní LEN MediaStore Images+Videos, adresované cez
// nepriehľadné MediaStore _ID (žiadne cesty od klienta -> ../ traversal je nemožný). Chránené PIN tokenom.
class MediaServer(
    private val appContext: Context,
    private val pin: String,
    port: Int = DEFAULT_PORT,
) : NanoHTTPD(null, port) {

    companion object {
        const val DEFAULT_PORT = 8089
        const val SERVER_NAME = "Galéria+"
        const val SERVER_VERSION = "1"
        private const val SAVE_SUBDIR = "Galéria"
    }

    data class Item(
        val id: Long,
        val name: String,
        val relpath: String,
        val size: Long,
        val mtime: Long,
        val bucket: String,
        val mime: String,
        val uri: Uri,
    )

    @Volatile
    private var index: Map<Long, Item> = emptyMap()

    private val resolver: ContentResolver get() = appContext.contentResolver

    fun itemCount(): Int = index.size

    override fun start(timeout: Int, daemon: Boolean) {
        rebuildIndex()
        super.start(timeout, daemon)
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (!authorized(session)) {
                return json(Status.FORBIDDEN, """{"error":"forbidden"}""")
            }
            when (session.uri) {
                "/api/ping" -> handlePing()
                "/api/list" -> handleList()
                "/api/file" -> handleFile(session)
                "/api/upload" -> handleUpload(session)
                "/" -> handleRoot(session)
                else -> json(Status.NOT_FOUND, """{"error":"not_found"}""")
            }
        } catch (e: Exception) {
            json(Status.INTERNAL_ERROR, """{"error":"${(e.message ?: "error").replace("\"", "'")}"}""")
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val headerTok = session.headers["x-auth-token"]
        val queryTok = session.parameters["token"]?.firstOrNull()
        val tok = headerTok ?: queryTok ?: return false
        return constantTimeEquals(tok, pin)
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
        val stream: InputStream = resolver.openInputStream(item.uri)
            ?: return json(Status.NOT_FOUND, """{"error":"open failed"}""")
        val resp = newFixedLengthResponse(Status.OK, item.mime, stream, item.size)
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
        val body = session.inputStream
        val saved = saveToMediaStore(name, mime, lenHdr) { out ->
            copyExactly(body, out, lenHdr)
        } ?: return json(Status.INTERNAL_ERROR, """{"error":"save failed"}""")
        rebuildIndex()
        return json(Status.OK, JSONObject().put("saved", saved.first).put("uri", saved.second).toString())
    }

    private fun handleRoot(session: IHTTPSession): Response {
        val token = session.parameters["token"]?.firstOrNull().orEmpty()
        val sb = StringBuilder()
        sb.append("<!doctype html><meta charset=utf-8>")
        sb.append("<meta name=viewport content='width=device-width,initial-scale=1'>")
        sb.append("<title>$SERVER_NAME</title>")
        sb.append("<style>body{font-family:sans-serif;margin:1rem}a{display:block;padding:.3rem 0}</style>")
        sb.append("<h2>$SERVER_NAME — ${index.size} položiek</h2>")
        for (it in index.values.take(2000)) {
            sb.append("<a href='/api/file?id=${it.id}&token=${escape(token)}'>${escape(it.relpath)} (${it.size} B)</a>")
        }
        return newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", sb.toString())
    }

    private fun rebuildIndex() {
        val map = LinkedHashMap<Long, Item>()
        collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, map)
        collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, map)
        index = map
    }

    private fun collect(base: Uri, isVideo: Boolean, into: MutableMap<Long, Item>) {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        resolver.query(base, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iNm = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSz = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMt = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iBk = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iMm = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val name = c.getString(iNm) ?: "$id"
                val bucket = c.getString(iBk) ?: "Camera"
                val mime = c.getString(iMm) ?: if (isVideo) "video/*" else "image/*"
                val uri = Uri.withAppendedPath(base, id.toString())
                into[id] = Item(
                    id = id, name = name, relpath = "$bucket/$name",
                    size = c.getLong(iSz), mtime = c.getLong(iMt),
                    bucket = bucket, mime = mime, uri = uri,
                )
            }
        }
    }

    private fun json(status: Status, body: String): Response {
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", body)
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

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, len: Long) {
        if (len < 0) {
            input.copyTo(out)
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
