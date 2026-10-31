package org.fossify.gallery.sync

import android.content.Context
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.CompareAnalysis
import org.fossify.gallery.helpers.CompareHiddenStore
import org.fossify.gallery.helpers.CompareKind
import org.fossify.gallery.helpers.CompareRepository
import org.fossify.gallery.helpers.TYPE_VIDEOS
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------- SYNC MANAŽÉR LAN servera: značky na zmazanie, evidencia stiahnutých, stav skupín ----------
// Perzistentné v prefs "galeria_pcsync" (SyncStore/SyncExtras). Kľúč = cesta (stabilná aj po reštarte
// servera/telefónu); klientovi sa mapuje na aktuálne id z indexu servera.
class ServerMarks(private val appContext: Context, private val index: () -> Map<Long, MediaServer.Item>) {
    companion object {
        // značky/stiahnuté posiela web dávkovo (celý výber naraz)
        const val MAX_MARK_IDS = 20000
        const val MAX_MARK_BODY_BYTES = 512L * 1024
    }

    private val prefs get() = SyncStore.prefs(appContext)

    // aktuálny stav značiek pre klienta: marked=[id...], synced={id: čas_ms}
    fun state(): Response {
        val p = prefs
        val marked = SyncStore.markedPaths(p)
        val synced = SyncStore.syncedMap(p)
        val mArr = JSONArray()
        val sObj = JSONObject()
        for (it in index().values) {
            if (it.path in marked) mArr.put(it.id)
            synced[it.path]?.let { ts -> sObj.put(it.id.toString(), ts) }
        }
        val o = JSONObject().put("marked", mArr).put("synced", sObj).put("stateGen", SyncStore.stateGen(p))
        return Http.json(Status.OK, o)
    }

    // id zo starého tvaru (?id=) alebo z tela {"ids":[…]}; telo sa vráti kvôli ďalším poliam
    private fun idsFrom(session: IHTTPSession, legacy: Long?): Pair<List<Long>, JSONObject> {
        if (legacy != null) return listOf(legacy) to JSONObject()
        val body = Http.readJson(session, MAX_MARK_BODY_BYTES)
        val ids = RequestGuard.parseIds(body, MAX_MARK_IDS) ?: throw HttpError(Status.BAD_REQUEST, "bad ids")
        return ids to body
    }

    private fun pathsOf(ids: List<Long>): List<String> {
        val idx = index()
        return ids.mapNotNull { idx[it]?.path?.takeIf { p -> p.isNotEmpty() } }
    }

    // POST {"ids":[…], "del":true|false} (dávkovo) alebo pôvodné POST ?id=&del=1|0; neznáme id sa vynechajú
    fun mark(session: IHTTPSession): Response {
        val legacy = Http.param(session, "id")?.toLongOrNull()
        val (ids, body) = idsFrom(session, legacy)
        val del = if (legacy != null) Http.param(session, "del") != "0" else body.optBoolean("del", true)
        val paths = pathsOf(ids)
        if (legacy != null && paths.isEmpty()) return Http.error(Status.NOT_FOUND, "unknown id")
        val p = prefs
        val changed = SyncStore.setMarked(p, paths, del)
        val o = JSONObject().put("ok", true).put("marked", SyncStore.markedPaths(p).size)
            .put("changed", changed.size).put("stateGen", SyncStore.stateGen(p))
        return Http.json(Status.OK, o)
    }

    // POST {"ids":[…]} alebo ?id= — evidencia „stiahnuté do PC" (PC program po vlastnom stiahnutí)
    fun synced(session: IHTTPSession): Response {
        val legacy = Http.param(session, "id")?.toLongOrNull()
        val paths = pathsOf(idsFrom(session, legacy).first)
        if (legacy != null && paths.isEmpty()) return Http.error(Status.NOT_FOUND, "unknown id")
        SyncStore.addSynced(prefs, paths)
        return Http.json(Status.OK, JSONObject().put("ok", true).put("count", paths.size))
    }

    fun clearMarks(): Response {
        SyncStore.clearMarked(prefs)
        return Http.json(Status.OK, JSONObject().put("ok", true))
    }

    // GET: {states:{key:{s,t}}}; POST {key, state} alebo {states:{key:state}} (state "done"/"skip", "" = zmazať)
    fun groupState(session: IHTTPSession): Response {
        val p = prefs
        val states = if (session.method == Method.POST) {
            val body = Http.readJson(session, MAX_MARK_BODY_BYTES)
            val now = System.currentTimeMillis()
            SyncExtras.updateGroupStates(p) { map ->
                val updates = LinkedHashMap<String, String>()
                body.optString("key", "").takeIf { it.isNotEmpty() }?.let { updates[it] = body.optString("state", "") }
                body.optJSONObject("states")?.let { many ->
                    val keys = many.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        updates[k] = many.optString(k, "")
                    }
                }
                updates.count { (k, v) -> GroupStates.apply(map, k, v, now) } > 0
            }
        } else {
            SyncExtras.groupStates(p)
        }
        val o = JSONObject().put("states", GroupStates.toJson(states)).put("stateGen", SyncStore.stateGen(p))
        return Http.json(Status.OK, o)
    }
}

// Upratovanie: skupiny (série a duplikáty), mazanie do Koša, Kôš Galérie+ a obnova z neho.
class ServerCleanup(
    private val appContext: Context,
    private val snapshot: () -> Pair<Map<Long, MediaServer.Item>, Long>,
    private val rebuild: () -> Unit,
) {
    companion object {
        // strop na počet fotiek v jednej požiadavke na zmazanie (PC posiela dávky po desiatkach)
        const val MAX_DELETE_IDS = 1000
        const val MAX_BODY_BYTES = 64L * 1024
        private const val MAX_BIN_ITEMS = 3000
        private const val BIN_CACHE_MS = 15_000L
        private const val CONFIDENCE_SCALE = 100.0
    }

    // jedno mazanie / obnova naraz: NanoHTTPD obsluhuje každé spojenie vo vlastnom vlákne a dve súbežné
    // požiadavky s rovnakým id by kopírovali do toho istého súboru v Koši
    private val deleteLock = Any()

    @Volatile
    private var binCache: Pair<Long, Map<String, PcBin.BinItem>>? = null

    private val prefs get() = SyncStore.prefs(appContext)

    fun invalidateBin() {
        binCache = null
    }

    // Mazanie z PC/webu priamo v mobile (do Koša podľa nastavenia) — len po potvrdení na PC.
    // {"ids":[…], "dryRun":true} nič nezmaže, len vráti počet a uvoľnené bajty (na potvrdenie).
    fun delete(session: IHTTPSession): Response {
        // telo prečítať vždy, aj pri 409: neprečítané by na keep-alive spojení skazilo ďalšiu požiadavku (400)
        val body = Http.readJson(session, MAX_BODY_BYTES)
        val ids = RequestGuard.parseIds(body, MAX_DELETE_IDS) ?: throw HttpError(Status.BAD_REQUEST, "bad ids")
        val index = snapshot().first
        val items = ids.mapNotNull { index[it] }
        val unknown = ids.filter { it !in index }
        if (body.optBoolean("dryRun", false)) {
            val o = JSONObject().put("dryRun", true).put("count", items.size).put("freedBytes", items.sumOf { it.size })
                .put("unknown", JSONArray(unknown)).put("recycleBin", appContext.config.useRecycleBin)
                .put("allFiles", PcDeleter.canDelete())
            return Http.json(Status.OK, o)
        }
        if (!PcDeleter.canDelete()) return Http.error(Status.CONFLICT, "no_all_files_access")
        synchronized(deleteLock) {
            val failed = JSONObject()
            unknown.forEach { failed.put(it.toString(), "unknown id") }
            val r = PcDeleter.delete(appContext, items)
            r.failed.forEach { (id, msg) -> failed.put(id.toString(), msg) }
            val gone = items.filter { it.id in r.deleted }
            val gonePaths = gone.map { it.path }
            if (gonePaths.isNotEmpty()) SyncStore.removeMarked(prefs, gonePaths)
            // dávka do Koša -> „Vrátiť" na webe obnoví presne tieto fotky
            val batchId = if (r.toRecycleBin && gonePaths.isNotEmpty()) {
                BinBatches.newId(System.currentTimeMillis())
            } else {
                null
            }
            if (batchId != null) SyncExtras.addBinBatch(prefs, batchId, gonePaths)
            binCache = null
            rebuild()
            val o = JSONObject().put("deleted", JSONArray(r.deleted)).put("failed", failed)
                .put("recycleBin", r.toRecycleBin).put("freedBytes", gone.sumOf { it.size })
                .put("batchId", batchId ?: JSONObject.NULL)
            return Http.json(Status.OK, o)
        }
    }

    // ?v=2: {gen, groups:[{key, kind, ids, keeper, bytes, reclaim, from, to, bucket, best, confidence}]};
    // bez v: pôvodný formát {burst:[[id]], similar:[[id]]}. Skupiny sú TIE ISTÉ ako v mobile
    // (CompareRepository; skryté cez „Ponechať všetky“ / „Nie sú to duplikáty“ sa vynechajú).
    fun groups(session: IHTTPSession): Response {
        val (index, gen) = snapshot()
        val groups = currentGroups(index)
        val body = if (Http.param(session, "v") == "2") {
            JSONObject().put("gen", gen).put("groups", GroupBuilder.toJson(groups)).toString()
        } else {
            val burst = JSONArray()
            val similar = JSONArray()
            groups.forEach { g -> (if (g.kind == GroupBuilder.KIND_BURST) burst else similar).put(JSONArray(g.ids)) }
            JSONObject().put("burst", burst).put("similar", similar).toString()
        }
        val etag = ETags.of("g", ETags.hash(body))
        if (ETags.matches(session.headers["if-none-match"], etag)) return Http.notModified(etag)
        val r = Http.json(Status.OK, body)
        r.addHeader("ETag", etag)
        return r
    }

    // CompareRepository drží výsledok v cache, kým sa nezmení MediaStore, pHash index ani rozsah
    private fun currentGroups(index: Map<Long, MediaServer.Item>): List<GroupBuilder.Group> {
        val snap = CompareRepository.load(appContext)
        val hidden = CompareHiddenStore.get(appContext)
        val src = snap.groups.filter { !hidden.isHidden(it.paths) }.map { g ->
            val kind = when (g.kind) {
                CompareKind.BURST -> GroupBuilder.KIND_BURST
                CompareKind.EXACT -> GroupBuilder.KIND_EXACT
                CompareKind.SIMILAR -> GroupBuilder.KIND_SIMILAR
            }
            val best = bestOf(g.paths)
            GroupBuilder.Src(kind, g.paths, g.keeper, best?.first, best?.second, best?.third.orEmpty())
        }
        val byPath = HashMap<String, GroupBuilder.Member>(index.size)
        index.values.forEach { byPath[it.path] = GroupBuilder.Member(it.id, it.bucket, it.taken, it.size) }
        return GroupBuilder.fromPaths(src, byPath)
    }

    // najlepšia fotka podľa porovnávača (len z cache CompareAnalysis — server nič nepočíta):
    // (cesta, náskok skóre pred druhou 0–1, kódy dôvodov) alebo null
    private fun bestOf(paths: List<String>): Triple<String, Double, List<String>>? {
        val scores = try {
            CompareAnalysis.cachedScores(appContext, paths)
        } catch (ignored: Throwable) {
            null
        }
        val best = scores?.entries?.firstOrNull { it.value.rank == 0 } ?: return null
        val second = scores.values.filter { it.rank != 0 }.maxOfOrNull { it.score } ?: 0.0
        val margin = Math.round((best.value.score - second).coerceIn(0.0, 1.0) * CONFIDENCE_SCALE) / CONFIDENCE_SCALE
        return Triple(best.key, margin, best.value.reasons.map { it.code })
    }

    // Kôš Galérie+ (bez zamknutých/vylúčených priečinkov), najnovšie zmazané prvé
    fun binItems(): Map<String, PcBin.BinItem> {
        val now = System.currentTimeMillis()
        binCache?.let { if (now - it.first < BIN_CACHE_MS) return it.second }
        val map = LinkedHashMap<String, PcBin.BinItem>()
        PcBin.list(appContext).take(MAX_BIN_ITEMS).forEach { map[it.key] = it }
        binCache = now to map
        return map
    }

    fun bin(): Response {
        val batchOf = BinBatches.batchOfPath(SyncExtras.binBatchesRaw(prefs))
        val arr = JSONArray()
        binItems().values.forEach { b ->
            arr.put(
                JSONObject().put("key", b.key).put("name", b.name)
                    .put("bucket", File(b.origPath).parentFile?.name ?: "")
                    .put("size", b.size).put("deletedTs", b.deletedTs).put("video", b.type == TYPE_VIDEOS)
                    .put("batchId", batchOf[b.origPath] ?: JSONObject.NULL)
            )
        }
        val o = JSONObject().put("enabled", appContext.config.useRecycleBin).put("locked", PcBin.isLocked(appContext))
            .put("allFiles", PcDeleter.canDelete()).put("items", arr)
        return Http.json(Status.OK, o)
    }

    // POST {"batchId":"…"} alebo {"keys":["…"]} — obnova z Koša Galérie+ na pôvodné miesto
    fun restore(session: IHTTPSession): Response {
        val body = Http.readJson(session, ServerMarks.MAX_MARK_BODY_BYTES)
        if (!PcDeleter.canDelete()) return Http.error(Status.CONFLICT, "no_all_files_access")
        val keys = LinkedHashSet<String>()
        body.optString("batchId", "").takeIf { it.isNotEmpty() }?.let { id ->
            BinBatches.paths(SyncExtras.binBatchesRaw(prefs), id).forEach { keys.add(ETags.hash(it)) }
        }
        body.optJSONArray("keys")?.let { arr -> for (i in 0 until arr.length()) keys.add(arr.optString(i, "")) }
        synchronized(deleteLock) {
            binCache = null
            val bin = binItems()
            val r = PcBin.restore(appContext, keys.mapNotNull { bin[it] })
            val failed = JSONObject()
            keys.filter { it !in bin }.forEach { failed.put(it, "not in bin") }
            r.failed.forEach { (k, msg) -> failed.put(k, msg) }
            binCache = null
            rebuild()
            return Http.json(Status.OK, JSONObject().put("restored", JSONArray(r.restored)).put("failed", failed))
        }
    }
}
