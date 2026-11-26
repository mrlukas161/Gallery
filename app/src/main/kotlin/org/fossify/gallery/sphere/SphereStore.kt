package org.fossify.gallery.sphere

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.fossify.gallery.spherecore.SphereSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Relácie snímania v `Android/data/<balík>/files/sfera/<id>/`. */
object SphereStore {
    const val MARKER_STITCHED = "stitched.txt"
    const val TMP_PANO = "pano_tmp.jpg"

    fun root(context: Context): File {
        val base = context.getExternalFilesDir("sfera") ?: File(context.filesDir, "sfera")
        if (!base.exists()) base.mkdirs()
        return base
    }

    /** Nový priečinok relácie `yyyyMMdd_HHmmss` (pri zhode s existujúcim sa pridá prípona). */
    fun newSessionDir(context: Context): File {
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var dir = File(root(context), id)
        var n = 1
        while (dir.exists()) dir = File(root(context), "${id}_${n++}")
        dir.mkdirs()
        return dir
    }

    /** Všetky relácie, najnovšia prvá. */
    fun list(context: Context): List<File> =
        root(context).listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun totalSize(context: Context): Long = list(context).sumOf { sizeOf(it) }

    /** Zmaže prázdne priečinky relácií (po zlyhanom prvom zábere). */
    fun purgeEmpty(context: Context) {
        for (d in list(context)) if (d.listFiles().isNullOrEmpty()) d.delete()
    }

    fun delete(dir: File) {
        dir.deleteRecursively()
    }

    /** Zmaže všetky relácie okrem `keep`; vráti počet zmazaných. */
    fun deleteAll(context: Context, keep: Set<String> = emptySet()): Int {
        var n = 0
        for (d in list(context)) {
            if (d.absolutePath in keep) continue
            d.deleteRecursively()
            n++
        }
        return n
    }

    fun load(dir: File): SphereSession? = try {
        SphereSession.load(dir)
    } catch (e: Throwable) {
        null
    }

    /** Výsledná guľa už bola zložená (služba zapísala značku). */
    fun isStitched(dir: File): Boolean = File(dir, MARKER_STITCHED).exists()

    /** Najnovšia relácia, ktorá má aspoň 2 zábery a nebola zložená (rozrobená alebo ukončená bez výsledku). */
    fun findResumable(context: Context, exclude: Set<String> = emptySet()): Pair<File, SphereSession>? {
        for (d in list(context)) {
            if (d.absolutePath in exclude || isStitched(d)) continue
            val s = load(d) ?: continue
            if (s.frames.size >= 2) return d to s
        }
        return null
    }

    private val ZIP_NAMES = Regex("""f_\d+\.jpg|session\.json|stitch_log\.txt""")

    /**
     * Zabalí zdrojové zábery do ZIP v priečinku Stiahnuté (`Sfera_<id>.zip`). Vráti názov súboru, null pri chybe.
     * Beží na vlákne volajúceho (volať mimo hlavného).
     */
    fun exportZip(context: Context, dir: File): String? {
        val name = "Sfera_${dir.name}.zip"
        return try {
            val files = dir.listFiles { f -> f.isFile && ZIP_NAMES.matches(f.name) }?.sortedBy { it.name } ?: return null
            if (files.isEmpty()) return null
            fun write(out: java.io.OutputStream) {
                ZipOutputStream(out.buffered()).use { z ->
                    z.setLevel(0) // JPEG sa už nestlačí
                    for (f in files) {
                        z.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
                try {
                    context.contentResolver.openOutputStream(uri)?.use { write(it) } ?: return null
                    cv.clear()
                    cv.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(uri, cv, null, null)
                } catch (e: Throwable) {
                    context.contentResolver.delete(uri, null, null)
                    return null
                }
            } else {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dl.mkdirs()
                File(dl, name).outputStream().use { write(it) }
            }
            name
        } catch (e: Throwable) {
            null
        }
    }
}
