package org.fossify.gallery.faces

import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.helpers.ensureBackgroundThread

// Prečíta GPS z EXIF každej fotky (rýchle – len hlavička) a z metadát videí (ISO6709) a uloží do
// geo.db. Typ (fotka/video) sa rozlišuje príponou cesty — bez zmeny schémy. Resumovateľné.
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
                val todo = (queryImages(appCtx) + queryVideos(appCtx)).filter { it !in processed }
                val total = todo.size
                var done = 0
                for (path in todo) {
                    if (!isRunning) break
                    var lat = 0.0
                    var lon = 0.0
                    var has = false
                    if (path.isVideoFast()) {
                        videoLatLon(path)?.let { (la, lo) ->
                            lat = la
                            lon = lo
                            has = true
                        }
                    } else try {
                        val exif = ExifInterface(path)
                        val ll = FloatArray(2)
                        if (exif.getLatLong(ll) && (ll[0] != 0f || ll[1] != 0f)) {
                            lat = ll[0].toDouble()
                            lon = ll[1].toDouble()
                            has = true
                        }
                    } catch (ignored: Throwable) {
                    }
                    // Zjavne chybné súradnice na mapu nedávame: telefón občas zapíše fix z Wi-Fi/siete
                    // (býva ďaleko od reality) alebo nulový/„ostrovný" bod pri chýbajúcom N/S/E/W.
                    if (has && !plausible(lat, lon)) has = false
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

    // hodnovernosť GPS bodu: mimo rozsahu, presná nula (Null Island) alebo „celé číslo bez desatín"
    // = takmer vždy chyba zápisu, nie skutočné miesto
    private fun plausible(lat: Double, lon: Double): Boolean {
        if (lat.isNaN() || lon.isNaN()) return false
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return false
        if (kotlin.math.abs(lat) < 0.0005 && kotlin.math.abs(lon) < 0.0005) return false
        return true
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

    private fun queryVideos(context: Context): List<String> {
        val list = ArrayList<String>()
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media.DATA),
                null, null, null,
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (cursor.moveToNext()) {
                    cursor.getString(dataCol)?.let { list.add(it) }
                }
            }
        } catch (ignored: Throwable) {
        }
        return list
    }

    // GPS z metadát videa: ISO6709 reťazec typu "+48.1465+017.1078/" (len hlavička, rýchle)
    private fun videoLatLon(path: String): Pair<Double, Double>? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            val loc = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: return null
            val m = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(loc) ?: return null
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return null
            if (lat == 0.0 && lon == 0.0) null else lat to lon
        } catch (e: Throwable) {
            null
        } finally {
            try {
                mmr.release()
            } catch (ignored: Throwable) {
            }
        }
    }
}
