package org.fossify.gallery.sync

import org.fossify.gallery.helpers.CompareBulk
import org.fossify.gallery.helpers.PhotoScore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

// Čistá logika POST /api/quality (bez Android tried, testy QualityJobsTest). Výpočet robí ServerQuality
// cez CompareAnalysis (HeavyGate, cache); tu je len: rozbor požiadavky s limitmi, fronta s JEDNOU úlohou
// naraz a JSON odpovede.

// {"ids":[…]} = jedna skupina, {"groups":[[…],[…]]} = viac skupín v jednej úlohe (web: hromadné
// „Ponechať najlepšiu“). Limity: najviac maxIds rôznych id a maxGroups skupín v jednej požiadavke.
object QualityRequest {
    const val MAX_IDS = 300
    const val MAX_GROUPS = 50
    const val TOO_MANY = "too_many_ids"
    const val BAD = "bad ids"

    sealed class Result {
        data class Ok(val groups: List<List<Long>>) : Result()
        data class Bad(val code: String) : Result()
    }

    fun parse(body: JSONObject, maxIds: Int = MAX_IDS, maxGroups: Int = MAX_GROUPS): Result {
        val arrays = arraysOf(body) ?: return Result.Bad(BAD)
        // dlhé pole sa ani nečíta celé (telo má aj tak strop 64 kB)
        if (arrays.size > maxGroups || arrays.any { it.length() > maxIds }) return Result.Bad(TOO_MANY)
        val groups = arrays.map { idsOf(it) ?: return Result.Bad(BAD) }
        // rovnaké id vo viacerých skupinách sa ráta raz
        return if (groups.flatMapTo(HashSet()) { it }.size > maxIds) Result.Bad(TOO_MANY) else Result.Ok(groups)
    }

    // null = chýba ids/groups, prázdne, alebo skupina nie je pole
    private fun arraysOf(body: JSONObject): List<JSONArray>? {
        val many = body.optJSONArray("groups")
        val list = if (many != null) {
            (0 until many.length()).map { many.optJSONArray(it) ?: return null }
        } else {
            listOfNotNull(body.optJSONArray("ids"))
        }
        return list.takeIf { it.isNotEmpty() }
    }

    // id bez duplicít v poradí; null = prázdne alebo neplatné (záporné, text)
    private fun idsOf(arr: JSONArray): List<Long>? {
        val ids = LinkedHashSet<Long>()
        for (i in 0 until arr.length()) ids.add(arr.optLong(i, -1L).takeIf { it >= 0 } ?: return null)
        return ids.toList().takeIf { it.isNotEmpty() }
    }

    fun badJson(code: String): JSONObject {
        val o = JSONObject().put("error", code)
        if (code == TOO_MANY) o.put("maxIds", MAX_IDS).put("maxGroups", MAX_GROUPS)
        return o
    }
}

// Fronta hodnotenia kvality: najviac JEDNA úloha naraz (ďalšia iná požiadavka dostane „busy“), rovnaká
// požiadavka počas behu dostane priebeh. Úloha sa sama zruší, keď sa klient prestane pýtať (abandonMs)
// alebo beží priveľmi dlho (maxRunMs) — na pozadí nič nebeží trvalo. Skupiny, ktoré sa už raz celé
// prepočítali, sa nespúšťajú znova len preto, že model tvárí nebol k dispozícii (attemptTtlMs).
class QualityJobs(
    private val clock: () -> Long = System::currentTimeMillis,
    private val abandonMs: Long = ABANDON_MS,
    private val maxRunMs: Long = MAX_RUN_MS,
    private val attemptTtlMs: Long = ATTEMPT_TTL_MS,
) {
    companion object {
        const val ABANDON_MS = 30_000L
        const val MAX_RUN_MS = 5L * 60 * 1000
        const val ATTEMPT_TTL_MS = 10L * 60 * 1000
        const val MAX_ATTEMPTED = 64

        // kľúč úlohy: zo zoradených kľúčov skupín (GroupBuilder.key)
        fun keyOf(groupKeys: List<String>): String = ETags.hash(groupKeys.sorted().joinToString(","))
    }

    // done/total = zmerané / všetky rôzne fotky, waiting = čaká na miesto v HeavyGate (spracovanie knižnice)
    class Job internal constructor(val key: String, val total: Int, internal val startedAt: Long) {
        @Volatile
        var done = 0

        @Volatile
        var waiting = false

        @Volatile
        internal var lastPoll = startedAt

        @Volatile
        internal var cancelled = false
    }

    sealed class Answer {
        // výsledok je v cache CompareAnalysis -> 200
        object Ready : Answer()

        // táto požiadavka sa počíta -> 202; started = práve vznikla, volajúci ju má spustiť
        data class Running(val job: Job, val started: Boolean) : Answer()

        // počíta sa iná požiadavka -> 429
        data class Busy(val done: Int, val total: Int) : Answer()
    }

    private var current: Job? = null
    private val attempted = LinkedHashMap<String, Long>()

    // complete = všetky fotky sú zmerané (alebo sa nedajú prečítať), facesOk = tváre sa hodnotili všade
    @Synchronized
    fun request(key: String, total: Int, complete: Boolean, facesOk: Boolean): Answer {
        val now = clock()
        attempted.entries.removeAll { now - it.value > attemptTtlMs }
        val cur = current
        // klient sa pýta na svoju úlohu -> žije ďalej
        if (cur != null && cur.key == key && !cur.cancelled) cur.lastPoll = now
        return when {
            complete && facesOk -> Answer.Ready
            cur != null && cur.key != key -> Answer.Busy(cur.done, cur.total)
            cur != null -> Answer.Running(cur, started = false)
            // už sa raz celé počítalo a chýbajú len tváre (model obsadený/nedostupný) -> nepočítať dokola
            complete && attempted.containsKey(key) -> Answer.Ready
            else -> Answer.Running(Job(key, total, now).also { current = it }, started = true)
        }
    }

    // volá vlákno úlohy po každej fotke; true = skončiť
    fun isCancelled(job: Job): Boolean {
        if (job.cancelled) return true
        val now = clock()
        if (now - job.lastPoll > abandonMs || now - job.startedAt > maxRunMs) job.cancelled = true
        return job.cancelled
    }

    fun progress(job: Job, done: Int) {
        job.done = done
        job.waiting = false
    }

    // koniec úlohy (aj po chybe); completed = prebehla celá
    @Synchronized
    fun finish(job: Job, completed: Boolean) {
        if (current === job) current = null
        if (completed && !job.cancelled) {
            attempted.remove(job.key)
            attempted[job.key] = clock()
            while (attempted.size > MAX_ATTEMPTED) attempted.remove(attempted.keys.first())
        }
    }

    @Synchronized
    fun cancelAll() {
        current?.cancelled = true
    }

    @Synchronized
    fun running(): Job? = current
}

// JSON odpovedí /api/quality
object QualityJson {
    private const val ROUND = 1000.0
    private const val CONFIDENCE_ROUND = 100.0

    // jedna skupina odpovede: key = GroupBuilder.key(cesty), ids = id v poradí požiadavky,
    // idOf = cesta -> id, scores = skóre zmeraných fotiek (cesta -> skóre), index = poradie v požiadavke
    class Group(
        val key: String,
        val ids: List<Long>,
        val idOf: Map<String, Long>,
        val scores: Map<String, PhotoScore>,
        val index: Int = 0,
    )

    fun running(job: QualityJobs.Job): JSONObject = JSONObject().put("state", "running")
        .put("done", job.done).put("total", job.total).put("waiting", job.waiting).put("retryMs", RETRY_MS)

    fun busy(done: Int, total: Int): JSONObject =
        JSONObject().put("error", "busy").put("done", done).put("total", total).put("retryMs", BUSY_RETRY_MS)

    // describe = čitateľné zdôvodnenie („ostrejšia o 18 %, všetci 3 majú otvorené oči“)
    fun done(groups: List<Group>, unknown: List<Long>, describe: (String, PhotoScore) -> String): JSONObject {
        val scores = JSONObject()
        val out = JSONObject()
        for (g in groups) {
            val order = g.scores.entries.sortedBy { it.value.rank }
            for ((path, s) in order) {
                val id = g.idOf[path] ?: continue
                scores.put(id.toString(), score(s, g.key, describe(path, s)))
            }
            val v = CompareBulk.verdict(g.scores)
            // náskok ★ pred najlepšou z ostatných (0–1), null = nie je s čím porovnať
            val confidence = v.second?.let { roundTo(v.confidence.coerceIn(0.0, 1.0), CONFIDENCE_ROUND) }
            val scored = order.mapNotNull { g.idOf[it.key] }
            out.put(
                g.key,
                JSONObject()
                    .put("index", g.index)
                    .put("ids", JSONArray(g.ids))
                    .put("order", JSONArray(scored))
                    .put("unscored", JSONArray(g.ids.filter { it !in scored }))
                    .put("best", v.best?.let { g.idOf[it] } ?: JSONObject.NULL)
                    .put("confidence", confidence ?: JSONObject.NULL)
                    .put("uncertain", v.uncertain)
                    .put("facesChecked", v.facesChecked),
            )
        }
        return JSONObject().put("state", "done").put("scores", scores).put("groups", out)
            .put("unknown", JSONArray(unknown))
    }

    fun score(s: PhotoScore, groupKey: String, text: String): JSONObject {
        val details = JSONArray()
        s.reasons.forEach { r ->
            details.put(JSONObject().put("code", r.code).put("value", r.value).put("total", r.total))
        }
        return JSONObject()
            .put("sharpRel", roundTo(s.sharpRel, ROUND))
            .put("exposure", roundTo(s.exposure, ROUND))
            .put("faces", s.faces)
            .put("eyesOpen", s.eyesOpen)
            .put("smiles", s.smiles)
            .put("score", roundTo(s.score, ROUND))
            .put("percent", s.percent)
            .put("rank", s.rank)
            .put("reasons", JSONArray(s.reasons.map { it.code }))
            .put("details", details)
            .put("text", text)
            .put("facesChecked", s.facesChecked)
            .put("groupKey", groupKey)
    }

    fun roundTo(v: Double, scale: Double): Double = (v * scale).roundToLong() / scale

    const val RETRY_MS = 1000
    const val BUSY_RETRY_MS = 2000
}
