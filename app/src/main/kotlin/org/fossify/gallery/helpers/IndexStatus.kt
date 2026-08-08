package org.fossify.gallery.helpers

import android.content.Context

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
    private const val CLEANUP_BATCH = 2000

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

    // Lacné upratanie indexov: raz denne na pozadí zmaž z indexových DB riadky fotiek, ktoré už
    // na disku neexistujú (nikde inde sa riadky nemažú — počty by navždy prevyšovali realitu).
    // Kontroluje sa max CLEANUP_BATCH ciest naraz, takže beh je krátky aj na obrích knižniciach;
    // zvyšok príde na rad pri ďalších dňoch.
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
                    cleanupIndexes(appCtx)
                } catch (ignored: Throwable) {
                } finally {
                    cleanupRunning = false
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    // prejde tabuľky indexov a zmaže riadky s neexistujúcou cestou (File.exists po dávkach)
    private fun cleanupIndexes(context: Context) {
        val databases: List<Pair<androidx.room.RoomDatabase, List<Pair<String, String>>>> = listOf(
            org.fossify.gallery.faces.FacesDatabase.getInstance(context) to listOf(
                "indexed_photos" to "path",
                "faces" to "media_full_path",
            ),
            org.fossify.gallery.faces.OcrDatabase.getInstance(context) to listOf("ocr_text" to "path"),
            org.fossify.gallery.faces.QrDatabase.getInstance(context) to listOf("qr_codes" to "path"),
            org.fossify.gallery.faces.GeoDatabase.getInstance(context) to listOf("geo" to "path"),
            org.fossify.gallery.faces.PhashDatabase.getInstance(context) to listOf("phash_index" to "path"),
            org.fossify.gallery.clip.ClipDatabase.getInstance(context) to listOf("clip_index" to "path"),
        )
        var checked = 0
        for ((db, tables) in databases) {
            for ((table, col) in tables) {
                if (checked >= CLEANUP_BATCH) return
                val missing = ArrayList<String>()
                try {
                    val sql = db.openHelper.writableDatabase
                    sql.query("SELECT DISTINCT $col FROM $table").use { cursor ->
                        while (cursor.moveToNext()) {
                            if (checked >= CLEANUP_BATCH) break
                            val path = cursor.getString(0) ?: continue
                            checked++
                            if (!java.io.File(path).exists()) missing.add(path)
                        }
                    }
                    if (missing.isNotEmpty()) {
                        missing.chunked(400).forEach { chunk ->
                            val placeholders = chunk.joinToString(",") { "?" }
                            sql.execSQL("DELETE FROM $table WHERE $col IN ($placeholders)", chunk.toTypedArray())
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private inline fun safe(block: () -> Int): Int = try {
        block()
    } catch (e: Throwable) {
        0
    }
}
