package org.fossify.gallery.sync

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
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
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.extensions.config
import org.fossify.gallery.faces.IndexScope
import org.fossify.gallery.helpers.EXCLUDED_FOLDERS
import org.fossify.gallery.helpers.INCLUDED_FOLDERS

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
        const val SERVER_VERSION = "2"
        private const val SAVE_SUBDIR = "Galéria"

        // po toľkých RÔZNYCH zlých tokenoch sa server zamkne (šanca uhádnuť 6-miestny PIN ~ 20 : 900 000)
        private const val MAX_BAD_PINS = 20

        // strop na jeden nahrávaný súbor a rezerva voľného miesta — upload nesmie zaplniť telefón
        private const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024
        private const val FREE_SPACE_RESERVE = 200L * 1024 * 1024
        private const val FREE_SPACE_CHECK_EVERY = 64L * 1024 * 1024

        // strop na počet fotiek v jednej požiadavke na zmazanie (PC posiela dávky po desiatkach)
        private const val MAX_DELETE_IDS = 1000

        // strop na telo /api/delete (1000 id sa zmestí s rezervou) — parseBody inak načíta celé telo do pamäte
        private const val MAX_DELETE_BODY_BYTES = 64L * 1024
    }

    // jedno mazanie naraz: NanoHTTPD obsluhuje každé spojenie vo vlastnom vlákne a dve súbežné
    // požiadavky s rovnakým id by kopírovali do toho istého súboru v Koši
    private val deleteLock = Any()

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

    // generácia zoznamu: PC sa pýta /api/ping každé 2 s a pri zmene si vypýta nové položky
    @Volatile
    private var generation = 0L

    @Volatile
    private var newestMtime = 0L

    // Ochrana PIN-u pred hádaním na LAN: rátajú sa RÔZNE zlé tokeny (otvorená stará karta v PC
    // prehliadači posiela stále ten istý starý token a server by inak zbytočne zamkla); požiadavky
    // úplne bez tokenu (favicon…) nie sú hádanie. Po zamknutí sa odmietne všetko a služba server
    // zastaví — nový štart = nový PIN.
    private val badPins = HashSet<String>()

    @Volatile
    private var locked = false

    private val resolver: ContentResolver get() = appContext.contentResolver

    fun itemCount(): Int = index.size

    // volá HttpSyncService po zmene v MediaStore (nová / zmazaná fotka) — mimo hlavného vlákna
    fun refreshIndex() = rebuildIndex()

    // Zamknutie/odomknutie priečinka PIN-om a vylúčené/zahrnuté priečinky MediaStore nezmenia, takže
    // ContentObserver služby index neprepočíta a PC by videl fotky práve zamknutého priečinka.
    // SharedPreferences drží listener len slabo -> silná referencia v poli (inak by ho GC zrušil).
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // key == null = prefs sa vyčistili (clear) -> radšej prepočítať
        if (key == null || IndexDiff.isScopeKey(key)) {
            ensureBackgroundThread {
                try {
                    rebuildIndex()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    override fun start(timeout: Int, daemon: Boolean) {
        // najprv listener, potom index: zmena nastavení počas prvého prepočtu sa tak nestratí
        appContext.getSharedPrefs().registerOnSharedPreferenceChangeListener(prefsListener)
        try {
            rebuildIndex()
            super.start(timeout, daemon)
        } catch (e: Throwable) {
            appContext.getSharedPrefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
            throw e
        }
    }

    override fun stop() {
        appContext.getSharedPrefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.stop()
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
                "/api/list" -> handleList(session)
                "/api/file" -> handleFile(session)
                "/api/thumb" -> handleThumb(session)
                "/api/upload" -> handleUpload(session)
                "/api/delete" -> handleDelete(session)
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
            .put("gen", generation)
            .put("newest", newestMtime)
            .put("allFiles", PcDeleter.canDelete())
            .put("recycleBin", appContext.config.useRecycleBin)
        return json(Status.OK, o.toString())
    }

    private fun handleList(session: IHTTPSession): Response {
        val since = session.parameters["since"]?.firstOrNull()?.toLongOrNull()
        val arr = JSONArray()
        for (it in IndexDiff.since(index.values, since) { i -> i.mtime }) {
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

    // mazanie z PC porovnávača priamo v mobile (do Koša podľa nastavenia) — len po potvrdení na PC
    private fun handleDelete(session: IHTTPSession): Response {
        if (session.method != Method.POST) return json(Status.METHOD_NOT_ALLOWED, """{"error":"use POST"}""")
        // veľkosť tela over PRED parseBody (ten číta celé telo bez limitu); telo ostáva neprečítané,
        // preto spojenie zavrieť (Connection: close) — inak by zvyšok tela skazil ďalšiu požiadavku na keep-alive
        when (IndexDiff.deleteBodyReject(session.headers["content-length"], MAX_DELETE_BODY_BYTES)) {
            411 -> return json(Status.LENGTH_REQUIRED, """{"error":"missing Content-Length"}""").also { it.closeConnection(true) }
            413 -> return json(Status.PAYLOAD_TOO_LARGE, """{"error":"body too large"}""").also { it.closeConnection(true) }
        }
        // telo prečítať vždy, aj pri 409: neprečítané by na keep-alive spojení skazilo ďalšiu požiadavku (400)
        val files = HashMap<String, String>()
        session.parseBody(files)
        if (!PcDeleter.canDelete()) return json(Status.CONFLICT, """{"error":"no_all_files_access"}""")
        val body = files["postData"].orEmpty()
        // Throwable: hlboko vnorený JSON hodí StackOverflowError, ktorý by inak zhodil vlákno servera aj appku
        val arr = try {
            JSONObject(body).getJSONArray("ids")
        } catch (e: Throwable) {
            return json(Status.BAD_REQUEST, """{"error":"bad body"}""")
        }
        if (arr.length() > MAX_DELETE_IDS) return json(Status.BAD_REQUEST, """{"error":"too many ids"}""")
        // jedno mazanie naraz (rovnaké id v dvoch súbežných požiadavkách)
        synchronized(deleteLock) {
            val failed = JSONObject()
            val items = ArrayList<Item>()
            // duplicitné id by sa mazalo dvakrát (druhýkrát by skončilo ako „missing“ vo failed)
            for (id in IndexDiff.uniqueIds((0 until arr.length()).map { arr.optLong(it, -1L) })) {
                val item = index[id]
                if (item == null) failed.put(id.toString(), "unknown id") else items.add(item)
            }
            val r = PcDeleter.delete(appContext, items)
            r.failed.forEach { (id, msg) -> failed.put(id.toString(), msg) }
            val gonePaths = items.filter { it.id in r.deleted }.map { it.path }
            if (gonePaths.isNotEmpty()) SyncStore.removeMarked(syncPrefs, gonePaths)
            rebuildIndex()
            return json(Status.OK, JSONObject().put("deleted", JSONArray(r.deleted)).put("failed", failed).put("recycleBin", r.toRecycleBin).toString())
        }
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
            // najprv zmenši (lacnejšie), potom otoč podľa EXIF — BitmapFactory orientáciu ignoruje
            // a zvislé fotky by prišli do PC otočené na bok
            applyExifOrientation(scaleToMax(bmp, maxPx), uri)
        } catch (e: Throwable) {
            null
        }
    }

    // EXIF orientácia (1–8) z uri; pri akejkoľvek chybe čítania 1 = bez otočenia
    private fun readExifOrientation(uri: Uri): Int = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Throwable) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyExifOrientation(bmp: Bitmap, uri: Uri): Bitmap {
        val t = ExifOrient.of(readExifOrientation(uri))
        if (t.degrees == 0 && !t.mirror) return bmp
        return try {
            val m = Matrix()
            m.setRotate(t.degrees.toFloat())
            if (t.mirror) m.postScale(-1f, 1f)
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            out
        } catch (e: Throwable) {
            bmp // pri nedostatku pamäte radšej neotočený náhľad než žiadny
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

    @Synchronized
    private fun rebuildIndex() {
        val map = LinkedHashMap<Long, Item>()
        // zamknuté a vylúčené priečinky PC nevidí (rozhodnutie Lukáša 20.10.2026)
        val scope = IndexScope(appContext)
        // provider dočasne nedostupný (query vrátilo null) -> ponechať starý index a generáciu,
        // inak by PC uvidel prázdnu knižnicu a ponúkol všetko ako nové
        if (!collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, map, scope)) return
        if (!collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, map, scope)) return
        val old = index
        index = map
        newestMtime = map.values.maxOfOrNull { it.mtime } ?: 0L
        if (IndexDiff.changed(old.mapValues { it.value.mtime to it.value.size }, map.mapValues { it.value.mtime to it.value.size })) generation++
    }

    // true = dotaz prebehol (aj prázdny výsledok), false = provider nevrátil kurzor
    private fun collect(base: Uri, isVideo: Boolean, into: MutableMap<Long, Item>, scope: IndexScope): Boolean {
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
        val cursor = resolver.query(base, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC") ?: return false
        cursor.use { c ->
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
        return true
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

// EXIF orientácia (1–8) -> (uhol v smere hodín, potom zrkadlenie vodorovne). Čistá logika, testovateľná na JVM.
// Poradie „najprv otoč, potom zrkadli" je rovnaké ako v Glide (TransformationUtils), teda ako zobrazenie
// v appke: 5 (transpose) = otoč 90° + zrkadli, 7 (transverse) = otoč 270° + zrkadli.
object ExifOrient {
    data class T(val degrees: Int, val mirror: Boolean)

    fun of(orientation: Int): T = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> T(0, true)
        ExifInterface.ORIENTATION_ROTATE_180 -> T(180, false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> T(180, true)
        ExifInterface.ORIENTATION_TRANSPOSE -> T(90, true)
        ExifInterface.ORIENTATION_ROTATE_90 -> T(90, false)
        ExifInterface.ORIENTATION_TRANSVERSE -> T(270, true)
        ExifInterface.ORIENTATION_ROTATE_270 -> T(270, false)
        else -> T(0, false) // 1 = bez otočenia, 0 a neznáme hodnoty tiež
    }
}

// čistá logika živého zoznamu (bez Androidu -> testovateľná)
object IndexDiff {
    // kľúč = id, hodnota = (mtime, size); zmena = pribudla/ubudla/zmenila sa fotka
    fun changed(old: Map<Long, Pair<Long, Long>>, new: Map<Long, Pair<Long, Long>>): Boolean =
        old.size != new.size || new.any { (id, v) -> old[id] != v }

    fun <T> since(items: Collection<T>, since: Long?, mtime: (T) -> Long): List<T> =
        if (since == null) items.toList() else items.filter { mtime(it) >= since }

    // duplicity preč, poradie zachovať
    fun uniqueIds(ids: List<Long>): List<Long> = LinkedHashSet(ids).toList()

    // rozhodnutie o tele /api/delete podľa hlavičky Content-Length: null = v poriadku (telo sa smie čítať),
    // 411 = dĺžka chýba / je neplatná, 413 = telo je väčšie než strop
    fun deleteBodyReject(contentLength: String?, max: Long): Int? {
        val len = contentLength?.trim()?.toLongOrNull()
        return when {
            len == null || len < 0 -> 411
            len > max -> 413
            else -> null
        }
    }

    // stačí voľné miesto na kópiu súboru + rezervu? free < 0 = nezistené -> nebrániť
    fun hasRoomForCopy(free: Long, size: Long, reserve: Long): Boolean = free < 0 || free >= size.coerceAtLeast(0) + reserve

    // kľúče nastavení, po ktorých sa zmení rozsah indexu (IndexScope): vylúčené/zahrnuté priečinky a
    // ochrana priečinka PIN-om/heslom (commons BaseConfig: protected_folder_hash_<cesta>, protected_folder_type_<cesta>)
    fun isScopeKey(key: String): Boolean =
        key == EXCLUDED_FOLDERS || key == INCLUDED_FOLDERS || key.startsWith("protected_folder_", ignoreCase = true)
}
