package org.fossify.gallery.sync

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.faces.IndexScope
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.TYPE_GIFS
import org.fossify.gallery.helpers.TYPE_IMAGES
import org.fossify.gallery.helpers.TYPE_VIDEOS
import org.fossify.gallery.models.Medium
import java.io.File

// Mazanie z PC porovnávača priamo v mobile — rovnaký výsledok ako pri mazaní v Galérii+
// (movePathsInRecycleBin): pri zapnutom Koši kópia do Koša Galérie+ a záznam v jej DB,
// potom zmazanie originálu; pri vypnutom Koši natrvalo.
// Beží v službe servera bez UI -> potrebuje „Prístup ku všetkým súborom“ (inak Android pri fotkách
// iných appiek, napr. Fotoaparátu, vyžaduje potvrdenie v mobile).
object PcDeleter {
    data class Result(val deleted: List<Long>, val failed: Map<Long, String>, val toRecycleBin: Boolean)

    // rezerva voľného miesta v cieli Koša, aby kopírovanie nezaplnilo telefón do nuly
    private const val BIN_SPACE_RESERVE = 50L * 1024 * 1024

    fun canDelete(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun delete(context: Context, items: List<MediaServer.Item>): Result {
        val useBin = context.config.useRecycleBin
        val deleted = ArrayList<Long>()
        val failed = LinkedHashMap<Long, String>()
        // cesty známe v DB Galérie+ po priečinkoch, načítané raz na priečinok (celý Camera naraz do pamäte
        // pre každú fotku by pri dávke zbytočne zaťažil telefón)
        val knownByParent = HashMap<String, HashSet<String>>()
        for (item in items) {
            try {
                val file = File(item.path)
                if (item.path.isEmpty() || !file.exists()) {
                    failed[item.id] = "missing"
                    continue
                }
                var binFile: File? = null
                if (useBin) {
                    val target = File(context.recycleBinPath, item.path)
                    val lastModified = file.lastModified()
                    // veľkosť zdroja raz pred kopírovaním — porovnáva sa s ňou aj pri zmene originálu počas kopírovania
                    val size = file.length()
                    // plné úložisko: kopírovanie by zlyhalo uprostred a v Koši by ostala čiastočná kópia
                    if (!IndexDiff.hasRoomForCopy(freeSpaceAt(File(context.recycleBinPath)), size, BIN_SPACE_RESERVE)) {
                        failed[item.id] = "no space"
                        continue
                    }
                    // akékoľvek zlyhanie (výnimka, false, nezhoda veľkosti) zmaže čiastočnú kópiu; originál sa nedotkne
                    val copied = try {
                        file.copyRecursively(target, true) && target.length() == size
                    } catch (e: Throwable) {
                        false
                    }
                    if (!copied) {
                        target.delete()
                        failed[item.id] = "copy failed"
                        continue
                    }
                    if (context.config.keepLastModified && lastModified != 0L) target.setLastModified(lastModified)
                    val row = RowInfo(item.path, item.name, item.mime, item.taken, item.id)
                    ensureMediumRow(context, row, file, knownByParent)
                    context.mediaDB.updateDeleted("$RECYCLE_BIN${item.path}", System.currentTimeMillis(), item.path)
                    binFile = target
                }
                if (!file.delete() && file.exists()) {
                    // originál sa nedal zmazať -> vrátiť Kôš do pôvodného stavu, nech fotka nie je dvakrát
                    binFile?.delete()
                    if (useBin) context.mediaDB.updateDeleted(item.path, 0, "$RECYCLE_BIN${item.path}")
                    failed[item.id] = "delete failed"
                    continue
                }
                try {
                    context.contentResolver.delete(item.uri, null, null)
                } catch (ignored: Throwable) {
                }
                if (!useBin) context.deleteDBPath(item.path)
                deleted.add(item.id)
            } catch (e: Throwable) {
                failed[item.id] = (e.message ?: e.javaClass.simpleName).take(120)
            }
        }
        return Result(deleted, failed, useBin)
    }

    // voľné miesto na najbližšom existujúcom predkovi (priečinok Koša sa zakladá až pri prvom kopírovaní);
    // -1 = nedá sa zistiť -> kontrola sa preskočí
    internal fun freeSpaceAt(dir: File): Long {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return d?.usableSpace ?: -1L
    }

    internal class RowInfo(
        val path: String,
        val name: String,
        val mime: String,
        val taken: Long,
        val mediaStoreId: Long,
    )

    // Pre mazanie potvrdené v mobile (MainActivity -> movePathsInRecycleBin): fotky, ktoré Galéria+ ešte
    // nemala v DB, by sa v Koši neukázali — riadky treba založiť vopred. Volať mimo hlavného vlákna.
    fun ensureMediumRows(context: Context, paths: Collection<String>) {
        val knownByParent = HashMap<String, HashSet<String>>()
        for (path in paths) {
            try {
                val file = File(path)
                if (!file.exists()) continue
                val ext = path.substringAfterLast('.', "").lowercase()
                val mime = when {
                    ext in VIDEO_EXT -> "video/$ext"
                    ext == "gif" -> "image/gif"
                    else -> "image/$ext"
                }
                ensureMediumRow(context, RowInfo(path, file.name, mime, file.lastModified(), 0L), file, knownByParent)
            } catch (ignored: Throwable) {
            }
        }
    }

    private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "3gp", "webm", "avi", "m4v")

    // fotka, ktorú Galéria+ ešte nemala v DB (napr. práve odfotená a v appke neotvorená), by po presune
    // do Koša v Koši nebola vidno — preto riadok najprv založiť
    private fun ensureMediumRow(
        context: Context,
        row: RowInfo,
        file: File,
        knownByParent: HashMap<String, HashSet<String>>,
    ) {
        val parent = row.path.getParentPath()
        val known = knownByParent.getOrPut(parent) {
            context.mediaDB.getMediaFromPath(parent).mapTo(HashSet()) { it.path }
        }
        if (!known.add(row.path)) return
        val type = when {
            row.mime.startsWith("video/") -> TYPE_VIDEOS
            row.mime == "image/gif" -> TYPE_GIFS
            else -> TYPE_IMAGES
        }
        val medium = Medium(
            null, row.name, row.path, parent, file.lastModified(), row.taken, file.length(),
            type, 0, false, 0L, row.mediaStoreId,
        )
        context.mediaDB.insert(medium)
    }
}

// Kôš Galérie+ pre web (bez Activity): zoznam a obnova. Rovnaká logika ako restoreRecycleBinPaths
// (extensions/Activity.kt): kópia z Koša na pôvodné miesto (pri obsadenom názve foto_1.jpg), overenie
// veľkosti, riadok v DB späť na pôvodnú cestu s deleted_ts = 0 a sken do MediaStore. Navyše sa po
// overenej kópii zmaže súbor v Koši (inak by v ňom ostal navždy a zaberal miesto). Kam sa nedá
// zapisovať (Android/data…), ide fotka do Obrázkov ako v appke. Potrebuje „Prístup ku všetkým súborom“.
object PcBin {
    data class BinItem(
        val key: String,
        val origPath: String,
        val binFile: File,
        val name: String,
        val size: Long,
        val deletedTs: Long,
        val type: Int,
    )

    data class RestoreResult(val restored: List<String>, val failed: Map<String, String>)

    private const val SPACE_RESERVE = 50L * 1024 * 1024

    // zamknutý Kôš (PIN/heslo na priečinku) sa na webe neukáže
    fun isLocked(context: Context): Boolean = try {
        context.config.isFolderProtected(RECYCLE_BIN) || context.config.isFolderProtected(context.recycleBinPath)
    } catch (ignored: Throwable) {
        true
    }

    fun list(context: Context): List<BinItem> {
        if (isLocked(context)) return emptyList()
        val scope = IndexScope(context)
        val root = context.recycleBinPath
        val rows = try {
            context.mediaDB.getDeletedMedia()
        } catch (ignored: Throwable) {
            emptyList()
        }
        return rows.mapNotNull { m ->
            val orig = m.path.removePrefix(RECYCLE_BIN)
            val f = File(root, orig)
            // zamknuté a vylúčené priečinky ani v Koši nesprístupniť
            if (orig.startsWith("/") && scope.allows(orig) && f.isFile) {
                BinItem(ETags.hash(orig), orig, f, m.name, f.length(), m.deletedTS, m.type)
            } else {
                null
            }
        }.sortedByDescending { it.deletedTs }
    }

    fun restore(context: Context, items: List<BinItem>): RestoreResult {
        val restored = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        val scanned = ArrayList<String>()
        for (item in items) {
            val msg = try {
                val dest = restoreOne(context, item)
                if (dest != null) {
                    scanned.add(dest)
                    restored.add(item.key)
                    null
                } else {
                    "copy failed"
                }
            } catch (e: SecurityException) {
                e.message ?: "denied"
            } catch (e: java.io.IOException) {
                e.message ?: "io"
            } catch (ignored: Throwable) {
                "error"
            }
            if (msg != null) failed[item.key] = msg.take(MSG_MAX)
        }
        if (scanned.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(context, scanned.toTypedArray(), null, null)
            } catch (ignored: Throwable) {
            }
        }
        return RestoreResult(restored, failed)
    }

    private const val MSG_MAX = 120

    // cieľová cesta po obnove alebo null pri zlyhaní (čiastočná kópia sa zmaže)
    private fun restoreOne(context: Context, item: BinItem): String? {
        val src = item.binFile
        if (!src.isFile) return null
        val size = src.length()
        val dest = target(item.origPath) ?: return null
        val destFile = File(dest)
        if (!IndexDiff.hasRoomForCopy(PcDeleter.freeSpaceAt(destFile.parentFile ?: destFile), size, SPACE_RESERVE)) {
            throw java.io.IOException("no space")
        }
        val ok = try {
            src.copyTo(destFile, overwrite = false).length() == size
        } catch (ignored: java.io.IOException) {
            false
        }
        if (!ok) {
            destFile.delete()
            return null
        }
        if (context.config.keepLastModified && src.lastModified() != 0L) destFile.setLastModified(src.lastModified())
        context.mediaDB.updateDeleted(dest, 0, "$RECYCLE_BIN${item.origPath}")
        src.delete()
        return dest
    }

    private fun writableDir(dir: File) = (dir.isDirectory || dir.mkdirs()) && dir.canWrite()

    // pôvodný priečinok (založí sa), inak Obrázky; obsadený názov -> foto_1.jpg
    private fun target(orig: String): String? {
        val parent = File(orig).parentFile
        val dir = if (parent != null && writableDir(parent)) {
            parent
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).also { it.mkdirs() }
        }
        return AltName.free(File(dir, File(orig).name).path) { File(it).exists() }
    }
}
