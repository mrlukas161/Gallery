package org.fossify.gallery.compare

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Údaje o jednej fotke pre štítok panela a porovnanie EXIF (načíta PhotoInfoLoader).
// taken = čas snímky v ms (EXIF DateTimeOriginal, inak čas zmeny súboru), width × height vzpriamene.
data class PhotoInfo(
    val path: String,
    val taken: Long,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val modified: Long,
    val iso: Int? = null,
    val exposureTime: Double? = null,
    val fNumber: Double? = null,
    val focalLength: Double? = null,
    val camera: String? = null,
    val hasGps: Boolean = false,
    val orientation: Int = 1,
) {
    val pixels: Long get() = width.toLong() * height
    val aspect: Float get() = if (height > 0) width.toFloat() / height else 0f
}

// Druh priečinka pre čitateľný názov („Fotoaparát“) — preklad mien robí Android vrstva.
enum class FolderKind { CAMERA, SCREENSHOTS, WHATSAPP, DOWNLOAD, OTHER }

// Slovenské formáty štítkov porovnávača bez Android tried (CompareFormatTest): „12 Mpx“, „3,2 MB“,
// „14:05:32“, „3. 6. 2024“, „1/120 s“, „f/1,8“, „26 mm“.
object CompareFormat {
    const val SEPARATOR = " · "
    private const val KB = 1000.0
    private const val MB = 1_000_000.0
    private const val GB = 1_000_000_000.0
    private const val MPX = 1_000_000.0
    private const val ONE_DECIMAL_BELOW = 10.0
    private const val TEN = 10L
    private val SK = Locale.forLanguageTag("sk")

    fun megapixels(width: Int, height: Int): String {
        val mpx = width.toLong() * height / MPX
        return "${decimal(mpx)} Mpx"
    }

    fun size(bytes: Long): String = when {
        bytes >= GB -> "${decimal(bytes / GB)} GB"
        bytes >= MB -> "${decimal(bytes / MB)} MB"
        bytes >= KB -> "${(bytes / KB).roundToInt()} kB"
        else -> "${bytes.coerceAtLeast(0)} B"
    }

    // pod 10 s jedným desatinným miestom (desatinná čiarka), inak celé číslo
    fun decimal(value: Double): String {
        if (value >= ONE_DECIMAL_BELOW) return value.roundToLong().toString()
        val tenths = (value * TEN).roundToLong()
        return if (tenths % TEN == 0L) (tenths / TEN).toString() else "${tenths / TEN},${tenths % TEN}"
    }

    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm:ss", SK).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun date(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("d. M. yyyy", SK).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    // „1/120 s“ pod sekundu, inak „2 s“ / „0,5 s“
    fun exposureTime(seconds: Double): String =
        if (seconds > 0.0 && seconds < 1.0) "1/${(1.0 / seconds).roundToInt()} s" else "${decimal(seconds)} s"

    fun aperture(f: Double): String = "f/${decimal(f)}"

    fun focal(mm: Double): String = "${decimal(mm)} mm"

    fun folderKind(path: String): FolderKind {
        val segments = path.lowercase().substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        val last = segments.lastOrNull().orEmpty()
        return when {
            segments.any { it.startsWith("whatsapp") } -> FolderKind.WHATSAPP
            last.startsWith("screenshot") -> FolderKind.SCREENSHOTS
            last == "download" || last == "downloads" -> FolderKind.DOWNLOAD
            last == "dcim" || last.endsWith("camera") -> FolderKind.CAMERA
            else -> FolderKind.OTHER
        }
    }
}
