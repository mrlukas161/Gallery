package org.fossify.gallery.sync

import android.content.Context
import android.os.Process
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.fossify.gallery.helpers.CompareAnalysis
import org.fossify.gallery.helpers.PhotoScore

// POST /api/quality {"ids":[…]} alebo {"groups":[[…],…]}: skóre kvality (ostrosť, expozícia, tváre, oči,
// úsmevy, poradie, dôvody) pre skupinu daných fotiek. Všetko spočítané (cache CompareAnalysis) -> 200 hneď;
// inak sa spustí JEDNA úloha na pozadí (QualityJobs) a klient dostáva 202 s priebehom {done, total}, kým
// sa nedopočíta. Iná požiadavka počas behu -> 429 busy. Model tvárí ide cez HeavyGate (CompareAnalysis):
// ak ho drží spracovanie knižnice dlhšie ako FACE_WAIT_MS, úloha dopočíta bez tvárí.
class ServerQuality(private val appContext: Context, private val index: () -> Map<Long, MediaServer.Item>) {
    companion object {
        private const val MAX_BODY_BYTES = 64L * 1024
        private const val FACE_WAIT_MS = 10_000L
        private const val MS_PER_S = 1000
    }

    private val jobs = QualityJobs()

    fun handle(session: IHTTPSession): Response {
        val body = Http.readJson(session, MAX_BODY_BYTES)
        val parsed = QualityRequest.parse(body)
        if (parsed is QualityRequest.Result.Bad) {
            return Http.json(Status.BAD_REQUEST, QualityRequest.badJson(parsed.code))
        }
        val idGroups = (parsed as QualityRequest.Result.Ok).groups
        val idx = index()
        // len fotky, ktoré server sprístupňuje (bez zamknutých/vylúčených priečinkov), videá sa nehodnotia
        val unknown = ArrayList<Long>()
        val indexed = idGroups.mapIndexedNotNull { i, ids ->
            val items = ids.mapNotNull { id ->
                val item = idx[id]?.takeIf { it.path.isNotEmpty() && it.mime.startsWith("image/") }
                if (item == null) unknown.add(id)
                item
            }
            items.takeIf { it.isNotEmpty() }?.let { i to it }
        }
        val groups = indexed.map { it.second }
        val pathGroups = groups.map { items -> items.map { it.path } }
        val key = QualityJobs.keyOf(pathGroups.map { GroupBuilder.key(it) })
        val states = pathGroups.map { CompareAnalysis.cachedState(appContext, it) }
        val complete = states.all { it.missing == 0 }
        val facesOk = states.all { it.facesChecked }
        val total = pathGroups.flatMapTo(HashSet()) { it }.size
        return when (val answer = jobs.request(key, total, complete, facesOk)) {
            is QualityJobs.Answer.Ready -> {
                val out = groups.mapIndexed { i, items ->
                    QualityJson.Group(
                        key = GroupBuilder.key(pathGroups[i]),
                        ids = items.map { it.id },
                        idOf = items.associate { it.path to it.id },
                        scores = states[i].scores,
                        index = indexed[i].first,
                    )
                }
                Http.json(Status.OK, QualityJson.done(out, unknown.distinct()) { path, s -> describe(path, s) })
            }
            is QualityJobs.Answer.Running -> {
                if (answer.started) launch(answer.job, pathGroups)
                Http.json(Status.ACCEPTED, QualityJson.running(answer.job))
            }
            is QualityJobs.Answer.Busy -> {
                val r = Http.json(Status.TOO_MANY_REQUESTS, QualityJson.busy(answer.done, answer.total))
                r.addHeader("Retry-After", (QualityJson.BUSY_RETRY_MS / MS_PER_S).toString())
                r
            }
        }
    }

    // zastavenie servera zastaví aj výpočet (na pozadí nič neostane bežať)
    fun stop() = jobs.cancelAll()

    private fun describe(path: String, score: PhotoScore): String = try {
        CompareAnalysis.describe(appContext, score, path)
    } catch (ignored: Exception) {
        score.reasons.joinToString(", ") { it.code }
    }

    // vlastné vlákno (požiadavka sa nesmie zdržať): NanoHTTPD vlákno nie je hlavné, ensureBackgroundThread
    // by úlohu spustil priamo v ňom
    private fun launch(job: QualityJobs.Job, groups: List<List<String>>) {
        val worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var completed = false
            try {
                CompareAnalysis.analyzeGroups(
                    appContext,
                    groups,
                    FACE_WAIT_MS,
                    onGroup = { _, _ -> },
                    onPhoto = { done, _ -> jobs.progress(job, done) },
                    isCancelled = { jobs.isCancelled(job) },
                    listener = object : CompareAnalysis.Listener {
                        override fun onWaitingForFaces() {
                            job.waiting = true
                        }
                    },
                )
                completed = !jobs.isCancelled(job)
            } catch (ignored: Exception) {
            } catch (ignored: OutOfMemoryError) {
            } finally {
                jobs.finish(job, completed)
            }
        }, "galeria-quality")
        worker.isDaemon = true
        worker.start()
    }
}
