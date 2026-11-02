package org.fossify.gallery.sync

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.ClientHandler
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.gallery.extensions.config
import org.fossify.gallery.faces.IndexScope
import org.fossify.gallery.helpers.ServerActivity
import org.fossify.gallery.helpers.EXCLUDED_FOLDERS
import org.fossify.gallery.helpers.INCLUDED_FOLDERS

// Telefónny LAN media server (NanoHTTPD). Sprístupní LEN MediaStore Images+Videos, adresované cez
// nepriehľadné MediaStore _ID (žiadne cesty od klienta -> ../ traversal je nemožný). Chránené PIN-om:
// PC program posiela PIN v hlavičke X-Auth-Token (alebo ?token=), prehliadač ho cez /api/login vymení
// za session cookie (HttpOnly, SameSite=Strict). Zmeny len cez POST z rovnakého pôvodu (Origin).
// Popis API pre PC: docs/api/server-pre-pc.md.
// onTooManyBadPins: volá sa (raz, z vlákna požiadavky), keď sa server zamkol po hádaní PIN-u.
// server-rychlost: epoch dodá služba (po reštarte služby ten istý, HttpSyncService), resumed = reštart
// služby (START_STICKY) -> prihlásenia prehliadačov sa obnovia z prefs. Prenos: TunedSocketFactory
// (TCP_NODELAY, bufferovaný výstup) a LimitedRunner (limit spojení), viď ServerTransport.kt.
class MediaServer(
    private val appContext: Context,
    private val pin: String,
    port: Int = DEFAULT_PORT,
    private val epoch: String = newEpoch(),
    resumed: Boolean = false,
    private val onTooManyBadPins: () -> Unit = {},
) : NanoHTTPD(null, port) {

    companion object {
        const val DEFAULT_PORT = 8089
        const val SERVER_NAME = "Galéria+"
        const val SERVER_VERSION = "3"

        // čo server vie (PC program a web sa podľa toho rozhodnú, ktoré API použiť)
        val FEATURES = listOf(
            "session", "sinceGen", "etag", "range", "groupsV2", "groupState", "meta", "markBatch",
            "syncedBatch", "deleteDryRun", "bin", "restore", "thumbMax", "dlMark", "quality",
            // server-rychlost: /api/state?since=, /api/stats
            "stateSince", "stats",
        )
        private const val SAVE_SUBDIR = "Galéria"

        // po toľkých RÔZNYCH zlých tokenoch sa server zamkne (šanca uhádnuť 6-miestny PIN ~ 20 : 900 000)
        private const val MAX_BAD_PINS = 20
        private const val BAD_PIN_KEEP = 64

        // strop na jeden nahrávaný súbor a rezerva voľného miesta — upload nesmie zaplniť telefón
        private const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024
        private const val FREE_SPACE_RESERVE = 200L * 1024 * 1024
        private const val FREE_SPACE_CHECK_EVERY = 64L * 1024 * 1024
        private const val COPY_BUFFER = 64 * 1024

        // strop na telo JSON požiadaviek — parseBody inak načíta celé telo do pamäte
        private const val MAX_BODY_BYTES = 64L * 1024

        private const val MAX_META_IDS = 8
        private const val NONCE_BYTES = 16
        private const val EPOCH_BYTES = 6
        private const val ERROR_MAX = 200
        private const val MS = 1000L

        // server-rychlost: obnova indexu po zmenách v MediaStore (debounce), dávka „✓ v PC“, generácia
        private const val REBUILD_QUIET_MS = 2_500L
        private const val REBUILD_MAX_MS = 10_000L
        private const val SYNCED_FLUSH_MS = 3_000L
        private const val KEY_LIST_GEN = "server_list_gen"
        private const val KEY_SESSIONS = "server_sessions"
        private const val GEN_NEW_RUN_GAP = 100L
        private const val NS_PER_MS = 1_000_000L
        private const val DESCRIBE_MAX = 160
        private const val FP_SIZE = 31L
        private const val FP_TAKEN = 17L
        private const val FP_W = 7L
        private const val FP_H = 3L

        // náhodné id behu servera (klient podľa neho spozná nový beh a načíta zoznam celý)
        fun newEpoch(): String = randomToken(EPOCH_BYTES)

        private fun randomToken(bytes: Int): String {
            val b = ByteArray(bytes)
            SecureRandom().nextBytes(b)
            return Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        }
    }

    // w/h = rozmery uložených pixelov (pred otočením), orient = EXIF/MediaStore otočenie v stupňoch,
    // taken = dátum snímania alebo náhradne čas úpravy súboru; takenReal = či je dátum skutočný
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
        val takenReal: Boolean = true,
        val w: Int = 0,
        val h: Int = 0,
        val orient: Int = 0,
        val dur: Long = 0,
    )

    // index a generácia sa menia naraz (inak by /api/list?sinceGen videl novú generáciu so starým indexom)
    private class Snapshot(val index: Map<Long, Item>, val gen: Long, val newest: Long)

    private val syncPrefs get() = appContext.getSharedPreferences(SyncStore.PREFS, Context.MODE_PRIVATE)

    // generácia pokračuje od poslednej uloženej + rezerva: epoch je po reštarte služby rovnaký, takže
    // klient nesmie vidieť tú istú generáciu s iným obsahom
    @Volatile
    private var snap = Snapshot(emptyMap(), syncPrefs.getLong(KEY_LIST_GEN, 0L) + GEN_NEW_RUN_GAP, 0L)
    private val index: Map<Long, Item> get() = snap.index
    private val genLog = GenLog()

    // Ochrana PIN-u pred hádaním na LAN: rátajú sa RÔZNE zlé tokeny (otvorená stará karta v PC
    // prehliadači posiela stále ten istý starý token a server by inak zbytočne zamkla); požiadavky
    // úplne bez tokenu (favicon…) nie sú hádanie. Po zamknutí sa odmietne všetko a služba server
    // zastaví — nový štart = nový PIN.
    private val badPins = HashSet<String>()

    @Volatile
    private var locked = false

    private val sessions = Sessions()
    private val media = ServerMedia(appContext)

    // server-rychlost: jedno vlákno na pozadí (obnova indexu, dávka „✓ v PC“, zámky, disková cache)
    private val bg: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "galeria-server-bg").apply { isDaemon = true }
    }
    private val stats = ServerStats()
    private val runner = LimitedRunner(ServerTransport.MAX_CONNECTIONS) { stats.rejectedConnections.incrementAndGet() }
    private val locks = ServerLocks(appContext, bg, { stats.inFlight.get() })
    private val rebuildDebounce = Debounce(REBUILD_QUIET_MS, REBUILD_MAX_MS)
    private val rebuildLock = Any()
    private var rebuildFuture: ScheduledFuture<*>? = null
    private val flushScheduled = AtomicBoolean(false)
    private val startedAt = System.currentTimeMillis()

    private val marks = ServerMarks(appContext) { snap.index }
    private val cleanup = ServerCleanup(
        appContext,
        { snap.let { it.index to it.gen } },
        onDeleted = { ids -> removeIds(ids) },
        requestRebuild = { requestRebuild() },
    )
    private val groups = ServerGroups(appContext, epoch) { snap.let { it.index to it.gen } }
    private val quality = ServerQuality(appContext) { snap.index }

    // cesty API -> obsluha (po prihlásení a kontrole zmien)
    private val routes: Map<String, (IHTTPSession) -> Response> = mapOf(
        "/api/ping" to { _ -> handlePing() },
        "/api/stats" to { s -> handleStats(s) },
        "/api/logout" to { s -> handleLogout(s) },
        "/api/list" to { s -> handleList(s) },
        "/api/file" to { s -> handleFile(s) },
        "/api/thumb" to { s -> handleThumb(s) },
        "/api/meta" to { s -> handleMeta(s) },
        "/api/upload" to { s -> handleUpload(s) },
        "/api/delete" to { s -> cleanup.delete(s) },
        "/api/groups" to { s -> groups.groups(s) },
        "/api/quality" to { s -> quality.handle(s) },
        "/api/groupstate" to { s -> marks.groupState(s) },
        "/api/state" to { s -> marks.state(s) },
        "/api/mark" to { s -> marks.mark(s) },
        "/api/synced" to { s -> marks.synced(s) },
        "/api/clearmarks" to { _ -> marks.clearMarks() },
        "/api/bin" to { _ -> cleanup.bin() },
        "/api/restore" to { s -> cleanup.restore(s) },
    )

    @Volatile
    private var html: String? = null

    private val resolver: ContentResolver get() = appContext.contentResolver

    init {
        setServerSocketFactory(TunedSocketFactory())
        setAsyncRunner(runner)
        if (resumed) sessions.restore(syncPrefs.getString(KEY_SESSIONS, null), System.currentTimeMillis())
    }

    fun itemCount(): Int = index.size

    // volá HttpSyncService po zmene v MediaStore (nová / zmazaná fotka); obnova ide na pozadí po
    // REBUILD_QUIET_MS ticha (najneskôr REBUILD_MAX_MS) — séria zmien = jedna prestavba
    fun refreshIndex() = requestRebuild()

    fun requestRebuild(immediate: Boolean = false) {
        val delay = if (immediate) 0L else rebuildDebounce.onEvent(System.currentTimeMillis())
        synchronized(rebuildLock) {
            rebuildFuture?.cancel(false)
            rebuildFuture = try {
                bg.schedule({ runRebuild() }, delay, TimeUnit.MILLISECONDS)
            } catch (ignored: RejectedExecutionException) {
                null
            }
        }
    }

    private fun runRebuild() {
        rebuildDebounce.onRun()
        try {
            rebuildIndex()
        } catch (ignored: RuntimeException) {
        }
    }

    // NanoHTTPD vytvára handler spojenia na prijímacom vlákne — LimitedRunner potrebuje jeho socket
    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler {
        val h = super.createClientHandler(finalAccept, inputStream)
        runner.register(h, finalAccept)
        return h
    }

    // Zamknutie/odomknutie priečinka PIN-om a vylúčené/zahrnuté priečinky MediaStore nezmenia, takže
    // ContentObserver služby index neprepočíta a PC by videl fotky práve zamknutého priečinka.
    // SharedPreferences drží listener len slabo -> silná referencia v poli (inak by ho GC zrušil).
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // key == null = prefs sa vyčistili (clear) -> radšej prepočítať
        if (key == null || IndexDiff.isScopeKey(key)) {
            cleanup.invalidateBin()
            requestRebuild(immediate = true)
        }
    }

    override fun start(timeout: Int, daemon: Boolean) {
        // najprv listener, potom index: zmena nastavení počas prvého prepočtu sa tak nestratí
        appContext.getSharedPrefs().registerOnSharedPreferenceChangeListener(prefsListener)
        try {
            SyncStore.startNewRun(syncPrefs)
            rebuildIndex()
            bg.execute { media.cache.init() }
            super.start(timeout, daemon)
        } catch (e: Throwable) {
            appContext.getSharedPrefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
            bg.shutdownNow()
            throw e
        }
    }

    override fun stop() {
        appContext.getSharedPrefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        try {
            super.stop()
        } finally {
            // „✓ v PC“ z poslednej dávky sa nesmie stratiť; zámky a čakajúce indexery pustiť hneď
            flushSynced()
            bg.shutdownNow()
            locks.release()
            ServerActivity.SHARED.reset()
            media.cache.clear()
            quality.stop()
        }
    }

    // Každá požiadavka: normálna priorita vlákna, počítadlá (ServerActivity pre spracovanie knižnice,
    // ServerStats pre /api/stats), zámky WakeLock/WifiLock a hlavička Server-Timing. Koniec požiadavky
    // = zatvorenie tela odpovede po odoslaní (FinishStream).
    override fun serve(session: IHTTPSession): Response {
        ServerTransport.normalPriority()
        val startNs = System.nanoTime()
        val uri = session.uri ?: "/"
        val work = ServerActivity.countsAsWork(uri)
        val via = ClientKind.of(session.headers["remote-addr"])
        // ping z otvorenej karty v PC (každé 2–4 s) zámky nedrží — inak by telefón nezaspal nikdy
        if (work) {
            ServerActivity.SHARED.begin()
            locks.onRequest()
        }
        stats.begin(via)
        val timing = RequestTiming.begin(startNs)
        val resp = try {
            Http.secure(route(session, uri))
        } catch (e: Throwable) {
            if (work) ServerActivity.SHARED.end()
            stats.abort()
            throw e
        } finally {
            RequestTiming.end()
        }
        val endNs = System.nanoTime()
        resp.addHeader("Server-Timing", timing.header(endNs))
        val handlerMs = (endNs - startNs) / NS_PER_MS
        val status = resp.status?.requestStatus ?: 0
        val path = describe(session, uri)
        val finish: (Long) -> Unit = { bytes ->
            if (work) ServerActivity.SHARED.end()
            val totalMs = (System.nanoTime() - startNs) / NS_PER_MS
            val entry = SlowLog.Entry(System.currentTimeMillis(), path, status, handlerMs, totalMs, bytes, via)
            stats.end(if (uri in routes || uri == "/") uri else LatencyStats.OTHER, entry)
        }
        val data = resp.data
        if (data == null) finish(0L) else resp.data = FinishStream(data, finish)
        return resp
    }

    private fun route(session: IHTTPSession, uri: String): Response = try {
        when {
            uri == "/" || uri == "/index.html" -> handleRoot()
            uri == "/favicon.ico" -> NanoHTTPD.newFixedLengthResponse(Status.NO_CONTENT, "text/plain", "")
            uri == "/api/login" -> handleLogin(session)
            !authorized(session) -> Http.error(Status.FORBIDDEN, if (locked) "locked" else "forbidden")
            else -> {
                checkMutation(session)
                routes[uri]?.invoke(session) ?: Http.error(Status.NOT_FOUND, "not_found")
            }
        }
    } catch (e: HttpError) {
        Http.error(e.status, e.code).also { if (e.close) it.closeConnection(true) }
    } catch (e: Exception) {
        Http.error(Status.INTERNAL_ERROR, (e.message ?: "error").take(ERROR_MAX))
    }

    // cesta s parametrami pre zoznam pomalých požiadaviek — bez PIN-u (token)
    private fun describe(session: IHTTPSession, uri: String): String {
        val q = session.parameters.orEmpty().filterKeys { it != "token" }.entries
            .joinToString("&") { (k, v) -> "$k=${v.firstOrNull().orEmpty()}" }
        return (if (q.isEmpty()) uri else "$uri?$q").take(DESCRIBE_MAX)
    }

    private fun handleStats(session: IHTTPSession): Response {
        val s = snap
        val o = ServerDiag.base(stats, runner.open, session.headers["remote-addr"])
            .put("epoch", epoch)
            .put("uptimeMs", System.currentTimeMillis() - startedAt)
            .put("index", ServerDiag.index(stats, s.index.size, s.gen))
            .put("thumbs", media.stats())
            .put("groups", groups.cacheStats())
            .put("synced", JSONObject().put("pending", SyncedStore.pendingSynced()))
            .put("processing", ServerDiag.processing())
            .put("device", ServerDiag.device(appContext))
            .put("wifi", ServerDiag.wifi(appContext) ?: JSONObject.NULL)
            .put("locks", locks.json())
        return Http.json(Status.OK, o)
    }

    // zmeny stavu: len POST (upload aj PUT) a z rovnakého pôvodu — cudzia stránka v prehliadači nič nezmení
    private fun checkMutation(session: IHTTPSession) {
        val uri = session.uri
        val write = when (uri) {
            "/api/mark", "/api/synced", "/api/clearmarks", "/api/delete", "/api/restore", "/api/upload",
            "/api/logout", "/api/quality",
            -> true
            "/api/groupstate" -> session.method != Method.GET && session.method != Method.HEAD
            else -> false
        }
        if (!write) return
        if (RequestGuard.legacyGetAllowed(uri, session.method == Method.GET, hasPinToken(session))) return
        val methodOk = session.method == Method.POST || (uri == "/api/upload" && session.method == Method.PUT)
        val err = when {
            !methodOk -> HttpError(Status.METHOD_NOT_ALLOWED, "use POST")
            !originOk(session) -> HttpError(Status.FORBIDDEN, "bad_origin")
            else -> null
        }
        if (err != null) throw err
    }

    private fun originOk(session: IHTTPSession): Boolean =
        RequestGuard.sameOrigin(session.headers["origin"], session.headers["host"], session.headers["sec-fetch-site"])

    private fun authorized(session: IHTTPSession): Boolean {
        if (locked) return false
        if (sessions.valid(RequestGuard.cookie(session.headers["cookie"]), System.currentTimeMillis())) return true
        val headerTok = session.headers["x-auth-token"]
        val queryTok = session.parameters["token"]?.firstOrNull()
        val tok = headerTok ?: queryTok ?: return false
        return checkPin(tok)
    }

    // PIN token priamo v požiadavke (PC program), bez započítania do ochrany pred hádaním —
    // overenie s počítaním už prebehlo v authorized()
    private fun hasPinToken(session: IHTTPSession): Boolean {
        val tok = session.headers["x-auth-token"] ?: session.parameters["token"]?.firstOrNull() ?: return false
        return constantTimeEquals(tok, pin)
    }

    // správny PIN = true; zlý sa zaráta do ochrany pred hádaním
    private fun checkPin(tok: String): Boolean {
        if (constantTimeEquals(tok, pin)) return true
        val lockNow = synchronized(badPins) {
            if (locked) {
                false
            } else {
                badPins.add(tok.take(BAD_PIN_KEEP))
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

    // prehliadač: PIN -> session cookie, PIN potom nemusí byť v adrese (história, kopírovanie odkazu)
    private fun handleLogin(session: IHTTPSession): Response {
        val err = when {
            session.method != Method.POST -> HttpError(Status.METHOD_NOT_ALLOWED, "use POST")
            !originOk(session) -> HttpError(Status.FORBIDDEN, "bad_origin")
            locked -> HttpError(Status.FORBIDDEN, "locked")
            else -> null
        }
        if (err != null) throw err
        val given = Http.readJson(session, MAX_BODY_BYTES).optString("pin", "").trim()
        if (given.isEmpty() || !checkPin(given)) {
            return Http.error(Status.FORBIDDEN, if (locked) "locked" else "bad_pin")
        }
        val token = sessions.create(System.currentTimeMillis())
        saveSessions()
        val r = Http.json(Status.OK, JSONObject().put("ok", true))
        r.addHeader("Set-Cookie", RequestGuard.sessionCookie(token))
        return r
    }

    private fun handleLogout(session: IHTTPSession): Response {
        sessions.remove(RequestGuard.cookie(session.headers["cookie"]))
        saveSessions()
        val r = Http.json(Status.OK, JSONObject().put("ok", true))
        r.addHeader("Set-Cookie", RequestGuard.clearCookie())
        return r
    }

    // prihlásenia prehliadačov prežijú reštart služby (START_STICKY); nový ručný štart ich zahodí
    private fun saveSessions() {
        syncPrefs.edit().putString(KEY_SESSIONS, sessions.export()).apply()
    }

    private fun param(session: IHTTPSession, name: String): String? = Http.param(session, name)

    private fun itemOf(session: IHTTPSession): Item {
        val id = param(session, "id")?.toLongOrNull() ?: throw HttpError(Status.BAD_REQUEST, "bad id")
        // KĽÚČOVÉ: servovať možno len id prítomné v MediaStore indexe (rozsah bez zamknutých priečinkov)
        return index[id] ?: throw HttpError(Status.NOT_FOUND, "unknown id")
    }

    private fun handlePing(): Response {
        val s = snap
        val o = JSONObject()
            .put("name", SERVER_NAME)
            .put("version", SERVER_VERSION)
            .put("epoch", epoch)
            .put("count", s.index.size)
            .put("gen", s.gen)
            .put("stateGen", SyncStore.stateGen(syncPrefs))
            .put("newest", s.newest)
            .put("allFiles", PcDeleter.canDelete())
            .put("recycleBin", appContext.config.useRecycleBin)
            .put("features", JSONArray(FEATURES))
        return Http.json(Status.OK, o)
    }

    // bez parametrov: celé pole položiek (pôvodný formát); ?since=mtime: len novšie (pôvodné);
    // ?sinceGen=N: {gen, epoch, full, upserts, removed} — len zmeny od generácie N
    private fun handleList(session: IHTTPSession): Response {
        val s = snap
        val sinceGen = param(session, "sinceGen")?.toLongOrNull()
        val since = param(session, "since")?.toLongOrNull()
        val stateGen = SyncStore.stateGen(syncPrefs)
        val etag = ETags.of("l", epoch, s.gen, stateGen, sinceGen ?: "a", since ?: "a")
        if (ETags.matches(session.headers["if-none-match"], etag)) return Http.notModified(etag)
        val marked = SyncStore.markedPaths(syncPrefs)
        val synced = SyncedStore.syncedMap(syncPrefs)
        val toJson = { list: Collection<Item> ->
            JSONArray().also { a -> list.forEach { a.put(itemJson(it, marked, synced)) } }
        }
        val body = if (sinceGen != null) {
            val delta = genLog.since(sinceGen, s.gen)
            val o = JSONObject().put("gen", s.gen).put("epoch", epoch).put("stateGen", stateGen)
            if (delta == null) {
                o.put("full", true).put("upserts", toJson(s.index.values)).put("removed", JSONArray())
            } else {
                o.put("full", false).put("upserts", toJson(delta.upserts.mapNotNull { s.index[it] }))
                    .put("removed", JSONArray(delta.removed.filter { it !in s.index }))
            }
            o.toString()
        } else {
            toJson(IndexDiff.since(s.index.values, since) { i -> i.mtime }).toString()
        }
        val r = Http.json(Status.OK, body)
        r.addHeader("ETag", etag)
        return r
    }

    private fun itemJson(it: Item, marked: Set<String>, synced: Map<String, Long>): JSONObject {
        val o = JSONObject()
            .put("id", it.id)
            .put("relpath", it.relpath)
            .put("name", it.name)
            .put("size", it.size)
            .put("mtime", it.mtime)
            .put("taken", it.taken)
            .put("takenReal", it.takenReal)
            .put("bucket", it.bucket)
            .put("mime", it.mime)
            .put("w", it.w)
            .put("h", it.h)
            .put("orient", it.orient)
            .put("marked", it.path in marked)
            .put("synced", synced[it.path] ?: 0L)
        if (it.dur > 0) o.put("dur", it.dur)
        return o
    }

    private fun handleFile(session: IHTTPSession): Response {
        val item = itemOf(session)
        return media.file(session, item) {
            // „✓ v PC" až po odoslaní celého originálu (prerušené sťahovanie sa nezapíše); do pamäti hneď,
            // do prefs dávkou o SYNCED_FLUSH_MS — posledné read() originálu nič nezdrží
            if (item.path.isNotEmpty()) markSynced(listOf(item.path))
        }
    }

    private fun markSynced(paths: List<String>) {
        SyncedStore.queueSynced(syncPrefs, paths)
        if (!flushScheduled.compareAndSet(false, true)) return
        try {
            bg.schedule({
                flushScheduled.set(false)
                flushSynced()
            }, SYNCED_FLUSH_MS, TimeUnit.MILLISECONDS)
        } catch (ignored: RejectedExecutionException) {
            flushScheduled.set(false)
        }
    }

    private fun flushSynced() {
        try {
            SyncedStore.flushSynced(syncPrefs)
        } catch (ignored: RuntimeException) {
        }
    }

    private fun handleThumb(session: IHTTPSession): Response {
        val binKey = param(session, "bin")
        if (binKey != null) {
            val bi = cleanup.binItems()[binKey] ?: return Http.error(Status.NOT_FOUND, "unknown key")
            return media.binThumb(session, bi.binFile, bi.key)
        }
        return media.thumb(session, itemOf(session))
    }

    private fun handleMeta(session: IHTTPSession): Response {
        val o = JSONObject()
        for (id in RequestGuard.parseIdList(param(session, "ids"), MAX_META_IDS)) {
            val item = index[id] ?: continue
            o.put(id.toString(), MediaMeta.of(resolver, item))
        }
        return Http.json(Status.OK, o)
    }

    private fun handleUpload(session: IHTTPSession): Response {
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
            ?: return Http.error(Status.LENGTH_REQUIRED, "missing Content-Length")
        // parseBody najprv celé telo zapíše do dočasného súboru, potom sa kopíruje do MediaStore
        rejectUpload(declared, copies = 2)?.let { return it }
        val files = HashMap<String, String>()
        session.parseBody(files)
        val firstPart = files.entries.firstOrNull()
            ?: return Http.error(Status.BAD_REQUEST, "no file part")
        val tmpPath = firstPart.value
        val name = session.parameters[firstPart.key]?.firstOrNull()
            ?: ("upload_" + System.currentTimeMillis())
        val mime = guessMime(name)
        val tmp = File(tmpPath)
        val saved = saveMediaScoped(appContext, name, mime, SAVE_SUBDIR) { out ->
            tmp.inputStream().use { it.copyTo(out, COPY_BUFFER) }
        } ?: return Http.error(Status.INTERNAL_ERROR, "save failed")
        return uploaded(saved, mime)
    }

    // Fallback: surové bajty v tele + X-Filename hlavička (názov je URL-kódovaný).
    private fun uploadRaw(session: IHTTPSession): Response {
        val rawName = session.headers["x-filename"]
            ?: return Http.error(Status.BAD_REQUEST, "missing X-Filename")
        val name = try {
            URLDecoder.decode(rawName, "UTF-8")
        } catch (e: Exception) {
            rawName
        }
        val mime = session.headers["x-mime"] ?: guessMime(name)
        val lenHdr = session.headers["content-length"]?.toLongOrNull() ?: -1L
        rejectUpload(lenHdr, copies = 1)?.let { return it }
        val body = session.inputStream
        val saved = saveMediaScoped(appContext, name, mime, SAVE_SUBDIR) { out ->
            copyExactly(body, out, lenHdr)
        } ?: return Http.error(Status.INTERNAL_ERROR, "save failed")
        return uploaded(saved, mime)
    }

    // server-rychlost: nová fotka do indexu cieleným dotazom na JEDNU položku (bez prestavby celej
    // knižnice); zvyšok (rozmery po skene) dorovná obnova po zmene v MediaStore. id = MediaStore _ID.
    private fun uploaded(saved: Pair<String, String>, mime: String): Response {
        val id = try {
            ContentUris.parseId(Uri.parse(saved.second))
        } catch (ignored: RuntimeException) {
            -1L
        }
        if (id >= 0) indexOne(id, mime.startsWith("video/"))
        val o = JSONObject().put("saved", saved.first).put("uri", saved.second)
            .put("id", if (id >= 0) id else JSONObject.NULL)
        return Http.json(Status.OK, o)
    }

    private fun indexOne(id: Long, isVideo: Boolean) {
        val base = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val one = LinkedHashMap<Long, Item>()
        val sel = "${MediaStore.MediaColumns._ID}=?"
        if (!collect(base, isVideo, one, IndexScope(appContext), sel, arrayOf(id.toString()))) return
        val item = one[id] ?: return
        synchronized(this) { install(snap.index + (id to item)) }
    }

    // zmazané z PC zmiznú z indexu hneď (bez prestavby); obnova po zmene v MediaStore to len potvrdí
    private fun removeIds(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        synchronized(this) { install(snap.index - ids.toSet()) }
    }

    // stránka sa servíruje aj bez prihlásenia (neobsahuje dáta; PIN formulár) — s jednorazovým CSP nonce
    private fun handleRoot(): Response {
        val page = html ?: try {
            appContext.assets.open("gallery.html").use { String(it.readBytes(), Charsets.UTF_8) }.also { html = it }
        } catch (e: Throwable) {
            return Http.error(Status.INTERNAL_ERROR, "gallery.html chýba")
        }
        val nonce = randomToken(NONCE_BYTES)
        val body = RequestGuard.injectNonce(page, nonce)
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", body)
        r.addHeader("Content-Security-Policy", RequestGuard.csp(nonce))
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    @Synchronized
    private fun rebuildIndex() {
        val t0 = System.nanoTime()
        val all = LinkedHashMap<Long, Item>()
        // zamknuté a vylúčené priečinky PC nevidí (rozhodnutie Lukáša 20.10.2026)
        val scope = IndexScope(appContext)
        // provider dočasne nedostupný (query vrátilo null) -> ponechať starý index a generáciu,
        // inak by PC uvidel prázdnu knižnicu a ponúkol všetko ako nové
        if (!collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, all, scope)) return
        if (!collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, all, scope)) return
        install(all)
        stats.rebuilt((System.nanoTime() - t0) / NS_PER_MS, System.currentTimeMillis())
    }

    // nový obsah indexu: jeden zoznam podľa dátumu (fotky aj videá premiešané), najnovšie prvé; pri zmene
    // nová generácia (aj uložená — ďalší beh servera pokračuje nad ňou)
    @Synchronized
    private fun install(all: Map<Long, Item>) {
        val map = LinkedHashMap<Long, Item>()
        all.values.sortedWith(compareByDescending<Item> { it.taken }.thenByDescending { it.id })
            .forEach { map[it.id] = it }
        val old = snap
        val delta = GenLog.diff(old.index.mapValues { fingerprint(it.value) }, map.mapValues { fingerprint(it.value) })
        val changed = delta.upserts.isNotEmpty() || delta.removed.isNotEmpty()
        val gen = if (changed) old.gen + 1 else old.gen
        if (changed) {
            genLog.record(gen, delta.upserts, delta.removed)
            syncPrefs.edit().putLong(KEY_LIST_GEN, gen).apply()
        }
        snap = Snapshot(map, gen, map.values.maxOfOrNull { it.mtime } ?: 0L)
    }

    // zmena položky pre /api/list?sinceGen: čas a veľkosť súboru, ale aj údaje, ktoré MediaStore doplní
    // až po skene (rozmery, dátum snímania) — inak by PC po nahratí videl navždy w=0
    private fun fingerprint(i: Item): Pair<Long, Long> =
        i.mtime to (i.size * FP_SIZE + i.taken * FP_TAKEN + i.w * FP_W + i.h * FP_H + i.orient)

    // stĺpce, ktoré nemusia existovať na každej verzii Androidu (video ORIENTATION až od API 29)
    private fun projection(isVideo: Boolean): Array<String> {
        val cols = arrayListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )
        if (!isVideo) cols.add(MediaStore.Images.ImageColumns.ORIENTATION)
        if (isVideo) cols.add(MediaStore.Video.VideoColumns.DURATION)
        if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cols.add(MediaStore.MediaColumns.ORIENTATION)
        return cols.toTypedArray()
    }

    // true = dotaz prebehol (aj prázdny výsledok), false = provider nevrátil kurzor
    private fun collect(
        base: Uri,
        isVideo: Boolean,
        into: MutableMap<Long, Item>,
        scope: IndexScope,
        selection: String? = null,
        args: Array<String>? = null,
    ): Boolean {
        val cursor = resolver.query(base, projection(isVideo), selection, args, null) ?: return false
        cursor.use { c ->
            val cols = Cols(c)
            while (c.moveToNext()) {
                val path = cols.str(cols.data) ?: ""
                // bez cesty sa nedá overiť priečinok -> radšej nesprístupniť (súkromie má prednosť)
                if (path.isNotEmpty() && scope.allows(path)) {
                    val item = cols.item(base, isVideo, path)
                    into[item.id] = item
                }
            }
        }
        return true
    }

    // indexy stĺpcov kurzora raz (nie pri každom riadku); chýbajúci stĺpec = -1 -> 0 / null
    private class Cols(private val c: android.database.Cursor) {
        private val id = c.getColumnIndex(MediaStore.MediaColumns._ID)
        val data = c.getColumnIndex(MediaStore.MediaColumns.DATA)
        private val name = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        private val size = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
        private val modified = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        private val taken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        private val bucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        private val mime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        private val width = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        private val height = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        private val orient = c.getColumnIndex(MediaStore.Images.ImageColumns.ORIENTATION)
        private val dur = c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)

        fun num(i: Int): Long = if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L

        fun str(i: Int): String? = if (i >= 0) c.getString(i) else null

        fun item(base: Uri, isVideo: Boolean, path: String): Item {
            val itemId = num(id)
            val n = str(name) ?: "$itemId"
            val b = str(bucket) ?: "Camera"
            val mtime = num(modified)
            val takenRaw = num(taken)
            return Item(
                id = itemId, name = n, relpath = "$b/$n", size = num(size), mtime = mtime,
                // bez dátumu snímania náhradne čas úpravy — ale takenReal=false (série z neho nevznikajú)
                taken = if (takenRaw > 0) takenRaw else mtime * MS,
                bucket = b, mime = str(mime) ?: if (isVideo) "video/*" else "image/*",
                uri = Uri.withAppendedPath(base, itemId.toString()), path = path, takenReal = takenRaw > 0,
                w = num(width).toInt(), h = num(height).toInt(), orient = num(orient).toInt(), dur = num(dur),
            )
        }
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
    } catch (e: Throwable) {
        -1L
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, len: Long) {
        if (len < 0) {
            // bez Content-Length najviac MAX_UPLOAD_BYTES a priebežne kontroluj voľné miesto —
            // inak by klient vedel zaplniť úložisko; výnimka = saveMediaScoped položku zmaže
            val buf = ByteArray(COPY_BUFFER)
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
        val buf = ByteArray(COPY_BUFFER)
        var remaining = len
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var r = 0
        for (i in x.indices) r = r or (x[i].toInt() xor y[i].toInt())
        return r == 0
    }
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
