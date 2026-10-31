package org.fossify.gallery.compare

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import org.fossify.gallery.family.RelBox
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// Údaje o fotke pre štítok panela a porovnanie EXIF: len hlavička súboru a EXIF, bez dekódovania pixelov.
// Volať z vlákna na pozadí.
object PhotoInfoLoader {
    private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun load(path: String): PhotoInfo {
        val file = File(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val exif = try {
            ExifInterface(path)
        } catch (ignored: IOException) {
            null
        }
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val size = RelBox.uprightSize(
            bounds.outWidth.coerceAtLeast(0),
            bounds.outHeight.coerceAtLeast(0),
            RelBox.exifTransform(orientation).rotationDegrees,
        )
        val modified = file.lastModified()
        return PhotoInfo(
            path = path,
            taken = exif?.let { takenAt(it) } ?: modified,
            width = size[0],
            height = size[1],
            bytes = file.length(),
            modified = modified,
            iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 },
            exposureTime = exif?.positive(ExifInterface.TAG_EXPOSURE_TIME),
            fNumber = exif?.positive(ExifInterface.TAG_F_NUMBER),
            focalLength = exif?.positive(ExifInterface.TAG_FOCAL_LENGTH),
            camera = exif?.let { camera(it) },
            hasGps = exif?.latLong != null,
            orientation = orientation,
        )
    }

    private fun ExifInterface.positive(tag: String): Double? = getAttributeDouble(tag, 0.0).takeIf { it > 0.0 }

    // EXIF čas snímky je miestny čas bez zóny → berie sa v zóne telefónu (rovnako ho aj zobrazíme)
    private fun takenAt(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        return try {
            LocalDateTime.parse(raw.trim(), EXIF_DATE).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (ignored: DateTimeParseException) {
            null
        }
    }

    // „Xiaomi 15“ namiesto „Xiaomi Xiaomi 15“
    private fun camera(exif: ExifInterface): String? {
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        val name = if (make.isNotEmpty() && !model.startsWith(make, ignoreCase = true)) "$make $model" else model
        return name.trim().ifEmpty { null }
    }
}
