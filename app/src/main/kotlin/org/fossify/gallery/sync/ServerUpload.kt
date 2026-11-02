package org.fossify.gallery.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.StatFs
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

// POST/PUT /api/upload: nahratie súboru z PC do mobilu (Pictures/Galéria resp. Movies/Galéria).
// PUT so surovým telom ide rovno do MediaStore (odporúčané pre PC program), multipart NanoHTTPD najprv
// celý zapíše do dočasného súboru. server-rychlost: nová položka sa do indexu dostane cieleným dotazom
// (onSaved -> MediaServer.indexOne), odpoveď nesie aj jej id.
class ServerUpload(private val appContext: Context, private val onSaved: (id: Long, isVideo: Boolean) -> Unit) {
    companion object {
        private const val SAVE_SUBDIR = "Galéria"

        // strop na jeden nahrávaný súbor a rezerva voľného miesta — upload nesmie zaplniť telefón
        private const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024
        private const val FREE_SPACE_RESERVE = 200L * 1024 * 1024
        private const val FREE_SPACE_CHECK_EVERY = 64L * 1024 * 1024
        private const val COPY_BUFFER = 64 * 1024
    }

    fun handle(session: IHTTPSession): Response {
        val ctype = session.headers["content-type"].orEmpty().lowercase()
        return if (ctype.startsWith("multipart/form-data")) multipart(session) else raw(session)
    }

    // multipart/form-data -> parseBody spooluje časť do dočasného súboru
    private fun multipart(session: IHTTPSession): Response {
        val declared = session.headers["content-length"]?.toLongOrNull()
            ?: return Http.error(Status.LENGTH_REQUIRED, "missing Content-Length")
        // parseBody najprv celé telo zapíše do dočasného súboru, potom sa kopíruje do MediaStore
        reject(declared, copies = 2)?.let { return it }
        val files = HashMap<String, String>()
        session.parseBody(files)
        val firstPart = files.entries.firstOrNull()
            ?: return Http.error(Status.BAD_REQUEST, "no file part")
        val name = session.parameters[firstPart.key]?.firstOrNull()
            ?: ("upload_" + System.currentTimeMillis())
        val mime = guessMime(name)
        val tmp = File(firstPart.value)
        val saved = saveMediaScoped(appContext, name, mime, SAVE_SUBDIR) { out ->
            tmp.inputStream().use { it.copyTo(out, COPY_BUFFER) }
        } ?: return Http.error(Status.INTERNAL_ERROR, "save failed")
        return uploaded(saved, mime)
    }

    // surové bajty v tele + X-Filename hlavička (názov je URL-kódovaný)
    private fun raw(session: IHTTPSession): Response {
        val rawName = session.headers["x-filename"]
            ?: return Http.error(Status.BAD_REQUEST, "missing X-Filename")
        val name = try {
            URLDecoder.decode(rawName, "UTF-8")
        } catch (ignored: IllegalArgumentException) {
            rawName
        }
        val mime = session.headers["x-mime"] ?: guessMime(name)
        val lenHdr = session.headers["content-length"]?.toLongOrNull() ?: -1L
        reject(lenHdr, copies = 1)?.let { return it }
        val body = session.inputStream
        val saved = saveMediaScoped(appContext, name, mime, SAVE_SUBDIR) { out ->
            if (lenHdr < 0) copyUnknown(body, out) else copyExactly(body, out, lenHdr)
        } ?: return Http.error(Status.INTERNAL_ERROR, "save failed")
        return uploaded(saved, mime)
    }

    private fun uploaded(saved: Pair<String, String>, mime: String): Response {
        val id = try {
            ContentUris.parseId(Uri.parse(saved.second))
        } catch (ignored: RuntimeException) {
            -1L
        }
        if (id >= 0) onSaved(id, mime.startsWith("video/"))
        val o = JSONObject().put("saved", saved.first).put("uri", saved.second)
            .put("id", if (id >= 0) id else JSONObject.NULL)
        return Http.json(Status.OK, o)
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
    private fun reject(length: Long, copies: Int): Response? {
        if (length > MAX_UPLOAD_BYTES) {
            return Http.error(Status.PAYLOAD_TOO_LARGE, "file too large")
        }
        val free = freeBytes()
        if (free >= 0 && free < length.coerceAtLeast(0) * copies + FREE_SPACE_RESERVE) {
            return Http.error(Status.PAYLOAD_TOO_LARGE, "not enough free space")
        }
        return null
    }

    // voľné miesto na primárnom úložisku (tam zapisuje saveMediaScoped); -1 = nedá sa zistiť
    private fun freeBytes(): Long = try {
        StatFs((appContext.getExternalFilesDir(null) ?: appContext.filesDir).path).availableBytes
    } catch (ignored: RuntimeException) {
        -1L
    }

    // bez Content-Length najviac MAX_UPLOAD_BYTES a priebežne kontroluj voľné miesto — inak by klient
    // vedel zaplniť úložisko; výnimka = saveMediaScoped položku zmaže
    private fun copyUnknown(input: InputStream, out: OutputStream) {
        val buf = ByteArray(COPY_BUFFER)
        var copied = 0L
        var nextCheck = FREE_SPACE_CHECK_EVERY
        var n = input.read(buf)
        while (n >= 0) {
            copied += n
            if (copied > MAX_UPLOAD_BYTES) throw IOException("upload too large")
            if (copied >= nextCheck) {
                nextCheck += FREE_SPACE_CHECK_EVERY
                if (freeBytes() in 0L until FREE_SPACE_RESERVE) throw IOException("not enough free space")
            }
            out.write(buf, 0, n)
            n = input.read(buf)
        }
    }

    private fun copyExactly(input: InputStream, out: OutputStream, len: Long) {
        val buf = ByteArray(COPY_BUFFER)
        var remaining = len
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }
}
