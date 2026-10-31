package org.fossify.gallery.sync

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

// Dekódovanie náhľadov pre LAN server. Od Androidu 9 cez ImageDecoder: jedno otvorenie súboru, EXIF
// orientáciu uplatní sám a vie aj HEIC/DNG, ktoré prehliadač v PC nezobrazí. Staršie Androidy
// (8.x) idú pôvodnou cestou BitmapFactory + ExifInterface.
object ImageDecode {
    private const val MIN_QUALITY = 30
    private const val MAX_QUALITY = 100
    private const val NORMAL = ExifInterface.ORIENTATION_NORMAL

    // hotový náhľad z MediaStore (systém ho má často v cache) — len malé veľkosti, API 29+
    fun systemThumb(resolver: ContentResolver, uri: Uri, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            resolver.loadThumbnail(uri, Size(px, px), null)
        } catch (ignored: Throwable) {
            null
        }
    }

    // zmenšený obrázok (dlhá hrana ≤ maxPx), správne otočený
    fun sampled(resolver: ContentResolver, uri: Uri, maxPx: Int): Bitmap? {
        val viaDecoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decode({ ImageDecoder.createSource(resolver, uri) }, maxPx)
        } else {
            null
        }
        return viaDecoder ?: legacy({ resolver.openInputStream(uri) }, maxPx)
    }

    // obrázok z Koša (súbor v dátach appky, nie v MediaStore)
    fun sampledFile(file: File, maxPx: Int): Bitmap? {
        val viaDecoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decode({ ImageDecoder.createSource(file) }, maxPx)
        } else {
            null
        }
        return viaDecoder ?: legacy({ file.inputStream() }, maxPx)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decode(source: () -> ImageDecoder.Source, maxPx: Int): Bitmap? = try {
        val bmp = ImageDecoder.decodeBitmap(source()) { decoder, info, _ ->
            // softvérová bitmapa (JPEG kompresia z HW bitmapy nejde) a hrubé zmenšenie už pri dekódovaní
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height, maxPx))
        }
        scaleToMax(bmp, maxPx)
    } catch (ignored: Throwable) {
        null
    }

    // mocnina 2 tak, aby dekódovaný obrázok mal najviac ~2× cieľovú veľkosť (dorovná scaleToMax)
    fun sampleFor(w: Int, h: Int, maxPx: Int): Int {
        var sample = 1
        while (w / sample > maxPx * 2 || h / sample > maxPx * 2) sample *= 2
        return sample
    }

    private fun legacy(open: () -> InputStream?, maxPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxPx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            // najprv zmenši (lacnejšie), potom otoč podľa EXIF — BitmapFactory orientáciu ignoruje
            val orientation = try {
                open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, NORMAL) }
            } catch (ignored: Throwable) {
                null
            }
            applyOrientation(scaleToMax(bmp, maxPx), orientation ?: NORMAL)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val t = ExifOrient.of(orientation)
        if (t.degrees == 0 && !t.mirror) return bmp
        return try {
            val m = Matrix()
            m.setRotate(t.degrees.toFloat())
            if (t.mirror) m.postScale(-1f, 1f)
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            out
        } catch (ignored: Throwable) {
            bmp // pri nedostatku pamäte radšej neotočený náhľad než žiadny
        }
    }

    fun videoFrame(context: Context, uri: Uri, maxPx: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.frameAtTime?.let { scaleToMax(it, maxPx) }
        } catch (ignored: Throwable) {
            null
        } finally {
            try {
                mmr.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    fun scaleToMax(bmp: Bitmap, maxPx: Int): Bitmap {
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge <= maxPx || longEdge <= 0) return bmp
        val sc = maxPx.toFloat() / longEdge
        val w = (bmp.width * sc).toInt().coerceAtLeast(1)
        val h = (bmp.height * sc).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    // JPEG bajty; bitmapu recykluje
    fun jpeg(bmp: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(MIN_QUALITY, MAX_QUALITY), bos)
        bmp.recycle()
        return bos.toByteArray()
    }
}
