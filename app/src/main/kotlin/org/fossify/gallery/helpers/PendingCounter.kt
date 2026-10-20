package org.fossify.gallery.helpers

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import org.fossify.gallery.clip.ClipDatabase
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.GeoDatabase
import org.fossify.gallery.faces.IndexScope
import org.fossify.gallery.faces.OcrDatabase
import org.fossify.gallery.faces.PhashDatabase
import org.fossify.gallery.faces.QrDatabase
import org.fossify.gallery.services.IndexingService

// Koľko fotiek čaká na jednotlivé indexery — veľkosť dávky pre ProcessingPolicy pri voľbe
// „Nové fotky hneď, zvyšok pri nabíjaní“. Počíta rovnako ako indexery: fotky z MediaStore
// v rozsahu spracovania (bez vylúčených a zamknutých priečinkov), ktoré ešte nie sú v indexe.
// Volá sa len na pozadí (MediaStore + Room). Kľúče sú názvy úloh IndexingService.TASK_*.
object PendingCounter {
    fun count(context: Context, tasks: Collection<String>): Map<String, Int> {
        val appCtx = context.applicationContext
        val scope = IndexScope(appCtx)
        val images = queryPaths(appCtx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).filter { scope.allows(it) }
        val result = HashMap<String, Int>()
        for (task in tasks) {
            val indexed = indexedPaths(appCtx, task) ?: continue
            val candidates = if (task == IndexingService.TASK_GEO) {
                images + queryPaths(appCtx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).filter { scope.allows(it) }
            } else {
                images
            }
            result[task] = candidates.count { it !in indexed }
        }
        return result
    }

    // null = indexer nepoznáme alebo sa DB nedala prečítať (dávka ostane neznáma)
    private fun indexedPaths(context: Context, task: String): Set<String>? = try {
        when (task) {
            IndexingService.TASK_FACES -> FacesDatabase.getInstance(context).FaceDao().getProcessedPaths()
            IndexingService.TASK_GEO -> GeoDatabase.getInstance(context).GeoDao().getIndexedPaths()
            IndexingService.TASK_QR -> QrDatabase.getInstance(context).QrDao().getIndexedPaths()
            IndexingService.TASK_OCR -> OcrDatabase.getInstance(context).OcrDao().getIndexedPaths()
            IndexingService.TASK_PHASH -> PhashDatabase.getInstance(context).PhashDao().getIndexedPaths()
            IndexingService.TASK_CLIP -> ClipDatabase.getInstance(context).ClipDao().getIndexedPaths()
            else -> null
        }?.toHashSet()
    } catch (ignored: Throwable) {
        null
    }

    private fun queryPaths(context: Context, uri: Uri): List<String> = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { readPaths(it) }
            .orEmpty()
    } catch (ignored: Throwable) {
        emptyList()
    }

    private fun readPaths(cursor: Cursor): List<String> {
        val list = ArrayList<String>(cursor.count)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
        while (cursor.moveToNext()) {
            cursor.getString(dataCol)?.let { list.add(it) }
        }
        return list
    }
}
