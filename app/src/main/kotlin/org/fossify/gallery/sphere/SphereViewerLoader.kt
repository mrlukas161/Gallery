package org.fossify.gallery.sphere

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.fossify.gallery.spherecore.GPanoInfo
import org.fossify.gallery.spherecore.XmpGPano
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.abs

/** Časť gule, ktorú obraz pokrýva (stupne): lon −180..180 doprava, lat 90 hore .. −90 dole. */
data class SphereRegion(val lonStart: Double, val lonEnd: Double, val latTop: Double, val latBottom: Double) {
    companion object {
        val FULL = SphereRegion(-180.0, 180.0, 90.0, -90.0)
    }
}

/** Čo vieme o súbore pred dekódovaním. */
class SphereViewerMeta(
    val width: Int,
    val height: Int,
    val region: SphereRegion,
    /** Východiskový azimut (stupne) voči stredu obrázka. */
    val initialYaw: Double,
    /** Východiskové zorné pole (stupne) pozdĺž kratšej strany obrazovky. */
    val initialFov: Double,
)

// Načítanie metadát a dekódovanie textúry mimo hlavného vlákna.
object SphereViewerLoader {
    private const val HEAD_BYTES = 512 * 1024

    private fun isUri(path: String) = path.startsWith("content:") || path.startsWith("file:")

    private fun open(context: Context, path: String): InputStream? =
        if (isUri(path)) context.contentResolver.openInputStream(Uri.parse(path)) else FileInputStream(File(path))

    private fun readHead(context: Context, path: String): ByteArray {
        val buf = ByteArray(HEAD_BYTES)
        var n = 0
        open(context, path)?.use { s ->
            while (n < buf.size) {
                val r = s.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
        }
        return buf.copyOf(n)
    }

    private fun wrap180(a: Double): Double {
        var x = a % 360.0
        if (x > 180) x -= 360
        if (x < -180) x += 360
        return x
    }

    /** null = nie je guľová panoráma alebo sa nedá prečítať. */
    fun readMeta(context: Context, path: String): SphereViewerMeta? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, path)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null

        var info: GPanoInfo? = try {
            XmpGPano.read(readHead(context, path))
        } catch (e: Throwable) {
            null
        }
        if (info != null && !sane(info)) info = null

        if (info == null) {
            // bez GPano len presný pomer 2:1 (±1 %) berieme ako celú guľu
            val ratio = w.toDouble() / (2.0 * h)
            return if (abs(ratio - 1.0) <= 0.01) SphereViewerMeta(w, h, SphereRegion.FULL, 0.0, 75.0) else null
        }

        val fw = info.fullPanoWidth.toDouble()
        val fh = info.fullPanoHeight.toDouble()
        val region = SphereRegion(
            lonStart = (info.croppedLeft / fw - 0.5) * 360.0,
            lonEnd = ((info.croppedLeft + info.croppedWidth) / fw - 0.5) * 360.0,
            latTop = (0.5 - info.croppedTop / fh) * 180.0,
            latBottom = (0.5 - (info.croppedTop + info.croppedHeight) / fh) * 180.0,
        )
        // GPano: azimuty sú kompasové; stred obrázka = PoseHeading (ak je známy)
        val yaw = wrap180(info.initialViewHeadingDeg - (info.poseHeadingDeg ?: 0.0))
        val fov = info.initialHorizontalFovDeg.let { if (it in 30.0..110.0) it else 75.0 }
        return SphereViewerMeta(w, h, region, yaw, fov)
    }

    private fun sane(i: GPanoInfo) =
        i.fullPanoWidth > 0 && i.fullPanoHeight > 0 && i.croppedWidth > 0 && i.croppedHeight > 0 &&
            i.croppedLeft >= 0 && i.croppedTop >= 0 &&
            i.croppedLeft + i.croppedWidth <= i.fullPanoWidth + 1 && i.croppedTop + i.croppedHeight <= i.fullPanoHeight + 1

    /** Dekóduje obraz tak, aby sa zmestil do textúry; pri nedostatku pamäte skúša väčší inSampleSize. */
    fun decode(
        context: Context,
        path: String,
        meta: SphereViewerMeta,
        maxTexture: Int,
        bigRam: Boolean,
        cancelled: () -> Boolean,
    ): Bitmap? {
        val maxWidth = minOf(maxTexture, if (bigRam) 8192 else 4096)
        val maxPixels = if (bigRam) 34_000_000L else 17_000_000L
        var sample = 1
        while (meta.width / sample > maxWidth || meta.height / sample > maxTexture ||
            (meta.width / sample).toLong() * (meta.height / sample) > maxPixels
        ) sample *= 2
        var config = Bitmap.Config.ARGB_8888
        repeat(6) {
            if (cancelled()) return null
            try {
                val o = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = config
                }
                val bmp = open(context, path)?.use { BitmapFactory.decodeStream(it, null, o) }
                if (bmp != null) return bmp
            } catch (e: OutOfMemoryError) {
                // ďalší pokus s menším obrazom
            }
            sample *= 2
            if (sample >= 8) config = Bitmap.Config.RGB_565
        }
        return null
    }
}
