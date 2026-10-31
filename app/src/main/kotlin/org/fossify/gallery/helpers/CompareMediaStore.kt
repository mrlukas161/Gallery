package org.fossify.gallery.helpers

import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore

// Čítanie MediaStore pre „Podobné a duplikáty“ (CompareRepository). Len na pozadí.
object CompareMediaStore {
    // takenMs = dátum nasnímania (0 = chýba EXIF), modifiedMs = zmena súboru, size/width/height 0 = neznáme
    class Row(
        val path: String,
        val takenMs: Long,
        val modifiedMs: Long,
        val size: Long,
        val width: Int,
        val height: Int,
    )

    private const val MS_PER_SECOND = 1000L
    private const val ID_MIX = 31L

    private val PROJECTION = arrayOf(
        MediaStore.Images.Media.DATA,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
    )

    fun images(context: Context): List<Row> = try {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, PROJECTION, null, null, null)
            ?.use { readRows(it) }
            .orEmpty()
    } catch (ignored: Throwable) {
        emptyList()
    }

    // Lacná pečiatka MediaStore: počet, zmiešané ID a najnovšia zmena (zachytí pridanie, zmazanie aj presun
    // — presunutý súbor dostane nové ID); na Androide 11+ navyše generácia (zachytí aj premenovanie).
    fun stamp(context: Context): String = try {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED)
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val counts = context.contentResolver.query(uri, projection, null, null, null)?.use { idStamp(it) }
        "$counts:" + generation(context)
    } catch (ignored: Throwable) {
        "?" + System.nanoTime() // nečitateľné = vždy prepočítať
    }

    private fun idStamp(c: Cursor): String {
        var count = 0L
        var mix = 0L
        var newest = 0L
        while (c.moveToNext()) {
            count++
            mix = mix * ID_MIX + c.getLong(0)
            newest = maxOf(newest, c.getLong(1))
        }
        return "$count:$mix:$newest"
    }

    private fun generation(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return try {
            MediaStore.getVersion(context) + MediaStore.getExternalVolumeNames(context).sorted()
                .joinToString(",") { it + "=" + MediaStore.getGeneration(context, it) }
        } catch (ignored: Throwable) {
            ""
        }
    }

    private fun readRows(c: Cursor): List<Row> {
        val rows = ArrayList<Row>(c.count)
        while (c.moveToNext()) {
            val row = readRow(c)
            if (row != null) rows.add(row)
        }
        return rows
    }

    // stĺpce v poradí PROJECTION
    private fun readRow(c: Cursor): Row? {
        var col = 0
        val path = c.getString(col++) ?: return null
        // DATE_TAKEN bez EXIF chýba (null → 0): taká fotka nepatrí do časovej série
        val taken = if (c.isNull(col)) 0L else c.getLong(col)
        col++
        return Row(
            path = path,
            takenMs = taken,
            modifiedMs = c.getLong(col++) * MS_PER_SECOND,
            size = c.getLong(col++),
            width = c.getInt(col++),
            height = c.getInt(col),
        )
    }
}
