package org.fossify.gallery.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom

// Čistá logika LAN servera pre PC (bez Android tried) — testovateľná na JVM (ServerLogicTest).
// MediaServer ju len volá; tu nie je nič, čo by čítalo MediaStore alebo súbory.

// Hlavička Range (RFC 9110, jeden rozsah). Neplatnú hlavičku alebo viac rozsahov RFC dovoľuje
// ignorovať -> pošle sa celý súbor (200). Rozsah mimo súboru = 416.
object HttpRange {
    sealed class Result {
        object Full : Result()
        object Unsatisfiable : Result()
        data class Partial(val start: Long, val end: Long) : Result() {
            val length: Long get() = end - start + 1
        }
    }

    private const val PREFIX = "bytes="

    // ifRange: hlavička If-Range; ak nesedí s aktuálnym ETagom, súbor sa zmenil -> celý súbor
    fun parse(header: String?, size: Long, ifRange: String? = null, etag: String? = null): Result {
        val spec = specOf(header, size, ifRange, etag) ?: return Result.Full
        val dash = spec.indexOf('-')
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        return when {
            !valid(a, b) -> Result.Full
            a.isEmpty() -> suffix(b.toLongOrNull() ?: Long.MAX_VALUE, size)
            else -> span(a, b, size)
        }
    }

    // "0-99" z "bytes=0-99"; null = hlavičku ignorovať (chýba, iná jednotka, viac rozsahov, zmenený súbor)
    private fun specOf(header: String?, size: Long, ifRange: String?, etag: String?): String? {
        val h = header?.trim().orEmpty()
        val usable = size >= 0 && h.startsWith(PREFIX, ignoreCase = true) && (ifRange == null || ifRange.trim() == etag)
        val spec = if (usable) h.substring(PREFIX.length).trim() else ""
        return spec.takeIf { it.contains('-') && !it.contains(',') }
    }

    private fun valid(a: String, b: String) = digitsOrEmpty(a) && digitsOrEmpty(b) && (a.isNotEmpty() || b.isNotEmpty())

    private fun suffix(n: Long, size: Long): Result =
        if (n <= 0 || size == 0L) Result.Unsatisfiable else Result.Partial(maxOf(0L, size - n), size - 1)

    private fun span(a: String, b: String, size: Long): Result {
        val start = a.toLongOrNull() ?: return Result.Full
        val end = if (b.isEmpty()) Long.MAX_VALUE else b.toLongOrNull() ?: return Result.Full
        return when {
            end < start -> Result.Full
            start >= size -> Result.Unsatisfiable
            else -> Result.Partial(start, minOf(end, size - 1))
        }
    }

    private fun digitsOrEmpty(s: String) = s.all { it in '0'..'9' }

    fun contentRange(p: Result.Partial, size: Long) = "bytes ${p.start}-${p.end}/$size"

    fun unsatisfiedRange(size: Long) = "bytes */$size"
}

// Content-Disposition s bezpečným názvom: ASCII náhrada v filename (bez úvodzoviek, lomiek a riadiacich
// znakov — tie by rozbili hlavičku) a presný názov v filename*=UTF-8'' (RFC 6266 / 8187).
object ContentDisposition {
    private const val HEX = "0123456789ABCDEF"
    private const val ATTR_EXTRA = "!#$&+-.^_`|~"
    private const val ASCII_FIRST = 0x20
    private const val ASCII_LAST = 0x7e
    private const val BYTE_MASK = 0xff
    private const val NIBBLE = 4
    private const val LOW_NIBBLE = 0xf

    fun of(type: String, name: String): String {
        val clean = name.filter { it >= ' ' && it != '\u007f' }
            .replace('/', '_').replace('\\', '_').trim().ifEmpty { "subor" }
        val ascii = clean.map { c -> if (c.code in ASCII_FIRST..ASCII_LAST && c != '"' && c != ';') c else '_' }
            .joinToString("")
        return "$type; filename=\"$ascii\"; filename*=UTF-8''${encode(clean)}"
    }

    fun encode(s: String): String {
        val sb = StringBuilder()
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and BYTE_MASK
            val ch = c.toChar()
            if (attrChar(ch)) {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c shr NIBBLE]).append(HEX[c and LOW_NIBBLE])
            }
        }
        return sb.toString()
    }

    // RFC 8187 attr-char: písmená, číslice a pár bezpečných znakov sa nekódujú
    private fun attrChar(ch: Char) = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ATTR_EXTRA.indexOf(ch) >= 0

    // názov zmenšenej kópie: JPEG má mať príponu .jpg (nie pôvodnú .heic/.png)
    fun jpegName(name: String): String {
        val base = name.substringBeforeLast('.', name).ifEmpty { "foto" }
        return "$base.jpg"
    }
}

object ETags {
    private const val SHORT_HASH = 16

    fun of(vararg parts: Any): String = "\"" + parts.joinToString("-") { it.toString().replace("\"", "") } + "\""

    // If-None-Match: zoznam ETagov oddelený čiarkou, slabé (W/) porovnanie stačí; * = čokoľvek
    fun matches(ifNoneMatch: String?, etag: String): Boolean {
        if (ifNoneMatch.isNullOrBlank()) return false
        return ifNoneMatch.split(',').any {
            val t = it.trim().removePrefix("W/")
            t == "*" || t == etag
        }
    }

    fun hash(s: String): String = sha1Hex(s).take(SHORT_HASH)
}

fun sha1Hex(s: String): String = hexOf(MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)))

private const val HEX_DIGITS = "0123456789abcdef"
private const val HEX_SHIFT = 4
private const val HEX_LOW = 0xf

fun hexOf(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt()
        sb.append(HEX_DIGITS[(v shr HEX_SHIFT) and HEX_LOW]).append(HEX_DIGITS[v and HEX_LOW])
    }
    return sb.toString()
}

// História zmien zoznamu po generáciách pre /api/list?sinceGen=N. Pamätá si posledných `capacity`
// zmien; staršia (alebo budúca, napr. po reštarte servera) generácia = null -> klient si vezme celý zoznam.
class GenLog(private val capacity: Int = DEFAULT_CAPACITY) {
    data class Delta(val upserts: Set<Long>, val removed: Set<Long>)

    private class Entry(val gen: Long, val upserts: Set<Long>, val removed: Set<Long>)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(gen: Long, upserts: Collection<Long>, removed: Collection<Long>) {
        entries.addLast(Entry(gen, upserts.toSet(), removed.toSet()))
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun since(from: Long, current: Long): Delta? {
        if (from == current) return Delta(emptySet(), emptySet())
        if (from > current || from < 0) return null
        val newer = entries.filter { it.gen > from }
        // súvislá história from+1..current, inak chýba medzičlánok -> celý zoznam
        if (newer.isEmpty() || newer.first().gen != from + 1 || newer.last().gen != current) return null
        val up = LinkedHashSet<Long>()
        val rm = LinkedHashSet<Long>()
        for (e in newer) {
            for (id in e.upserts) {
                up.add(id)
                rm.remove(id)
            }
            for (id in e.removed) {
                rm.add(id)
                up.remove(id)
            }
        }
        return Delta(up, rm)
    }

    companion object {
        const val DEFAULT_CAPACITY = 64

        // rozdiel dvoch indexov (id -> (mtime, size)): pribudnuté/zmenené a zmiznuté id
        fun diff(old: Map<Long, Pair<Long, Long>>, new: Map<Long, Pair<Long, Long>>): Delta {
            val up = new.filter { (id, v) -> old[id] != v }.keys
            val rm = old.keys.filter { it !in new }.toSet()
            return Delta(up, rm)
        }
    }
}

// Skupiny pre /api/groups?v=2: série (burst) a duplikáty (pHash) zlúčené tak, aby sa žiadna fotka
// neobjavila v dvoch skupinách. Séria vzniká LEN z fotiek so skutočným dátumom snímania (EXIF/MediaStore
// DATE_TAKEN) — náhradný čas úpravy súboru by z kopírovaných fotiek robil falošné série.
object GroupBuilder {
    const val BURST_GAP_MS = 3000L
    const val KIND_BURST = "burst"
    const val KIND_DUP = "dup"

    data class In(
        val id: Long,
        val path: String,
        val bucket: String,
        val date: Long,
        val takenReal: Boolean,
        val size: Long,
        val image: Boolean,
    )

    data class Group(
        val key: String,
        val kind: String,
        val ids: List<Long>,
        val bytes: Long,
        val reclaim: Long,
        val from: Long,
        val to: Long,
        val bucket: String,
    )

    fun bursts(items: Collection<In>): List<List<Long>> {
        val out = ArrayList<List<Long>>()
        val sorted = items.filter { it.image && it.takenReal && it.date > 0 }
            .sortedWith(compareBy({ it.bucket }, { it.date }, { it.id }))
        var cur = ArrayList<Long>()
        var prev: In? = null
        for (it in sorted) {
            val p = prev
            if (p != null && p.bucket == it.bucket && it.date - p.date <= BURST_GAP_MS) {
                cur.add(it.id)
            } else {
                if (cur.size >= 2) out.add(cur)
                cur = arrayListOf(it.id)
            }
            prev = it
        }
        if (cur.size >= 2) out.add(cur)
        return out
    }

    fun build(items: Collection<In>, similar: List<List<Long>>): List<Group> {
        val byId = items.associateBy { it.id }
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long {
            var r = x
            while (parent[r] != r) r = parent[r] ?: r
            var c = x
            while (c != r) {
                val n = parent[c] ?: r
                parent[c] = r
                c = n
            }
            return r
        }

        fun union(a: Long, b: Long) {
            parent.putIfAbsent(a, a)
            parent.putIfAbsent(b, b)
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        val burstOf = HashMap<Long, Int>()
        bursts(items).forEachIndexed { i, ids ->
            ids.forEach { burstOf[it] = i }
            ids.zipWithNext().forEach { (a, b) -> union(a, b) }
        }
        for (g in similar) {
            val known = g.filter { it in byId }.distinct()
            known.zipWithNext().forEach { (a, b) -> union(a, b) }
        }
        val comps = parent.keys.groupBy { find(it) }.values.filter { it.size >= 2 }
        return comps.map { toGroup(it.mapNotNull { id -> byId[id] }, burstOf) }
            .sortedWith(compareByDescending<Group> { it.to }.thenBy { it.key })
    }

    private fun toGroup(members: List<In>, burstOf: Map<Long, Int>): Group {
        val sorted = members.sortedWith(compareBy({ it.date }, { it.id }))
        val bursts = sorted.map { burstOf[it.id] }.toSet()
        val kind = if (bursts.size == 1 && bursts.first() != null) KIND_BURST else KIND_DUP
        val bytes = sorted.sumOf { it.size.coerceAtLeast(0) }
        val biggest = sorted.maxOf { it.size.coerceAtLeast(0) }
        val bucket = sorted.groupingBy { it.bucket }.eachCount().maxByOrNull { it.value }?.key ?: ""
        return Group(
            key = key(sorted.map { it.path }),
            kind = kind,
            ids = sorted.map { it.id },
            bytes = bytes,
            reclaim = bytes - biggest,
            from = sorted.first().date,
            to = sorted.maxOf { it.date },
            bucket = bucket,
        )
    }

    // stabilný kľúč skupiny: hash zoradených ciest (id v MediaStore sa môže zmeniť, cesta nie)
    fun key(paths: List<String>): String = ETags.hash(paths.sorted().joinToString("\n"))

    fun toJson(groups: List<Group>): JSONArray {
        val arr = JSONArray()
        for (g in groups) {
            arr.put(
                JSONObject()
                    .put("key", g.key)
                    .put("kind", g.kind)
                    .put("ids", JSONArray(g.ids))
                    .put("bytes", g.bytes)
                    .put("reclaim", g.reclaim)
                    .put("from", g.from)
                    .put("to", g.to)
                    .put("bucket", g.bucket)
                    // vlna 2 (CompareAnalysis): najlepšia fotka skupiny a istota odporúčania
                    .put("best", JSONObject.NULL)
                    .put("confidence", JSONObject.NULL)
            )
        }
        return arr
    }
}

// Stav prechádzania skupín na webe (spracovaná / preskočená), uložený na serveri, aby prežil
// zatvorenie prehliadača a bol rovnaký na viacerých PC. Kľúč = GroupBuilder.key.
object GroupStates {
    const val DONE = "done"
    const val SKIP = "skip"
    const val MAX = 5000
    private val KEY_RE = Regex("^[0-9a-f]{8,64}$")

    data class Entry(val state: String, val ts: Long)

    fun validKey(key: String) = KEY_RE.matches(key)

    fun parse(raw: String?): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        if (raw.isNullOrEmpty()) return out
        val o = try {
            JSONObject(raw)
        } catch (ignored: org.json.JSONException) {
            return out
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val e = o.optJSONObject(k) ?: continue
            val s = e.optString("s", "")
            if (validKey(k) && (s == DONE || s == SKIP)) out[k] = Entry(s, e.optLong("t", 0L))
        }
        return out
    }

    // state: "done" / "skip" nastaví, čokoľvek iné (""/"new"/null) stav zmaže; false = neplatný kľúč
    fun apply(map: MutableMap<String, Entry>, key: String, state: String?, now: Long): Boolean {
        if (!validKey(key)) return false
        if (state == DONE || state == SKIP) map[key] = Entry(state, now) else map.remove(key)
        if (map.size > MAX) {
            map.entries.sortedBy { it.value.ts }.take(map.size - MAX).map { it.key }.forEach { map.remove(it) }
        }
        return true
    }

    fun toJson(map: Map<String, Entry>): JSONObject {
        val o = JSONObject()
        map.forEach { (k, e) -> o.put(k, JSONObject().put("s", e.state).put("t", e.ts)) }
        return o
    }
}

// Prihlásenie prehliadača: PIN sa vymení za náhodnú session (HttpOnly cookie), PIN potom nemusí
// visieť v adrese. Sessions žijú len v pamäti servera — nový štart servera = nový PIN aj nové prihlásenie.
class Sessions(
    private val idleMs: Long = DEFAULT_IDLE_MS,
    private val max: Int = DEFAULT_MAX,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lastSeen = LinkedHashMap<String, Long>()

    @Synchronized
    fun create(now: Long): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = hexOf(bytes)
        lastSeen[token] = now
        while (lastSeen.size > max) lastSeen.remove(lastSeen.keys.first())
        return token
    }

    @Synchronized
    fun valid(token: String?, now: Long): Boolean {
        if (token.isNullOrEmpty()) return false
        val seen = lastSeen[token] ?: return false
        if (now - seen > idleMs) {
            lastSeen.remove(token)
            return false
        }
        lastSeen[token] = now
        return true
    }

    @Synchronized
    fun remove(token: String?) {
        if (token != null) lastSeen.remove(token)
    }

    @Synchronized
    fun count(): Int = lastSeen.size

    companion object {
        const val DEFAULT_IDLE_MS = 12L * 60 * 60 * 1000
        const val DEFAULT_MAX = 64
        private const val TOKEN_BYTES = 32
    }
}

// Ochrana pred CSRF a cudzími stránkami: zmeny len z rovnakého pôvodu (Origin == http://Host).
// Požiadavka bez Origin (PC program, curl) prejde — tú chráni PIN v hlavičke X-Auth-Token.
object RequestGuard {
    const val COOKIE = "gp_sid"

    fun sameOrigin(origin: String?, host: String?, fetchSite: String?): Boolean {
        if (fetchSite != null && fetchSite != "same-origin" && fetchSite != "none") return false
        if (origin == null) return true
        if (host.isNullOrBlank() || origin == "null") return false
        return origin.trim().equals("http://${host.trim()}", ignoreCase = true)
    }

    fun cookie(header: String?, name: String = COOKIE): String? {
        if (header.isNullOrEmpty()) return null
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq).trim() == name) return part.substring(eq + 1).trim()
        }
        return null
    }

    fun sessionCookie(token: String) = "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict"

    fun clearCookie() = "$COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"

    // CSP stránky: skripty len s jednorazovým nonce, všetko ostatné len z rovnakého pôvodu
    fun csp(nonce: String) = "default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; " +
        "img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self'; form-action 'self'; " +
        "base-uri 'none'; frame-ancestors 'none'"

    const val NONCE_PLACEHOLDER = "__CSP_NONCE__"

    fun injectNonce(html: String, nonce: String) = html.replace(NONCE_PLACEHOLDER, nonce)

    // čísla id z tela {"ids":[...]} — null = zlé telo; duplicity preč, poradie zachované
    fun parseIds(body: JSONObject, max: Int): List<Long>? {
        val arr = body.optJSONArray("ids") ?: return null
        if (arr.length() > max) return null
        val out = LinkedHashSet<Long>()
        for (i in 0 until arr.length()) {
            val v = arr.optLong(i, -1L)
            if (v < 0) return null
            out.add(v)
        }
        return out.toList()
    }

    // ?ids=1,2,3 (meta) — neplatné kúsky sa preskočia
    fun parseIdList(param: String?, max: Int): List<Long> =
        param.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.distinct().take(max)
}

// Dávky mazania do Koša z PC: batchId -> pôvodné cesty, aby šlo „Vrátiť" celé posledné mazanie.
// Uložené ako JSON {"id":{"t":čas,"p":[cesty]}}, drží sa len posledných MAX dávok.
object BinBatches {
    const val MAX = 30
    private const val RADIX = 36
    private const val RANDOM_BOUND = 1_000_000

    fun newId(now: Long, random: java.util.Random = SecureRandom()): String =
        "b" + now.toString(RADIX) + random.nextInt(RANDOM_BOUND).toString(RADIX)

    fun add(raw: String?, id: String, now: Long, paths: List<String>): String {
        val o = read(raw)
        o.put(id, JSONObject().put("t", now).put("p", JSONArray(paths)))
        val ids = ArrayList<Pair<String, Long>>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            ids.add(k to (o.optJSONObject(k)?.optLong("t", 0L) ?: 0L))
        }
        ids.sortedBy { it.second }.take((ids.size - MAX).coerceAtLeast(0)).forEach { o.remove(it.first) }
        return o.toString()
    }

    fun paths(raw: String?, id: String): List<String> {
        val arr = read(raw).optJSONObject(id)?.optJSONArray("p") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, "").ifEmpty { null } }
    }

    // pôvodná cesta -> id najnovšej dávky, v ktorej sa zmazala
    fun batchOfPath(raw: String?): Map<String, String> {
        val o = read(raw)
        val out = HashMap<String, Pair<String, Long>>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val b = o.optJSONObject(k)
            val t = b?.optLong("t", 0L) ?: 0L
            val p = b?.optJSONArray("p") ?: JSONArray()
            for (i in 0 until p.length()) {
                val path = p.optString(i, "")
                val prev = out[path]
                if (path.isNotEmpty() && (prev == null || prev.second < t)) out[path] = k to t
            }
        }
        return out.mapValues { it.value.first }
    }

    private fun read(raw: String?): JSONObject = try {
        if (raw.isNullOrEmpty()) JSONObject() else JSONObject(raw)
    } catch (ignored: org.json.JSONException) {
        JSONObject()
    }
}

// Voľný názov pri obnove z Koša, keď na pôvodnom mieste už súbor je: foto.jpg -> foto_1.jpg, foto_2.jpg…
object AltName {
    private const val MAX_TRIES = 9999

    fun free(path: String, exists: (String) -> Boolean): String? {
        if (!exists(path)) return path
        val slash = path.lastIndexOf('/')
        val dir = path.substring(0, slash + 1)
        val name = path.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..MAX_TRIES) {
            val cand = "$dir${base}_$i$ext"
            if (!exists(cand)) return cand
        }
        return null
    }
}

// Malá LRU cache bajtov (náhľady JPEG) s limitom celkovej veľkosti.
class ByteLru(private val maxBytes: Long) {
    private val map = LinkedHashMap<String, ByteArray>(INITIAL, LOAD, true)
    private var total = 0L

    @Synchronized
    fun get(key: String): ByteArray? = map[key]

    @Synchronized
    fun put(key: String, value: ByteArray) {
        // jedna obrovská položka by vytlačila všetko ostatné
        if (value.size > maxBytes / MAX_SHARE) return
        map.remove(key)?.let { total -= it.size }
        map[key] = value
        total += value.size
        val it = map.entries.iterator()
        while (total > maxBytes && it.hasNext()) {
            val e = it.next()
            total -= e.value.size
            it.remove()
        }
    }

    @Synchronized
    fun size(): Long = total

    @Synchronized
    fun clear() {
        map.clear()
        total = 0
    }

    private companion object {
        const val INITIAL = 64
        const val LOAD = 0.75f
        const val MAX_SHARE = 8
    }
}

// Prúd, ktorý zavolá onComplete, keď sa z neho prečíta celých `expected` bajtov — server tak
// zapíše „✓ v PC" až po úspešnom odoslaní celého originálu (prerušené sťahovanie sa nezapíše).
class CompletionStream(inner: InputStream, private val expected: Long, private val onComplete: () -> Unit) :
    FilterInputStream(inner) {
    private var count = 0L
    private var fired = false

    override fun read(): Int {
        val r = super.read()
        if (r >= 0) add(1)
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) add(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        // preskočené bajty klient nedostal -> už nikdy „celé"
        fired = true
        return super.skip(n)
    }

    private fun add(n: Long) {
        count += n
        if (!fired && count >= expected) {
            fired = true
            onComplete()
        }
    }
}
