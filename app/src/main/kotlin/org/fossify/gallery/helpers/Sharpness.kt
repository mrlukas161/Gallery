package org.fossify.gallery.helpers

import android.graphics.BitmapFactory
import java.io.File

// "AI ostrosť" = rozptyl Laplaciánu (štandardná miera ostrosti/rozmazania). Vyššie = ostrejšie.
object Sharpness {
    fun score(path: String, maxSide: Int = 256): Double {
        if (!File(path).exists()) return 0.0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) return 0.0
        var sample = 1
        while (w0 / sample > maxSide || h0 / sample > maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return 0.0
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) {
            bmp.recycle()
            return 0.0
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val gray = IntArray(w * h) {
            val p = px[it]
            ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = (gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4 * gray[i]).toDouble()
                sum += lap
                sum2 += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sum2 / n - mean * mean
    }
}
