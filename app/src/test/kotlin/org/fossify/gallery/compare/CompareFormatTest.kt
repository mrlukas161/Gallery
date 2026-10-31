package org.fossify.gallery.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class CompareFormatTest {
    @Test
    fun megapixelsAndSizesUseSlovakDecimalComma() {
        assertEquals("12 Mpx", CompareFormat.megapixels(4000, 3000))
        assertEquals("1,9 Mpx", CompareFormat.megapixels(1600, 1200))
        assertEquals("50 Mpx", CompareFormat.megapixels(8160, 6120))
        assertEquals("3,2 MB", CompareFormat.size(3_200_000))
        assertEquals("46 MB", CompareFormat.size(46_400_000))
        assertEquals("850 kB", CompareFormat.size(850_000))
        assertEquals("1,5 GB", CompareFormat.size(1_500_000_000))
        assertEquals("0 B", CompareFormat.size(-5))
        assertEquals("2", CompareFormat.decimal(2.0))
    }

    @Test
    fun timeHasSecondsAndDateIsSlovak() {
        val ms = LocalDateTime.of(2024, 6, 3, 14, 5, 32).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("14:05:32", CompareFormat.time(ms, ZoneOffset.UTC))
        assertEquals("3. 6. 2024", CompareFormat.date(ms, ZoneOffset.UTC))
    }

    @Test
    fun exposureApertureFocal() {
        assertEquals("1/120 s", CompareFormat.exposureTime(1.0 / 120))
        assertEquals("2 s", CompareFormat.exposureTime(2.0))
        assertEquals("f/1,8", CompareFormat.aperture(1.8))
        assertEquals("26 mm", CompareFormat.focal(26.0))
    }

    @Test
    fun folderKinds() {
        assertEquals(FolderKind.CAMERA, CompareFormat.folderKind("/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertEquals(FolderKind.SCREENSHOTS, CompareFormat.folderKind("/storage/emulated/0/Pictures/Screenshots/a.png"))
        val wa = "/storage/emulated/0/WhatsApp/Media/WhatsApp Images/a.jpg"
        assertEquals(FolderKind.WHATSAPP, CompareFormat.folderKind(wa))
        assertEquals(FolderKind.DOWNLOAD, CompareFormat.folderKind("/storage/emulated/0/Download/a.jpg"))
        assertEquals(FolderKind.OTHER, CompareFormat.folderKind("/storage/emulated/0/Pictures/Výlet/a.jpg"))
    }

    @Test
    fun exifRowsHighlightBetterAndDifferentValues() {
        val a = PhotoInfo(
            "/DCIM/Camera/a.jpg", 1000L, 4000, 3000, 3_000_000, 1000L,
            iso = 100, exposureTime = 1.0 / 250,
        )
        val b = PhotoInfo(
            "/WhatsApp/Media/WhatsApp Images/a.jpg", 2000L, 1600, 1200, 300_000, 2000L,
            iso = 400, exposureTime = 1.0 / 60, hasGps = true,
        )
        val rows = ExifCompare.rows(a, b, { CompareFormat.folderKind(it).name }, "áno", "nie").associateBy { it.field }
        assertEquals(-1, rows.getValue(InfoField.RESOLUTION).better)
        assertEquals("4000 × 3000", rows.getValue(InfoField.RESOLUTION).a)
        assertEquals(-1, rows.getValue(InfoField.ISO).better)
        assertEquals(-1, rows.getValue(InfoField.SHUTTER).better)
        assertEquals(-1, rows.getValue(InfoField.FOLDER).better)
        assertEquals("nie", rows.getValue(InfoField.GPS).a)
        assertTrue(rows.getValue(InfoField.GPS).differs)
        assertEquals(ExifCompare.NONE, rows.getValue(InfoField.CAMERA).a)
        assertFalse(rows.getValue(InfoField.CAMERA).differs)
        assertEquals(0, rows.getValue(InfoField.APERTURE).better)
        assertEquals(10, rows.size)
    }
}
