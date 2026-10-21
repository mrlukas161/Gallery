package org.fossify.gallery.faces

// Prepočty pre výrez tváre z plného rozlíšenia (B3) bez Android tried (JUnit testy).
// Rámčeky tvárí sú uložené v „upright" priestore fotky zmenšenej na najviac 1024 px (UprightDecoder,
// inSampleSize po mocninách dvoch). Výrez sa premietne späť do surových súradníc plného rozlíšenia
// a dekóduje sa len tento región (vzor: FaceCropLoader.decodeCropRegion).
object FullResMath {
    // tvár vo výreze aspoň takto široká (ArcFace šablóna ju potom zmenší na ~70 px postupne po polovici)
    const val TARGET_FACE_PX = 224

    // rovnaký výpočet ako UprightDecoder.decode
    fun sampleFor(rawW: Int, rawH: Int, maxSize: Int): Int {
        var sample = 1
        while (rawW / sample > maxSize || rawH / sample > maxSize) sample *= 2
        return sample
    }

    // [šírka, výška] upright bitmapy pre dané otočenie (90/270 vymení strany)
    fun uprightDims(rawW: Int, rawH: Int, sample: Int, deg: Int): IntArray {
        val w = rawW / sample
        val h = rawH / sample
        return if (deg == 90 || deg == 270) intArrayOf(h, w) else intArrayOf(w, h)
    }

    // výrez s okrajom [marginFrac] v upright priestore, orezaný na fotku (rovnako ako FaceAligner.cropRegion)
    fun regionWithMargin(l: Int, t: Int, r: Int, b: Int, marginFrac: Float, w: Int, h: Int): IntArray? {
        val fw = r - l
        val fh = b - t
        if (fw <= 0 || fh <= 0) return null
        val x0 = (l - fw * marginFrac).toInt().coerceIn(0, w)
        val y0 = (t - fh * marginFrac).toInt().coerceIn(0, h)
        val x1 = (r + fw * marginFrac).toInt().coerceIn(x0, w)
        val y1 = (b + fh * marginFrac).toInt().coerceIn(y0, h)
        if (x1 <= x0 || y1 <= y0) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    // Upright región (zmenšený priestor) → obdĺžnik v surových súradniciach plného rozlíšenia.
    // Presný opak UprightDecoder.mapBox pre otočenia bez zrkadlenia; null = prázdny výsledok.
    fun rawRect(region: IntArray, deg: Int, scaledW: Int, scaledH: Int, sample: Int, rawW: Int, rawH: Int): IntArray? {
        val ul = region[0]
        val ut = region[1]
        val ur = region[2]
        val ub = region[3]
        val raw = when (deg) {
            90 -> intArrayOf(ut, scaledH - ur, ub, scaledH - ul)
            180 -> intArrayOf(scaledW - ur, scaledH - ub, scaledW - ul, scaledH - ut)
            270 -> intArrayOf(scaledW - ub, ul, scaledW - ut, ur)
            else -> intArrayOf(ul, ut, ur, ub)
        }
        val out = intArrayOf(
            (raw[0] * sample).coerceIn(0, rawW),
            (raw[1] * sample).coerceIn(0, rawH),
            (raw[2] * sample).coerceIn(0, rawW),
            (raw[3] * sample).coerceIn(0, rawH),
        )
        return if (out[2] > out[0] && out[3] > out[1]) out else null
    }

    // Najväčšie inSampleSize (mocnina dvoch), pri ktorom má tvár vo výreze ešte aspoň [target] px.
    fun regionSample(faceWidthRaw: Int, target: Int = TARGET_FACE_PX): Int {
        var s = 1
        while (faceWidthRaw / (s * 2) >= target) s *= 2
        return s
    }
}
