package org.fossify.gallery.sync

import org.fossify.gallery.helpers.PhotoScore
import org.fossify.gallery.helpers.Reason
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// POST /api/quality: limity požiadavky, jedna úloha naraz (202 priebeh, 429 busy), zrušenie a JSON.
class QualityJobsTest {
    private var now = 1_000_000L
    private val jobs = QualityJobs(clock = { now }, abandonMs = 30_000L, maxRunMs = 300_000L, attemptTtlMs = 600_000L)

    // ---------- požiadavka a limity ----------

    @Test fun jedna_skupina_z_ids() {
        val r = QualityRequest.parse(JSONObject("""{"ids":[3,1,3,2]}""")) as QualityRequest.Result.Ok
        assertEquals(listOf(listOf(3L, 1L, 2L)), r.groups)
    }

    @Test fun viac_skupin() {
        val r = QualityRequest.parse(JSONObject("""{"groups":[[1,2],[2,3]]}""")) as QualityRequest.Result.Ok
        assertEquals(listOf(listOf(1L, 2L), listOf(2L, 3L)), r.groups)
    }

    @Test fun zle_telo_je_400() {
        val bad = QualityRequest.Result.Bad(QualityRequest.BAD)
        val bodies = listOf("{}", """{"ids":[]}""", """{"ids":[1,-2]}""", """{"ids":["x"]}""")
        (bodies + listOf("""{"groups":[1]}""", """{"groups":[]}"""))
            .forEach { assertEquals(it, bad, QualityRequest.parse(JSONObject(it))) }
    }

    @Test fun limit_poctu_id_a_skupin() {
        val ids = (1..5).joinToString(",")
        assertEquals(
            QualityRequest.Result.Bad(QualityRequest.TOO_MANY),
            QualityRequest.parse(JSONObject("""{"ids":[$ids]}"""), maxIds = 4, maxGroups = 10),
        )
        // rovnaké id vo viacerých skupinách sa ráta raz
        assertTrue(
            QualityRequest.parse(JSONObject("""{"groups":[[1,2,3],[2,3,4]]}"""), maxIds = 4, maxGroups = 10)
                is QualityRequest.Result.Ok,
        )
        assertEquals(
            QualityRequest.Result.Bad(QualityRequest.TOO_MANY),
            QualityRequest.parse(JSONObject("""{"groups":[[1,2,3],[4,5]]}"""), maxIds = 4, maxGroups = 10),
        )
        assertEquals(
            QualityRequest.Result.Bad(QualityRequest.TOO_MANY),
            QualityRequest.parse(JSONObject("""{"groups":[[1],[2],[3]]}"""), maxIds = 10, maxGroups = 2),
        )
        val bad = QualityRequest.badJson(QualityRequest.TOO_MANY)
        assertEquals(QualityRequest.MAX_IDS, bad.getInt("maxIds"))
        assertEquals(QualityRequest.MAX_GROUPS, bad.getInt("maxGroups"))
    }

    // ---------- fronta ----------

    @Test fun vsetko_v_cache_je_hned_200() {
        assertSame(QualityJobs.Answer.Ready, jobs.request("k", 3, complete = true, facesOk = true))
        assertEquals(null, jobs.running())
    }

    @Test fun nova_uloha_202_a_spusti_sa_raz() {
        val a = jobs.request("k", 3, complete = false, facesOk = false) as QualityJobs.Answer.Running
        assertTrue(a.started)
        assertEquals(3, a.job.total)
        jobs.progress(a.job, 2)
        val b = jobs.request("k", 3, complete = false, facesOk = false) as QualityJobs.Answer.Running
        assertFalse("rovnaká požiadavka nespúšťa druhú úlohu", b.started)
        assertSame(a.job, b.job)
        assertEquals(2, b.job.done)
        val json = QualityJson.running(b.job)
        assertEquals("running", json.getString("state"))
        assertEquals(2, json.getInt("done"))
        assertEquals(3, json.getInt("total"))
    }

    @Test fun ina_poziadavka_pocas_behu_je_busy() {
        val a = jobs.request("k1", 10, complete = false, facesOk = false) as QualityJobs.Answer.Running
        jobs.progress(a.job, 4)
        val busy = jobs.request("k2", 2, complete = false, facesOk = false) as QualityJobs.Answer.Busy
        assertEquals(4, busy.done)
        assertEquals(10, busy.total)
        // hotová v cache ide aj počas cudzej úlohy
        assertSame(QualityJobs.Answer.Ready, jobs.request("k3", 2, complete = true, facesOk = true))
        // po skončení sa môže spustiť ďalšia
        jobs.finish(a.job, completed = true)
        assertTrue((jobs.request("k2", 2, complete = false, facesOk = false) as QualityJobs.Answer.Running).started)
    }

    @Test fun po_dobehnuti_bez_tvari_sa_nepocita_dokola() {
        val a = jobs.request("k", 3, complete = false, facesOk = false) as QualityJobs.Answer.Running
        jobs.finish(a.job, completed = true)
        // model tvárí bol obsadený: všetko zmerané, tváre nie -> výsledok, žiadna nová úloha
        assertSame(QualityJobs.Answer.Ready, jobs.request("k", 3, complete = true, facesOk = false))
        // po čase sa skúsi znova (model už môže byť voľný)
        now += 600_001L
        assertTrue((jobs.request("k", 3, complete = true, facesOk = false) as QualityJobs.Answer.Running).started)
    }

    @Test fun chybajuce_fotky_spustia_ulohu_aj_po_pokuse() {
        val a = jobs.request("k", 3, complete = false, facesOk = false) as QualityJobs.Answer.Running
        jobs.finish(a.job, completed = true)
        // fotka sa medzitým zmenila (chýba v cache) -> počítať znova
        assertTrue((jobs.request("k", 3, complete = false, facesOk = true) as QualityJobs.Answer.Running).started)
    }

    @Test fun opustena_uloha_sa_zrusi() {
        val a = jobs.request("k", 3, complete = false, facesOk = false) as QualityJobs.Answer.Running
        now += 10_000L
        assertFalse(jobs.isCancelled(a.job))
        jobs.request("k", 3, complete = false, facesOk = false) // klient sa pýta -> žije
        now += 29_000L
        assertFalse(jobs.isCancelled(a.job))
        now += 2_000L
        assertTrue("bez otázky 30 s -> koniec", jobs.isCancelled(a.job))
        // zrušená úloha sa nezapíše ako hotová
        jobs.finish(a.job, completed = false)
        assertTrue((jobs.request("k", 3, complete = true, facesOk = false) as QualityJobs.Answer.Running).started)
    }

    @Test fun prilis_dlha_uloha_sa_zrusi() {
        val a = jobs.request("k", 3, complete = false, facesOk = false) as QualityJobs.Answer.Running
        repeat(12) {
            now += 25_000L
            jobs.request("k", 3, complete = false, facesOk = false)
        }
        assertFalse("presne 5 min ešte beží", jobs.isCancelled(a.job))
        now += 1_000L
        jobs.request("k", 3, complete = false, facesOk = false)
        assertTrue("dlhšie ako 5 min -> koniec, aj keď sa klient pýta", jobs.isCancelled(a.job))
    }

    @Test fun zastavenie_servera_zrusi_ulohu() {
        val a = jobs.request("k", 3, complete = false, facesOk = false) as QualityJobs.Answer.Running
        jobs.cancelAll()
        assertTrue(jobs.isCancelled(a.job))
        jobs.finish(a.job, completed = true)
        assertEquals(null, jobs.running())
        // zrušená sa nepočíta ako dokončená
        assertTrue((jobs.request("k", 3, complete = true, facesOk = false) as QualityJobs.Answer.Running).started)
    }

    @Test fun kluc_ulohy_nezavisi_od_poradia_skupin() {
        assertEquals(QualityJobs.keyOf(listOf("a", "b")), QualityJobs.keyOf(listOf("b", "a")))
        assertFalse(QualityJobs.keyOf(listOf("a")) == QualityJobs.keyOf(listOf("a", "b")))
    }

    // ---------- JSON výsledku ----------

    @Test fun json_vysledku() {
        val best = PhotoScore(
            1.0, 0.8, 3, 3, 2, 0.91,
            listOf(Reason(Reason.Kind.SHARPER, 18), Reason(Reason.Kind.ALL_EYES_OPEN, 3)), 0, true,
        )
        val other = PhotoScore(0.7, 0.8, 3, 2, 1, 0.6, listOf(Reason(Reason.Kind.EYES_CLOSED, 1)), 1, true)
        val g = QualityJson.Group(
            key = "abcd",
            ids = listOf(11L, 12L, 13L),
            idOf = mapOf("/a" to 11L, "/b" to 12L, "/c" to 13L),
            scores = mapOf("/b" to other, "/a" to best),
            index = 2,
        )
        val o = QualityJson.done(listOf(g), listOf(99L)) { path, _ -> "text $path" }
        assertEquals("done", o.getString("state"))
        val s = o.getJSONObject("scores").getJSONObject("11")
        assertEquals(0, s.getInt("rank"))
        assertEquals(3, s.getInt("eyesOpen"))
        assertEquals(0.91, s.getDouble("score"), 1e-9)
        assertEquals("sharper", s.getJSONArray("reasons").getString(0))
        assertEquals(18, s.getJSONArray("details").getJSONObject(0).getInt("value"))
        assertEquals("text /a", s.getString("text"))
        assertEquals("abcd", s.getString("groupKey"))
        val grp = o.getJSONObject("groups").getJSONObject("abcd")
        assertEquals(2, grp.getInt("index"))
        assertEquals(11L, grp.getLong("best"))
        assertEquals(0.31, grp.getDouble("confidence"), 1e-9)
        assertFalse(grp.getBoolean("uncertain"))
        assertEquals(11L, grp.getJSONArray("order").getLong(0))
        assertEquals(12L, grp.getJSONArray("order").getLong(1))
        // /c sa nedala zmerať
        assertEquals(13L, grp.getJSONArray("unscored").getLong(0))
        assertEquals(99L, o.getJSONArray("unknown").getLong(0))
        assertFalse(o.getJSONObject("scores").has("13"))
    }

    @Test fun json_neistej_skupiny() {
        val a = PhotoScore(1.0, 0.8, 0, 0, 0, 0.80, emptyList(), 0, true)
        val b = PhotoScore(0.98, 0.8, 0, 0, 0, 0.79, emptyList(), 1, true)
        val g = QualityJson.Group("k", listOf(1L, 2L), mapOf("/a" to 1L, "/b" to 2L), mapOf("/a" to a, "/b" to b))
        val grp = QualityJson.done(listOf(g), emptyList()) { _, _ -> "" }.getJSONObject("groups").getJSONObject("k")
        assertTrue(grp.getBoolean("uncertain"))
        assertEquals(1L, grp.getLong("best"))
    }

    // /api/groups?v=2: neistá ★ a čo ponechá hromadné „Ponechať najlepšiu“ (len fotky zo servera)
    @Test fun groups_v2_keep_a_uncertain() {
        val byPath = mapOf(
            "/1" to GroupBuilder.Member(1, "Camera", 10, 100),
            "/2" to GroupBuilder.Member(2, "Camera", 20, 100),
        )
        val src = listOf(
            GroupBuilder.Src("exact", listOf("/1", "/2"), "/1", keep = "/2"),
            GroupBuilder.Src("similar", listOf("/1", "/2"), "/1", best = "/1", confidence = 0.02, uncertain = true),
            GroupBuilder.Src("burst", listOf("/1", "/2"), "/1", keep = "/x"),
        )
        val g = GroupBuilder.fromPaths(src, byPath)
        val exact = g.first { it.kind == "exact" }
        assertEquals(2L, exact.keep)
        assertEquals(null, exact.uncertain)
        val similar = g.first { it.kind == "similar" }
        assertEquals(true, similar.uncertain)
        assertEquals(null, similar.keep)
        // keep mimo skupiny (fotka nie je na serveri) sa neposiela
        assertEquals(null, g.first { it.kind == "burst" }.keep)
        val json = GroupBuilder.toJson(g)
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            assertTrue(o.has("keep") && o.has("uncertain"))
        }
    }

    @Test fun busy_json() {
        val o = QualityJson.busy(3, 9)
        assertEquals("busy", o.getString("error"))
        assertEquals(3, o.getInt("done"))
        assertEquals(9, o.getInt("total"))
    }
}
