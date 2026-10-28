package org.fossify.gallery.faces

import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.fossify.gallery.family.BundleRules
import java.io.IOException

// E1 — dátum nasnímania fotky pre faces.taken_at (vekové prototypy v KnnModel): MediaStore DATE_TAKEN,
// inak EXIF DateTimeOriginal / DateTime. 0 = zistené, že dátum chýba (KnnModel vtedy použije váhu 1).
// Čas úpravy súboru sa NEpoužíva (kopírovanie alebo záloha ho mení a deti by „zostarli“ naraz).
// Volať len z pozadia.
object FaceTakenAt {
    const val UNKNOWN = 0L

    fun of(context: Context, path: String, mediaStoreId: Long): Long =
        fromMediaStore(context, path, mediaStoreId) ?: fromExif(path) ?: UNKNOWN

    // dátumy pre viac fotiek naraz (dopočítanie starých tvárí): jeden prechod MediaStore, EXIF len pre zvyšok
    fun batch(context: Context, paths: Collection<String>): Map<String, Long> {
        val wanted = paths.toHashSet()
        val out = HashMap<String, Long>()
        if (wanted.isEmpty()) return out
        val projection = arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_TAKEN)
        try {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)
                ?.use { c -> while (c.moveToNext()) readRow(c, wanted, out) }
        } catch (ignored: RuntimeException) {
            // bez MediaStore len EXIF
        }
        for (p in wanted) if (p !in out) out[p] = fromExif(p) ?: UNKNOWN
        return out
    }

    private fun readRow(c: Cursor, wanted: Set<String>, out: MutableMap<String, Long>) {
        val path = c.getString(0)
        if (path == null || path !in wanted || c.isNull(1)) return
        c.getLong(1).takeIf { it > 0L }?.let { out[path] = it }
    }

    private fun fromMediaStore(context: Context, path: String, mediaStoreId: Long): Long? = try {
        val byId = mediaStoreId > 0L
        val selection = if (byId) "${MediaStore.Images.Media._ID} = ?" else "${MediaStore.Images.Media.DATA} = ?"
        val args = arrayOf(if (byId) mediaStoreId.toString() else path)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DATE_TAKEN),
            selection,
            args,
            null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0L } else null }
    } catch (ignored: RuntimeException) {
        null
    }

    private fun fromExif(path: String): Long? = try {
        val exif = ExifInterface(path)
        BundleRules.parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            ?: BundleRules.parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME))
    } catch (ignored: IOException) {
        null
    } catch (ignored: RuntimeException) {
        null
    }
}
