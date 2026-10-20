package org.fossify.gallery.family

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

// RelBox má vlastné kópie hodnôt EXIF orientácie (aby bol bez Androidu) — musia sedieť s androidx.
class ExifConstantsTest {
    @Test
    fun orientationValuesMatchAndroidx() {
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, RelBox.EXIF_UNDEFINED)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, RelBox.EXIF_NORMAL)
        assertEquals(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, RelBox.EXIF_FLIP_HORIZONTAL)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_180, RelBox.EXIF_ROTATE_180)
        assertEquals(ExifInterface.ORIENTATION_FLIP_VERTICAL, RelBox.EXIF_FLIP_VERTICAL)
        assertEquals(ExifInterface.ORIENTATION_TRANSPOSE, RelBox.EXIF_TRANSPOSE)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, RelBox.EXIF_ROTATE_90)
        assertEquals(ExifInterface.ORIENTATION_TRANSVERSE, RelBox.EXIF_TRANSVERSE)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, RelBox.EXIF_ROTATE_270)
    }
}
