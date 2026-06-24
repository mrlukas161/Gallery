package org.fossify.gallery.faces

import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.fossify.commons.helpers.ensureBackgroundThread

// Prečíta GPS z EXIF každej fotky (rýchle – len hlavička) a uloží do geo.db. Resumovateľné.
object GeoIndexer {
    @Volatile
    var isRunning = false
        private set

    fun index(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (indexed: Int, geotagged: Int) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            try {
                val dao = GeoDatabase.getInstance(appCtx).GeoDao()
                val processed = dao.getIndexedPaths().toHashSet()
                val todo = queryImages(appCtx).filter { it !in processed }
                val total = todo.size
                var done = 0
                for (path in todo) {
                    if (!isRunning) break
                    var lat = 0.0
                    var lon = 0.0
                    var has = false
                    try {
                        val exif = ExifInterface(path)
                        val ll = FloatArray(2)
                        if (exif.getLatLong(ll) && (ll[0] != 0f || ll[1] != 0f)) {
                            lat = ll[0].toDouble()
                            lon = ll[1].toDouble()
                            has = true
                        }
                    } catch (ignored: Throwable) {
                    }
                    try {
                        dao.insert(GeoEntity(path, lat, lon, has))
                    } catch (ignored: Throwable) {
                    }
                    done++
                    if (done % 20 == 0 || done == total) onProgress(done, total)
                }
                onDone(safe { dao.count() }, safe { dao.countGeotagged() })
            } catch (e: Throwable) {
                onError(e.javaClass.simpleName + (e.message?.let { ": " + it.take(160) } ?: ""))
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    private inline fun safe(block: () -> Int): Int = try {
        block()
    } catch (e: Throwable) {
        0
    }

    private fun queryImages(context: Context): List<String> {
        val list = ArrayList<String>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null,
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (cursor.moveToNext()) {
                    cursor.getString(dataCol)?.let { list.add(it) }
                }
            }
        } catch (ignored: Throwable) {
        }
        return list
    }
}
