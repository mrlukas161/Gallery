package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.faces.BurstGrouper
import org.fossify.gallery.faces.IndexScope
import org.fossify.gallery.faces.PhashDatabase
import org.fossify.gallery.faces.PhashGrouper
import java.io.File

// Skupiny pre „Podobné a duplikáty“ (série + presné kópie + podobné) z JEDNÉHO čítania MediaStore a pHash
// indexu. Zoznam aj karta „Na upratanie“ (HomeStats) berú to isté, takže čísla sedia.
//
// SÚKROMIE: fotky zo zamknutých a vylúčených priečinkov (IndexScope) sa vyradia ešte pred zoskupením —
// aj keď ich pHash index zatiaľ drží (vyčistí ich až ďalší beh indexera).
//
// CACHE: výsledok ostáva v pamäti, kým sa nezmení MediaStore (CompareMediaStore.stamp), pHash index
// (počet + najnovší zápis) ani rozsah (zamknutie/vylúčenie priečinka). Návrat z porovnávača tak nečíta
// celú knižnicu znova. Skryté skupiny sa tu neriešia (CompareHiddenStore) — menia sa bez prepočtu.
//
// load() siaha na MediaStore, Room a disk — volať len z vlákna na pozadí.
object CompareRepository {
    // sizes = veľkosti fotiek zo skupín (súčet uvoľniteľného miesta naprieč skupinami)
    class Snapshot(val groups: List<CompareGroup>, val sizes: Map<String, Long>, val hasPhashIndex: Boolean)

    // folderSamples = jedna cesta z každého priečinka (na zistenie, či sa rozsah nezmenil)
    private class Cached(
        val stamp: String,
        val folderSamples: List<String>,
        val blocked: Set<String>,
        val snapshot: Snapshot,
    )

    private val lock = Any()

    @Volatile
    private var cached: Cached? = null

    fun load(context: Context): Snapshot {
        val app = context.applicationContext
        synchronized(lock) {
            val stamp = CompareMediaStore.stamp(app) + "|" + phashStamp(app)
            val scope = IndexScope(app)
            val current = cached
            val unchanged = current != null && current.stamp == stamp &&
                blockedOf(current.folderSamples, scope) == current.blocked
            if (current != null && unchanged) {
                return current.snapshot
            }
            val fresh = compute(app, scope, stamp)
            cached = fresh
            return fresh.snapshot
        }
    }

    // Po návrate z porovnávača: keď niektorá fotka otvorenej skupiny zmizla (Kôš), MediaStore to nemusí
    // ukázať hneď — vtedy radšej prepočítať. Len na pozadí (disk).
    fun invalidateIfMissing(paths: Collection<String>) {
        if (paths.any { !File(it).exists() }) cached = null
    }

    private fun compute(app: Context, scope: IndexScope, stamp: String): Cached {
        val rows = CompareMediaStore.images(app)
        val samples = LinkedHashMap<String, String>()
        rows.forEach { samples.getOrPut(it.path.substringBeforeLast('/', "")) { it.path } }
        val blocked = blockedOf(samples.values, scope)
        val allowed = rows.filter { scope.allows(it.path) }
        val byPath = allowed.associateBy { it.path }
        val hashes = readHashes(app)

        // fotka v skupine musí ešte existovať (MediaStore môže chvíľu držať zmazaný súbor)
        val infos = HashMap<String, CompareGroups.Info?>()
        val info: (String) -> CompareGroups.Info? = { path ->
            infos.getOrPut(path) { byPath[path]?.let { toInfo(it) } }
        }

        val shots = allowed.map { BurstGrouper.Shot(it.path, it.takenMs, hashes[it.path] ?: 0L) }
        val bursts = BurstGrouper.group(shots)
            .mapNotNull { burst -> CompareGroups.build(CompareKind.BURST, burst.map { it.path }, info) }
        val photos = allowed.mapNotNull { r ->
            hashes[r.path]?.let { PhashGrouper.Photo(r.path, it, r.width, r.height, r.size) }
        }
        val duplicates = PhashGrouper.group(photos).mapNotNull { g ->
            val kind = if (g.kind == PhashGrouper.Kind.EXACT) CompareKind.EXACT else CompareKind.SIMILAR
            CompareGroups.build(kind, g.paths, info)
        }
        val groups = bursts + duplicates
        val sizes = HashMap<String, Long>()
        groups.forEach { g -> g.paths.forEach { p -> infos[p]?.let { sizes[p] = it.sizeBytes } } }
        return Cached(stamp, samples.values.toList(), blocked, Snapshot(groups, sizes, hashes.isNotEmpty()))
    }

    private fun toInfo(row: CompareMediaStore.Row): CompareGroups.Info? {
        val file = File(row.path)
        if (!file.exists()) return null
        val size = if (row.size > 0) row.size else file.length()
        val date = if (row.takenMs > 0) row.takenMs else row.modifiedMs
        return CompareGroups.Info(row.path, size, row.width, row.height, date)
    }

    private fun blockedOf(samples: Collection<String>, scope: IndexScope): Set<String> =
        samples.filterTo(HashSet()) { !scope.allows(it) }

    // len platné hashe (0 = nepodarilo sa spočítať)
    private fun readHashes(app: Context): Map<String, Long> = try {
        PhashDatabase.getInstance(app).PhashDao().getAllHashes()
            .filter { it.hashValue != 0L }
            .associate { it.path to it.hashValue }
    } catch (ignored: Throwable) {
        emptyMap()
    }

    private fun phashStamp(app: Context): String = try {
        val dao = PhashDatabase.getInstance(app).PhashDao()
        "${dao.count()}:${dao.maxIndexedAt() ?: 0L}"
    } catch (ignored: Throwable) {
        "-"
    }
}
