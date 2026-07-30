package org.fossify.gallery.helpers

import android.content.Context

// Jedno miesto, kde sa dá zistiť priebeh KAŽDÉHO indexera (0–100 %) — pre prehľadné Nastavenia.
// Číta reálne počty z databáz vs. počet fotiek v knižnici; percentá sa počítajú rovnako pre všetky
// funkcie, takže ukazovateľ je konzistentný.
object IndexStatus {

    data class Item(val key: String, val titleRes: Int, val done: Int, val total: Int, val running: Boolean) {
        val percent: Int = if (total <= 0) 0 else ((done.toLong() * 100 / total).toInt()).coerceIn(0, 100)
        val complete: Boolean = total > 0 && done >= total
    }

    fun photoCount(context: Context): Int = try {
        context.contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media._ID), null, null, null,
        )?.use { it.count } ?: 0
    } catch (e: Throwable) {
        0
    }

    fun all(context: Context, totalPhotos: Int): List<Item> {
        val list = ArrayList<Item>(6)
        list.add(
            Item(
                "faces", org.fossify.gallery.R.string.indexing_faces,
                safe { org.fossify.gallery.faces.FacesDatabase.getInstance(context).FaceDao().getProcessedCount() },
                totalPhotos, org.fossify.gallery.faces.FaceIndexer.isRunning,
            )
        )
        list.add(
            Item(
                "ocr", org.fossify.gallery.R.string.indexing_ocr,
                safe { org.fossify.gallery.faces.OcrDatabase.getInstance(context).OcrDao().count() },
                totalPhotos, org.fossify.gallery.faces.OcrIndexer.isRunning,
            )
        )
        list.add(
            Item(
                "geo", org.fossify.gallery.R.string.indexing_geo,
                safe { org.fossify.gallery.faces.GeoDatabase.getInstance(context).GeoDao().count() },
                totalPhotos, org.fossify.gallery.faces.GeoIndexer.isRunning,
            )
        )
        list.add(
            Item(
                "qr", org.fossify.gallery.R.string.indexing_qr,
                safe { org.fossify.gallery.faces.QrDatabase.getInstance(context).QrDao().count() },
                totalPhotos, org.fossify.gallery.faces.QrIndexer.isRunning,
            )
        )
        list.add(
            Item(
                "phash", org.fossify.gallery.R.string.indexing_phash,
                safe { org.fossify.gallery.faces.PhashDatabase.getInstance(context).PhashDao().count() },
                totalPhotos, org.fossify.gallery.faces.PhashIndexer.isRunning,
            )
        )
        if (org.fossify.gallery.clip.ClipModels.bothPresent(context)) {
            list.add(
                Item(
                    "clip", org.fossify.gallery.R.string.indexing_clip,
                    safe { org.fossify.gallery.clip.ClipDatabase.getInstance(context).ClipDao().count() },
                    totalPhotos, org.fossify.gallery.clip.ClipIndexer.isRunning,
                )
            )
        }
        return list
    }

    // celkové percento naprieč všetkými funkciami
    fun overallPercent(items: List<Item>): Int {
        if (items.isEmpty()) return 0
        return items.sumOf { it.percent } / items.size
    }

    private inline fun safe(block: () -> Int): Int = try {
        block()
    } catch (e: Throwable) {
        0
    }
}
