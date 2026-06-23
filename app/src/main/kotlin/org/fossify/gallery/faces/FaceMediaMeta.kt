package org.fossify.gallery.faces

import android.content.Context
import android.provider.MediaStore
import java.io.File

// Načíta metadáta fotiek (dátum vytvorenia/úpravy, veľkosť, názov) z MediaStore — pre jednotné triedenie.
object FaceMediaMeta {
    data class Meta(val taken: Long, val modified: Long, val size: Long, val name: String)

    fun load(context: Context, paths: Collection<String>): Map<String, Meta> {
        val needed = paths.toHashSet()
        val result = HashMap<String, Meta>()
        if (needed.isEmpty()) return result

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        try {
            context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                val dData = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val dTaken = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dMod = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val dSize = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                val dName = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val path = c.getString(dData) ?: continue
                    if (!needed.contains(path)) continue
                    val taken = if (dTaken >= 0) c.getLong(dTaken) else 0L
                    val mod = if (dMod >= 0) c.getLong(dMod) * 1000L else 0L
                    val size = if (dSize >= 0) c.getLong(dSize) else 0L
                    val name = if (dName >= 0) (c.getString(dName) ?: "") else ""
                    // ak chýba dátum vytvorenia, použijeme dátum úpravy (nech chronológia funguje vždy)
                    result[path] = Meta(if (taken > 0) taken else mod, mod, size, name)
                }
            }
        } catch (ignored: Throwable) {
        }
        // fallback pre cesty, ktoré MediaStore nevrátil
        for (p in needed) {
            if (!result.containsKey(p)) {
                val lm = try {
                    File(p).lastModified()
                } catch (e: Throwable) {
                    0L
                }
                result[p] = Meta(lm, lm, 0L, p.substringAfterLast('/'))
            }
        }
        return result
    }
}
