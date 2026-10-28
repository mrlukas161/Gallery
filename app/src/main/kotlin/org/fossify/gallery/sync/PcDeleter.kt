package org.fossify.gallery.sync

import android.content.Context
import android.os.Build
import android.os.Environment
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.mediaDB
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

    fun canDelete(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun delete(context: Context, items: List<MediaServer.Item>): Result {
        val useBin = context.config.useRecycleBin
        val deleted = ArrayList<Long>()
        val failed = LinkedHashMap<Long, String>()
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
                    if (!file.copyRecursively(target, true) || target.length() != file.length()) {
                        target.delete()
                        failed[item.id] = "copy failed"
                        continue
                    }
                    if (context.config.keepLastModified && lastModified != 0L) target.setLastModified(lastModified)
                    ensureMediumRow(context, item, file)
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

    // fotka, ktorú Galéria+ ešte nemala v DB (napr. práve odfotená a v appke neotvorená), by po presune
    // do Koša v Koši nebola vidno — preto riadok najprv založiť
    private fun ensureMediumRow(context: Context, item: MediaServer.Item, file: File) {
        val parent = item.path.getParentPath()
        if (context.mediaDB.getMediaFromPath(parent).any { it.path == item.path }) return
        val type = when {
            item.mime.startsWith("video/") -> TYPE_VIDEOS
            item.mime == "image/gif" -> TYPE_GIFS
            else -> TYPE_IMAGES
        }
        context.mediaDB.insert(
            Medium(null, item.name, item.path, parent, file.lastModified(), item.taken, file.length(), type, 0, false, 0L, item.id)
        )
    }
}
