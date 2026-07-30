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
        // Laplacián po DLAŽDICIACH (8×8) a z nich horný kvartil namiesto jedného globálneho čísla:
        // pri portrétoch s rozmazaným pozadím (bokeh) globálny rozptyl klame — rozhoduje, či je
        // ostrá tá NAJOSTREJŠIA časť fotky (typicky tvár), nie priemer celej plochy.
        val tiles = 8
        val scores = ArrayList<Double>(tiles * tiles)
        for (ty in 0 until tiles) {
            for (tx in 0 until tiles) {
                val x0 = (w * tx / tiles).coerceAtLeast(1)
                val x1 = (w * (tx + 1) / tiles).coerceAtMost(w - 1)
                val y0 = (h * ty / tiles).coerceAtLeast(1)
                val y1 = (h * (ty + 1) / tiles).coerceAtMost(h - 1)
                if (x1 - x0 < 2 || y1 - y0 < 2) continue
                var s = 0.0
                var s2 = 0.0
                var cnt = 0
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        val i = row + x
                        val lap = (gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4 * gray[i]).toDouble()
                        s += lap
                        s2 += lap * lap
                        cnt++
                    }
                }
                if (cnt > 0) {
                    val m = s / cnt
                    scores.add(s2 / cnt - m * m)
                }
            }
        }
        if (scores.isEmpty()) return 0.0
        scores.sort()
        // horný kvartil = „ako ostrý je ostrý objekt fotky"
        return scores[(scores.size * 3 / 4).coerceAtMost(scores.size - 1)]
    }
}
