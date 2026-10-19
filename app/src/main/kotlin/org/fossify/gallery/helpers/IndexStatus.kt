package org.fossify.gallery.helpers

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

// Jedno miesto, kde sa dá zistiť priebeh KAŽDÉHO indexera (0–100 %) — pre prehľadné Nastavenia.
// Číta reálne počty z databáz vs. počet fotiek v knižnici; percentá sa počítajú rovnako pre všetky
// funkcie, takže ukazovateľ je konzistentný.
object IndexStatus {

    // modelMissing: funkcia nemôže bežať, kým sa nestiahne model (CLIP) — do celkového
    // percenta sa vtedy nepočíta, inak by sa ukazovateľ navždy zasekol pod 100 %
    data class Item(
        val key: String,
        val titleRes: Int,
        val done: Int,
        val total: Int,
        val running: Boolean,
        val modelMissing: Boolean = false,
    ) {
        val percent: Int = if (total <= 0) 0 else ((done.toLong() * 100 / total).toInt()).coerceIn(0, 100)
        val complete: Boolean = total > 0 && done >= total
    }

    private const val CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val CLEANUP_BATCH = 500 // ciest na tabuľku a deň
    private const val PROBE_SAMPLE = 40

    @Volatile
    private var cleanupRunning = false

    fun photoCount(context: Context): Int = try {
        context.contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media._ID), null, null, null,
        )?.use { it.count } ?: 0
    } catch (e: Throwable) {
        0
    }

    fun all(context: Context, totalPhotos: Int): List<Item> {
        maybeCleanup(context)
        // done nikdy nad total — indexové DB môžu obsahovať riadky už zmazaných fotiek
        // (kým ich denné upratovanie nezmaže) a percento by inak klamalo
        fun clamp(done: Int): Int = if (totalPhotos > 0) done.coerceAtMost(totalPhotos) else done
        val list = ArrayList<Item>(6)
        list.add(
            Item(
                "faces", org.fossify.gallery.R.string.indexing_faces,
                clamp(safe { org.fossify.gallery.faces.FacesDatabase.getInstance(context).FaceDao().getProcessedCount() }),
                totalPhotos, org.fossify.gallery.faces.FaceIndexer.isRunning,
            )
        )
        list.add(
            Item(
                "ocr", org.fossify.gallery.R.string.indexing_ocr,
                clamp(safe { org.fossify.gallery.faces.OcrDatabase.getInstance(context).OcrDao().count() }),
                totalPhotos, org.fossify.gallery.faces.OcrIndexer.isRunning,
            )
        )
        list.add(
            Item(
                "geo", org.fossify.gallery.R.string.indexing_geo,
                clamp(safe { org.fossify.gallery.faces.GeoDatabase.getInstance(context).GeoDao().count() }),
                totalPhotos, org.fossify.gallery.faces.GeoIndexer.isRunning,
            )
        )
        list.add(
            Item(
                "qr", org.fossify.gallery.R.string.indexing_qr,
                clamp(safe { org.fossify.gallery.faces.QrDatabase.getInstance(context).QrDao().count() }),
                totalPhotos, org.fossify.gallery.faces.QrIndexer.isRunning,
            )
        )
        list.add(
            Item(
                "phash", org.fossify.gallery.R.string.indexing_phash,
                clamp(safe { org.fossify.gallery.faces.PhashDatabase.getInstance(context).PhashDao().count() }),
                totalPhotos, org.fossify.gallery.faces.PhashIndexer.isRunning,
            )
        )
        // CLIP počítame VŽDY: keď model nie je stiahnutý, ukazuje 0 % s príznakom modelMissing
        // (ťuk na prehľad ho stiahne) — do celkového percenta sa vtedy nezapočítava
        val clipPresent = org.fossify.gallery.clip.ClipModels.bothPresent(context)
        val clipDone = if (clipPresent) {
            clamp(safe { org.fossify.gallery.clip.ClipDatabase.getInstance(context).ClipDao().count() })
        } else {
            0
        }
        list.add(
            Item(
                "clip", org.fossify.gallery.R.string.indexing_clip,
                clipDone, totalPhotos, org.fossify.gallery.clip.ClipIndexer.isRunning,
                modelMissing = !clipPresent,
            )
        )
        return list
    }

    // celkové percento naprieč všetkými funkciami; indexery čakajúce na model sa nepočítajú
    fun overallPercent(items: List<Item>): Int {
        val counted = items.filter { !it.modelMissing }
        if (counted.isEmpty()) return 0
        return counted.sumOf { it.percent } / counted.size
    }

    // Lacné upratanie PRESTAVATEĽNÝCH indexov: raz denne na pozadí zmaž riadky fotiek, ktoré naozaj
    // zmizli (nikde inde sa riadky nemažú — počty by navždy prevyšovali realitu).
    //
    // ⛔ Tabuľka `faces` sa tu NEUPRATUJE NIKDY: id tvárí odkazujú face_assignments a cannot_links
    // (people.db) aj ignored_faces (extras.db) — zmazaný riadok = stratené meno osoby. Predtým sa
    // mazala pri každom File.exists() == false, teda aj pri presune/premenovaní fotky alebo dočasne
    // nedostupnom úložisku. `indexed_photos` sa maže LEN pre fotky bez tvárí (inak by ich ďalšie
    // spracovanie knižnice detegovalo nanovo).
    //
    // „Naozaj zmizla" = File.exists() je false A MediaStore ju nepozná A jej úložisko je pripojené.
    // Keď súborové API nevyzerá spoľahlivo (chýba povolenie na všetky médiá, MediaStore je prázdny,
    // fotky známe MediaStore „neexistujú"), neupratuje sa nič. Každá tabuľka sa prechádza po dávkach
    // CLEANUP_BATCH s posunom uloženým v prefs — postupne sa vystrieda celá tabuľka (predtým sa
    // kontrolovalo stále tých istých prvých 2000 ciest prvej tabuľky a na ostatné nikdy nedošlo).
    fun maybeCleanup(context: Context) {
        val appCtx = context.applicationContext
        try {
            val prefs = appCtx.getSharedPreferences("galeria_faces", Context.MODE_PRIVATE)
            val last = prefs.getLong("index_cleanup_at", 0L)
            if (System.currentTimeMillis() - last < CLEANUP_INTERVAL_MS || cleanupRunning) return
            cleanupRunning = true
            org.fossify.commons.helpers.ensureBackgroundThread {
                try {
                    prefs.edit().putLong("index_cleanup_at", System.currentTimeMillis()).apply()
                    cleanupIndexes(appCtx, prefs)
                } catch (ignored: Throwable) {
                } finally {
                    cleanupRunning = false
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    // keepWhere = dodatočná podmienka DELETE, ktorá chráni riadky, čo sa zmazať nesmú
    private class CleanupTarget(
        val db: androidx.room.RoomDatabase,
        val table: String,
        val col: String,
        val keepWhere: String? = null,
    )

    private fun cleanupIndexes(context: Context, prefs: SharedPreferences) {
        if (!canReadAllMedia(context)) return
        val known = mediaStorePaths(context) ?: return
        if (known.isEmpty() || !fileApiReliable(known)) return
        val storage = try {
            context.getSystemService(StorageManager::class.java)
        } catch (e: Throwable) {
            null
        }
        val mountedByParent = HashMap<String, Boolean>()
        val reallyMissing: (String) -> Boolean = { path ->
            path !in known && !File(path).exists() &&
                mountedByParent.getOrPut(path.substringBeforeLast('/', "")) { volumeMounted(storage, path) }
        }
        val targets = listOf(
            CleanupTarget(
                org.fossify.gallery.faces.FacesDatabase.getInstance(context), "indexed_photos", "path",
                keepWhere = "AND NOT EXISTS (SELECT 1 FROM faces WHERE faces.media_full_path = indexed_photos.path)",
            ),
            CleanupTarget(org.fossify.gallery.faces.OcrDatabase.getInstance(context), "ocr_text", "path"),
            CleanupTarget(org.fossify.gallery.faces.QrDatabase.getInstance(context), "qr_codes", "path"),
            CleanupTarget(org.fossify.gallery.faces.GeoDatabase.getInstance(context), "geo", "path"),
            CleanupTarget(org.fossify.gallery.faces.PhashDatabase.getInstance(context), "phash_index", "path"),
            CleanupTarget(org.fossify.gallery.clip.ClipDatabase.getInstance(context), "clip_index", "path"),
        )
        for (t in targets) {
            try {
                cleanupTable(t, prefs, reallyMissing)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun cleanupTable(t: CleanupTarget, prefs: SharedPreferences, reallyMissing: (String) -> Boolean) {
        val offKey = "index_cleanup_off_${t.table}"
        val offset = prefs.getInt(offKey, 0).coerceAtLeast(0)
        val sql = t.db.openHelper.writableDatabase
        val paths = ArrayList<String>(CLEANUP_BATCH)
        sql.query("SELECT DISTINCT ${t.col} FROM ${t.table} ORDER BY ${t.col} LIMIT $CLEANUP_BATCH OFFSET $offset").use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { paths.add(it) }
            }
        }
        var deleted = 0
        paths.filter(reallyMissing).chunked(400).forEach { chunk ->
            val where = "${t.col} IN (${chunk.joinToString(",") { "?" }})" + (t.keepWhere?.let { " $it" } ?: "")
            deleted += sql.delete(t.table, where, chunk.toTypedArray())
        }
        // ďalší deň pokračuj za touto dávkou (zmazané riadky posunuli zvyšok dopredu); na konci od začiatku
        val next = if (paths.size < CLEANUP_BATCH) 0 else offset + paths.size - deleted
        prefs.edit().putInt(offKey, next).apply()
    }

    // bez prístupu ku VŠETKÝM fotkám a videám (napr. „len vybrané fotky") File.exists() vracia
    // false aj pre existujúce súbory — upratovanie by mazalo platné riadky
    private fun canReadAllMedia(context: Context): Boolean = try {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)

            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    } catch (e: Throwable) {
        false
    }

    // všetky cesty fotiek a videí, ktoré pozná MediaStore (null = nedá sa zistiť -> neupratuj)
    private fun mediaStorePaths(context: Context): HashSet<String>? {
        val set = HashSet<String>()
        try {
            for (uri in arrayOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
                val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                    ?: return null
                cursor.use {
                    val col = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    while (it.moveToNext()) {
                        it.getString(col)?.let { p -> set.add(p) }
                    }
                }
            }
        } catch (e: Throwable) {
            return null
        }
        return set
    }

    // sonda: keď súborové API tvrdí, že neexistujú fotky, ktoré MediaStore pozná, je práve nespoľahlivé
    // (povolenie, nepripojené úložisko, FUSE) — vtedy sa neupratuje nič
    private fun fileApiReliable(known: Set<String>): Boolean {
        val step = (known.size / PROBE_SAMPLE).coerceAtLeast(1)
        var checked = 0
        var missing = 0
        for ((i, path) in known.withIndex()) {
            if (i % step != 0) continue
            checked++
            if (!File(path).exists()) missing++
            if (checked >= PROBE_SAMPLE) break
        }
        return checked > 0 && missing * 10 <= checked
    }

    // cesta leží na pripojenom úložisku? (odpojená SD karta = súbor nemusí byť zmazaný)
    private fun volumeMounted(storage: StorageManager?, path: String): Boolean = try {
        val state = storage?.getStorageVolume(File(path))?.state
        state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
    } catch (e: Throwable) {
        false
    }

    private inline fun safe(block: () -> Int): Int = try {
        block()
    } catch (e: Throwable) {
        0
    }
}
