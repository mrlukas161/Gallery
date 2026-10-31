package org.fossify.gallery.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ServerLogicTest {
    // ---------- Range ----------
    @Test fun range_bez_hlavicky_je_cely_subor() {
        assertEquals(HttpRange.Result.Full, HttpRange.parse(null, 100))
        assertEquals(HttpRange.Result.Full, HttpRange.parse("", 100))
    }

    @Test fun range_od_do() {
        val r = HttpRange.parse("bytes=10-19", 100) as HttpRange.Result.Partial
        assertEquals(10L, r.start)
        assertEquals(19L, r.end)
        assertEquals(10L, r.length)
        assertEquals("bytes 10-19/100", HttpRange.contentRange(r, 100))
    }

    @Test fun range_otvoreny_koniec_a_prilis_dlhy_koniec() {
        assertEquals(HttpRange.Result.Partial(90, 99), HttpRange.parse("bytes=90-", 100))
        assertEquals(HttpRange.Result.Partial(90, 99), HttpRange.parse("bytes=90-5000", 100))
        // Safari/Chrome pri videu: bytes=0-1
        assertEquals(HttpRange.Result.Partial(0, 1), HttpRange.parse("bytes=0-1", 100))
    }

    @Test fun range_suffix() {
        assertEquals(HttpRange.Result.Partial(80, 99), HttpRange.parse("bytes=-20", 100))
        assertEquals(HttpRange.Result.Partial(0, 99), HttpRange.parse("bytes=-500", 100))
        assertEquals(HttpRange.Result.Unsatisfiable, HttpRange.parse("bytes=-0", 100))
    }

    @Test fun range_mimo_suboru_je_416() {
        assertEquals(HttpRange.Result.Unsatisfiable, HttpRange.parse("bytes=100-", 100))
        assertEquals(HttpRange.Result.Unsatisfiable, HttpRange.parse("bytes=0-", 0))
        assertEquals("bytes */100", HttpRange.unsatisfiedRange(100))
    }

    @Test fun neplatny_alebo_viacnasobny_range_sa_ignoruje() {
        assertEquals(HttpRange.Result.Full, HttpRange.parse("bytes=5-1", 100))
        assertEquals(HttpRange.Result.Full, HttpRange.parse("bytes=0-1,5-6", 100))
        assertEquals(HttpRange.Result.Full, HttpRange.parse("items=0-1", 100))
        assertEquals(HttpRange.Result.Full, HttpRange.parse("bytes=a-b", 100))
        assertEquals(HttpRange.Result.Full, HttpRange.parse("bytes=-", 100))
        assertEquals(HttpRange.Result.Full, HttpRange.parse("bytes=+1-5", 100))
    }

    @Test fun if_range_s_inym_etagom_posle_cely_subor() {
        assertEquals(HttpRange.Result.Full, HttpRange.parse("bytes=0-9", 100, "\"stary\"", "\"novy\""))
        assertEquals(HttpRange.Result.Partial(0, 9), HttpRange.parse("bytes=0-9", 100, "\"novy\"", "\"novy\""))
    }

    // ---------- Content-Disposition ----------
    @Test fun content_disposition_ascii() {
        assertEquals(
            "attachment; filename=\"IMG_1.jpg\"; filename*=UTF-8''IMG_1.jpg",
            ContentDisposition.of("attachment", "IMG_1.jpg"),
        )
    }

    @Test fun content_disposition_diakritika_a_uvodzovky() {
        val h = ContentDisposition.of("inline", "Výlet \"Tatry\";x.jpg")
        assertEquals("inline; filename=\"V_let _Tatry__x.jpg\"; filename*=UTF-8''V%C3%BDlet%20%22Tatry%22%3Bx.jpg", h)
    }

    @Test fun content_disposition_bez_riadkov_a_lomiek() {
        val h = ContentDisposition.of("inline", "a\r\nSet-Cookie: x/../b.jpg")
        assertFalse(h.contains("\r") || h.contains("\n"))
        assertFalse(h.contains("/"))
        assertEquals("inline; filename=\"subor\"; filename*=UTF-8''subor", ContentDisposition.of("inline", "\n"))
    }

    @Test fun zmensena_kopia_ma_priponu_jpg() {
        assertEquals("IMG_1.jpg", ContentDisposition.jpegName("IMG_1.heic"))
        assertEquals("scan.v2.jpg", ContentDisposition.jpegName("scan.v2.png"))
        assertEquals("bezpripony.jpg", ContentDisposition.jpegName("bezpripony"))
    }

    // ---------- ETag ----------
    @Test fun etag_zhoda() {
        val e = ETags.of("f", 12, 34)
        assertEquals("\"f-12-34\"", e)
        assertTrue(ETags.matches("\"f-12-34\"", e))
        assertTrue(ETags.matches("W/\"f-12-34\"", e))
        assertTrue(ETags.matches("\"x\", \"f-12-34\"", e))
        assertTrue(ETags.matches("*", e))
        assertFalse(ETags.matches("\"f-12-35\"", e))
        assertFalse(ETags.matches(null, e))
    }

    // ---------- sinceGen ----------
    @Test fun gen_diff_zmenene_a_zmazane() {
        val d = GenLog.diff(mapOf(1L to (1L to 1L), 2L to (1L to 1L)), mapOf(1L to (2L to 1L), 3L to (1L to 1L)))
        assertEquals(setOf(1L, 3L), d.upserts)
        assertEquals(setOf(2L), d.removed)
    }

    @Test fun gen_log_spoji_zmeny() {
        val log = GenLog()
        log.record(1, listOf(10L), emptyList())
        log.record(2, listOf(11L), listOf(10L))
        log.record(3, listOf(10L), listOf(12L))
        val d = log.since(0, 3)!!
        assertEquals(setOf(11L, 10L), d.upserts)
        assertEquals(setOf(12L), d.removed)
        val d2 = log.since(1, 3)!!
        assertEquals(setOf(11L, 10L), d2.upserts)
        assertEquals(setOf(12L), d2.removed)
        val d3 = log.since(2, 3)!!
        assertEquals(setOf(10L), d3.upserts)
    }

    @Test fun gen_log_rovnaka_generacia_je_prazdna() {
        val d = GenLog().since(5, 5)!!
        assertTrue(d.upserts.isEmpty() && d.removed.isEmpty())
    }

    @Test fun gen_log_bez_historie_vyziada_cely_zoznam() {
        val log = GenLog(capacity = 2)
        log.record(1, listOf(1L), emptyList())
        log.record(2, listOf(2L), emptyList())
        log.record(3, listOf(3L), emptyList())
        assertNull(log.since(0, 3)) // generácia 1 už vypadla
        assertEquals(setOf(3L), log.since(2, 3)!!.upserts)
        assertNull(log.since(7, 3)) // budúca generácia (iný beh servera)
        assertNull(log.since(-1, 3))
    }

    // ---------- skupiny ----------
    private fun item(
        id: Long,
        bucket: String,
        date: Long,
        real: Boolean = true,
        size: Long = 100,
        image: Boolean = true,
    ) =
        GroupBuilder.In(id, "/DCIM/$bucket/$id.jpg", bucket, date, real, size, image)

    @Test fun seria_len_zo_skutocneho_datumu() {
        val items = listOf(item(1, "Camera", 1000), item(2, "Camera", 2000), item(3, "Camera", 2500, real = false))
        assertEquals(listOf(listOf(1L, 2L)), GroupBuilder.bursts(items))
    }

    @Test fun seria_neprekroci_priecinok_ani_medzeru() {
        val items = listOf(
            item(1, "Camera", 1000), item(2, "Camera", 4000), item(3, "Camera", 7001),
            item(4, "WhatsApp", 1500), item(5, "Camera", 8000, image = false),
        )
        assertEquals(listOf(listOf(1L, 2L)), GroupBuilder.bursts(items))
    }

    @Test fun serie_a_duplikaty_sa_neprekryvaju() {
        val items = listOf(
            item(1, "Camera", 1000), item(2, "Camera", 2000), item(3, "Camera", 3000),
            item(7, "Camera", 900_000), item(8, "Camera", 950_000),
            item(20, "Old", 5, size = 300), item(21, "Old", 10, size = 50),
        )
        // duplikát 3 <-> 7 spojí sériu 1-2-3 s fotkou 7; 20-21 sú samostatné duplikáty
        val groups = GroupBuilder.build(items, listOf(listOf(3L, 7L), listOf(20L, 21L), listOf(99L, 20L)))
        val all = groups.flatMap { it.ids }
        assertEquals(all.size, all.toSet().size)
        assertEquals(2, groups.size)
        val big = groups.first { 1L in it.ids }
        assertEquals(listOf(1L, 2L, 3L, 7L), big.ids)
        assertEquals(GroupBuilder.KIND_DUP, big.kind)
        val old = groups.first { 20L in it.ids }
        assertEquals(listOf(20L, 21L), old.ids)
        assertEquals(350L, old.bytes)
        assertEquals(50L, old.reclaim)
        assertEquals(5L, old.from)
        assertEquals(10L, old.to)
        assertEquals("Old", old.bucket)
    }

    @Test fun seria_s_duplikatom_vo_vnutri_ostava_seriou() {
        val items = listOf(item(1, "Camera", 1000), item(2, "Camera", 2000), item(3, "Camera", 3000))
        val g = GroupBuilder.build(items, listOf(listOf(1L, 3L))).single()
        assertEquals(GroupBuilder.KIND_BURST, g.kind)
        assertEquals(listOf(1L, 2L, 3L), g.ids)
    }

    @Test fun kluc_skupiny_je_stabilny() {
        val a = GroupBuilder.key(listOf("/a/2.jpg", "/a/1.jpg"))
        val b = GroupBuilder.key(listOf("/a/1.jpg", "/a/2.jpg"))
        assertEquals(a, b)
        assertEquals(16, a.length)
        assertNotEquals(a, GroupBuilder.key(listOf("/a/1.jpg", "/a/3.jpg")))
        assertTrue(GroupStates.validKey(a))
    }

    @Test fun skupiny_json_ma_best_null() {
        val g = GroupBuilder.build(listOf(item(1, "C", 1000), item(2, "C", 2000)), emptyList())
        val o = GroupBuilder.toJson(g).getJSONObject(0)
        assertTrue(o.isNull("best"))
        assertTrue(o.isNull("confidence"))
        assertEquals("burst", o.getString("kind"))
        assertEquals(2, o.getJSONArray("ids").length())
    }

    // ---------- stav skupín ----------
    @Test fun stav_skupin_ulozi_a_nacita() {
        val m = GroupStates.parse(null)
        assertTrue(GroupStates.apply(m, "0123456789abcdef", GroupStates.DONE, 5))
        assertTrue(GroupStates.apply(m, "fedcba9876543210", GroupStates.SKIP, 6))
        assertFalse(GroupStates.apply(m, "ZLY-KLUC", GroupStates.DONE, 7))
        val back = GroupStates.parse(GroupStates.toJson(m).toString())
        assertEquals(GroupStates.Entry("done", 5), back["0123456789abcdef"])
        assertEquals(GroupStates.Entry("skip", 6), back["fedcba9876543210"])
        GroupStates.apply(back, "0123456789abcdef", "", 8)
        assertNull(back["0123456789abcdef"])
    }

    @Test fun stav_skupin_ignoruje_smeti() {
        assertTrue(GroupStates.parse("{nie json").isEmpty())
        val m = GroupStates.parse("""{"0123456789abcdef":{"s":"xx","t":1},"abc":{"s":"done"}}""")
        assertTrue(m.isEmpty())
    }

    @Test fun stav_skupin_ma_strop() {
        val m = LinkedHashMap<String, GroupStates.Entry>()
        for (i in 0 until GroupStates.MAX + 3) GroupStates.apply(m, "%016x".format(i), GroupStates.DONE, i.toLong())
        assertEquals(GroupStates.MAX, m.size)
        assertNull(m["%016x".format(0)])
        assertTrue(m.containsKey("%016x".format(GroupStates.MAX + 2)))
    }

    // ---------- session, origin, cookie ----------
    @Test fun session_platnost_a_vyprsanie() {
        val s = Sessions(idleMs = 1000, max = 2)
        val a = s.create(0)
        assertEquals(64, a.length)
        assertTrue(s.valid(a, 500))
        assertTrue(s.valid(a, 1400)) // posledné použitie 500 -> ešte platí
        assertFalse(s.valid(a, 2500))
        assertFalse(s.valid(null, 0))
        assertFalse(s.valid("x", 0))
        val b = s.create(0)
        s.create(0)
        s.create(0)
        assertFalse(s.valid(b, 1)) // najstaršia vypadla
        assertEquals(2, s.count())
    }

    @Test fun origin_rovnaky_alebo_ziadny() {
        assertTrue(RequestGuard.sameOrigin(null, "192.168.1.5:8089", null))
        assertTrue(RequestGuard.sameOrigin("http://192.168.1.5:8089", "192.168.1.5:8089", "same-origin"))
        assertFalse(RequestGuard.sameOrigin("http://evil.example", "192.168.1.5:8089", null))
        assertFalse(RequestGuard.sameOrigin("null", "192.168.1.5:8089", null))
        assertFalse(RequestGuard.sameOrigin(null, "192.168.1.5:8089", "cross-site"))
        assertFalse(RequestGuard.sameOrigin("http://192.168.1.5:8089", null, null))
    }

    @Test fun cookie_parsovanie() {
        assertEquals("abc", RequestGuard.cookie("x=1; gp_sid=abc; y=2"))
        assertNull(RequestGuard.cookie("gp_sidx=abc"))
        assertNull(RequestGuard.cookie(null))
        assertTrue(RequestGuard.sessionCookie("t").contains("HttpOnly"))
        assertTrue(RequestGuard.sessionCookie("t").contains("SameSite=Strict"))
    }

    @Test fun nonce_do_html_a_csp() {
        val html = RequestGuard.injectNonce("<script nonce=\"__CSP_NONCE__\">", "N1")
        assertEquals("<script nonce=\"N1\">", html)
        assertTrue(RequestGuard.csp("N1").contains("script-src 'nonce-N1'"))
        assertTrue(RequestGuard.csp("N1").contains("frame-ancestors 'none'"))
    }

    @Test fun ids_z_tela() {
        assertEquals(listOf(3L, 1L), RequestGuard.parseIds(JSONObject("""{"ids":[3,1,3]}"""), 10))
        assertNull(RequestGuard.parseIds(JSONObject("""{"ids":[1,2,3]}"""), 2))
        assertNull(RequestGuard.parseIds(JSONObject("""{"x":[1]}"""), 10))
        assertNull(RequestGuard.parseIds(JSONObject("""{"ids":["a"]}"""), 10))
        assertEquals(listOf(1L, 2L), RequestGuard.parseIdList("1, 2,x,2", 8))
        assertEquals(listOf(1L), RequestGuard.parseIdList("1,2", 1))
        assertTrue(RequestGuard.parseIdList(null, 8).isEmpty())
    }

    // ---------- Kôš ----------
    @Test fun davky_kosa() {
        var raw: String? = null
        raw = BinBatches.add(raw, "b1", 10, listOf("/a/1.jpg", "/a/2.jpg"))
        raw = BinBatches.add(raw, "b2", 20, listOf("/a/2.jpg"))
        assertEquals(listOf("/a/1.jpg", "/a/2.jpg"), BinBatches.paths(raw, "b1"))
        assertTrue(BinBatches.paths(raw, "nie").isEmpty())
        val of = BinBatches.batchOfPath(raw)
        assertEquals("b1", of["/a/1.jpg"])
        assertEquals("b2", of["/a/2.jpg"])
        assertTrue(BinBatches.newId(1234).startsWith("b"))
    }

    @Test fun davky_kosa_maju_strop() {
        var raw: String? = null
        for (i in 0 until BinBatches.MAX + 5) raw = BinBatches.add(raw, "b$i", i.toLong(), listOf("/p$i"))
        assertTrue(BinBatches.paths(raw, "b0").isEmpty())
        assertEquals(listOf("/p${BinBatches.MAX + 4}"), BinBatches.paths(raw, "b${BinBatches.MAX + 4}"))
        assertTrue(BinBatches.paths("{zle", "b1").isEmpty())
    }

    @Test fun volny_nazov_pri_obnove() {
        val taken = setOf("/d/a.jpg", "/d/a_1.jpg")
        assertEquals("/d/b.jpg", AltName.free("/d/b.jpg") { it in taken })
        assertEquals("/d/a_2.jpg", AltName.free("/d/a.jpg") { it in taken })
        assertEquals("/d/x_1", AltName.free("/d/x") { it == "/d/x" })
    }

    // ---------- cache a prúd ----------
    @Test fun lru_drzi_limit() {
        val c = ByteLru(100)
        c.put("a", ByteArray(10))
        c.put("b", ByteArray(10))
        c.get("a")
        repeat(9) { c.put("x$it", ByteArray(10)) }
        assertTrue(c.size() <= 100)
        assertNull(c.get("b")) // najdlhšie nepoužitá
        c.put("velka", ByteArray(50)) // > 1/8 limitu sa necachuje
        assertNull(c.get("velka"))
    }

    @Test fun completion_stream_az_po_celom_subore() {
        var done = 0
        val s = CompletionStream(ByteArrayInputStream(ByteArray(10)), 10) { done++ }
        val buf = ByteArray(4)
        s.read(buf)
        assertEquals(0, done)
        while (s.read(buf) > 0) Unit
        assertEquals(1, done)
        s.read(buf)
        assertEquals(1, done)
    }

    @Test fun completion_stream_prerusene_sa_nezapise() {
        var done = 0
        val s = CompletionStream(ByteArrayInputStream(ByteArray(10)), 10) { done++ }
        s.read(ByteArray(5))
        s.close()
        assertEquals(0, done)
        val s2 = CompletionStream(ByteArrayInputStream(ByteArray(10)), 10) { done++ }
        s2.skip(5)
        while (s2.read(ByteArray(4)) > 0) Unit
        assertEquals(0, done)
    }
}
