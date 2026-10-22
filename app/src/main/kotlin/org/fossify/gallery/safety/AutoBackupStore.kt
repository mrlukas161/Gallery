package org.fossify.gallery.safety

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// Priečinok automatických záloh Documents/Galeria+/zalohy/ (A3). Súbory v Documents prežijú
// odinštalovanie appky.
//  - Android 10+: cez MediaStore (Files, RELATIVE_PATH). Zápis ide do položky s IS_PENDING = 1, ktorú
//    systém drží ako dočasný súbor (na Androide 11+ fyzicky „.pending-…“), a až po úspešnom zápise sa
//    IS_PENDING = 0 → premenovanie na výsledný názov. Neúspešná položka sa zahodí.
//  - Android 8–9: priamo súbor `<názov>.tmp` a renameTo.
// Po preinštalovaní appka cudzie (staré) súbory cez MediaStore nevidí; s prístupom ku všetkým
// súborom ich nájde priamo v priečinku, inak ostáva ručný výber súboru (Obnoviť osoby).
// Všetko blokuje → volať len z pozadia.
class AutoBackupStore(context: Context) {
    private val app = context.applicationContext

    data class Item(
        val name: String,
        val uri: Uri,
        val createdAt: Long,
        val kind: AutoBackupNames.Kind,
        val file: File?,
    )

    fun list(): List<Item> {
        val byName = LinkedHashMap<String, Item>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStore().forEach { byName[it.name] = it }
        }
        listDirectory().forEach { if (!byName.containsKey(it.name)) byName[it.name] = it }
        return byName.values.sortedByDescending { it.createdAt }
    }

    @Throws(IOException::class)
    fun <T> write(name: String, mime: String, writer: (OutputStream) -> T): T =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeMediaStore(name, mime, writer)
        } else {
            writeLegacy(name, writer)
        }

    @Throws(IOException::class)
    fun open(item: Item): InputStream =
        app.contentResolver.openInputStream(item.uri) ?: throw IOException("záloha sa nedá otvoriť")

    // rotácia: false = súbor sa nedal zmazať (napr. patrí staršej inštalácii) — ostane
    fun delete(item: Item): Boolean {
        item.file?.let { if (it.exists() && it.delete()) return true }
        if (item.uri.scheme != "content") return false
        return try {
            app.contentResolver.delete(item.uri, null, null) > 0
        } catch (e: SecurityException) {
            Log.w(TAG, "starú zálohu ${item.name} sa nepodarilo zmazať", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Suppress("TooGenericExceptionCaught")
    private fun queryMediaStore(): List<Item> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val rows = ArrayList<Pair<Long, String>>()
        try {
            app.contentResolver.query(collection, projection, selection, arrayOf(AutoBackupNames.RELATIVE_DIR), null)
                ?.use { c ->
                    while (c.moveToNext()) rows.add(c.getLong(0) to c.getString(1).orEmpty())
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "zoznam záloh z MediaStore sa nepodarilo načítať", e)
        }
        return rows.mapNotNull { (id, name) ->
            AutoBackupNames.parse(name)?.let {
                Item(name, ContentUris.withAppendedId(collection, id), it.createdAt, it.kind, null)
            }
        }
    }

    private fun listDirectory(): List<Item> {
        val dir = directory()
        val files = try {
            dir.listFiles()?.toList().orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "priečinok záloh sa nedá prečítať", e)
            emptyList()
        }
        return files.filter { it.isFile && it.canRead() }.mapNotNull { f ->
            val parsed = AutoBackupNames.parse(f.name) ?: return@mapNotNull null
            Item(f.name, Uri.fromFile(f), parsed.createdAt, parsed.kind, f)
        }
    }

    @Suppress("DEPRECATION")
    private fun directory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        AutoBackupNames.DIR_NAME + File.separator + AutoBackupNames.SUB_DIR,
    )

    @RequiresApi(Build.VERSION_CODES.Q)
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private fun <T> writeMediaStore(name: String, mime: String, writer: (OutputStream) -> T): T {
        val resolver = app.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, AutoBackupNames.RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("položku zálohy sa nepodarilo vytvoriť")
        try {
            val result = (resolver.openOutputStream(uri, "w") ?: throw IOException("zálohu sa nepodarilo otvoriť"))
                .use { writer(it) }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, done, null, null) <= 0) throw IOException("zálohu sa nepodarilo dokončiť")
            return result
        } catch (e: IOException) {
            discard(uri)
            throw e
        } catch (e: RuntimeException) {
            discard(uri)
            throw IOException(e)
        }
    }

    // nedokončená (dočasná) položka sa zahodí, staré zálohy ostávajú
    private fun discard(uri: Uri) {
        try {
            app.contentResolver.delete(uri, null, null)
        } catch (ignored: RuntimeException) {
        }
    }

    @Suppress("ThrowsCount")
    private fun <T> writeLegacy(name: String, writer: (OutputStream) -> T): T {
        val dir = directory()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("priečinok záloh sa nedá vytvoriť")
        val tmp = File(dir, "$name.tmp")
        val target = File(dir, name)
        try {
            val result = FileOutputStream(tmp).use { out ->
                writer(out).also {
                    out.flush()
                    out.fd.sync()
                }
            }
            if (!tmp.renameTo(target)) throw IOException("zálohu sa nepodarilo premenovať")
            return result
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        private const val TAG = "AutoBackupStore"
    }
}
