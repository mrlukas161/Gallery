package org.fossify.gallery.sync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

// Zapíše novú fotku/video DO MediaStore (scoped storage, API29+) cez OutputStream.
// Obrázky -> Pictures/Galéria, videá -> Movies/Galéria. Vráti Pair(zobrazovaný názov, uri) alebo null.
// Pri kolízii mena MediaStore sám pridá príponu "(1)" — nikdy neprepíše existujúcu fotku používateľa.
fun saveMediaScoped(
    context: Context,
    displayName: String,
    mimeType: String,
    subDir: String,
    write: (OutputStream) -> Unit,
): Pair<String, String>? {
    val isVideo = mimeType.startsWith("video/")
    val collection: Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    val relBase = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relBase/$subDir")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val itemUri = resolver.insert(collection, values) ?: return null
    return try {
        resolver.openOutputStream(itemUri)?.use { os -> write(os) }
            ?: run {
                resolver.delete(itemUri, null, null)
                return null
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        }
        displayName to itemUri.toString()
    } catch (e: Exception) {
        resolver.delete(itemUri, null, null)
        null
    }
}
