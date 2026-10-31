package org.fossify.gallery.helpers

// Čisté výpočty nad pixelmi (bez Android tried) pre hodnotenie fotiek v porovnávači: jas, expozícia
// a ostrosť. Volá ich Sharpness, PhotoQuality aj CompareAnalysis; testy v ImageStatsTest.
object ImageStats {
    private const val R_WEIGHT = 299
    private const val G_WEIGHT = 587
    private const val B_WEIGHT = 114
    private const val LUMA_DIV = 1000
    private const val CHANNEL = 0xFF
    private const val SHIFT_R = 16
    private const val SHIFT_G = 8

    // pod/nad týmto jasom je pixel „prepálený“ alebo „utopený v čiernej“
    private const val DARK_CLIP = 14
    private const val BRIGHT_CLIP = 243

    // expozícia sa ráta z ~10 000 vzoriek (rýchle aj pri veľkej bitmape)
    private const val EXPOSURE_SAMPLES = 10_000

    // dlaždice 8×8 a z nich horný kvartil: pri portrétoch s rozmazaným pozadím (bokeh) globálny rozptyl
    // klame — rozhoduje, či je ostrá NAJOSTREJŠIA časť fotky (typicky tvár), nie priemer celej plochy
    private const val TILES = 8
    private const val QUARTILE_NUM = 3
    private const val QUARTILE_DEN = 4
    private const val LAPLACE_CENTER = 4

    // ostrosť sa meria vždy v rovnakej mierke (dlhšia strana), aby boli fotky rôzneho rozlíšenia porovnateľné
    const val SHARPNESS_SIDE = 400

    fun luma(argb: Int): Int {
        val r = argb shr SHIFT_R and CHANNEL
        val g = argb shr SHIFT_G and CHANNEL
        val b = argb and CHANNEL
        return (r * R_WEIGHT + g * G_WEIGHT + b * B_WEIGHT) / LUMA_DIV
    }

    fun toGray(argb: IntArray): IntArray = IntArray(argb.size) { luma(argb[it]) }

    // 0..1 podľa podielu prepálených a čiernych pixelov (1 = bez nich); prázdny obraz = 0,5
    fun exposure(gray: IntArray): Double {
        if (gray.isEmpty()) return NEUTRAL
        val step = (gray.size / EXPOSURE_SAMPLES).coerceAtLeast(1)
        var total = 0
        var clipped = 0
        var i = 0
        while (i < gray.size) {
            val l = gray[i]
            if (l < DARK_CLIP || l > BRIGHT_CLIP) clipped++
            total++
            i += step
        }
        return (1.0 - 2.0 * clipped / total).coerceIn(0.0, 1.0)
    }

    // zmenšenie plošným priemerom tak, aby dlhšia strana mala najviac maxSide (menší obraz sa nemení)
    fun downscale(img: GrayImage, maxSide: Int): GrayImage {
        val longSide = maxOf(img.width, img.height)
        if (longSide <= maxSide || img.width <= 0 || img.height <= 0) return img
        val tw = (img.width.toLong() * maxSide / longSide).toInt().coerceAtLeast(1)
        val th = (img.height.toLong() * maxSide / longSide).toInt().coerceAtLeast(1)
        val out = IntArray(tw * th)
        for (ty in 0 until th) {
            val y0 = ty * img.height / th
            val y1 = maxOf(y0 + 1, (ty + 1) * img.height / th)
            for (tx in 0 until tw) {
                val x0 = tx * img.width / tw
                val x1 = maxOf(x0 + 1, (tx + 1) * img.width / tw)
                out[ty * tw + tx] = boxAverage(img, x0, y0, x1, y1)
            }
        }
        return GrayImage(out, tw, th)
    }

    private fun boxAverage(img: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var sum = 0L
        for (y in y0 until y1) {
            val row = y * img.width
            for (x in x0 until x1) sum += img.pixels[row + x]
        }
        return (sum / ((x1 - x0) * (y1 - y0))).toInt()
    }

    // rozptyl Laplaciánu po dlaždiciach, horný kvartil = „ako ostrý je ostrý objekt fotky“; vyššie = ostrejšie
    fun sharpness(img: GrayImage): Double {
        val w = img.width
        val h = img.height
        if (w < MIN_SIDE || h < MIN_SIDE) return 0.0
        val scores = ArrayList<Double>(TILES * TILES)
        for (ty in 0 until TILES) {
            for (tx in 0 until TILES) {
                val x0 = (w * tx / TILES).coerceAtLeast(1)
                val x1 = (w * (tx + 1) / TILES).coerceAtMost(w - 1)
                val y0 = (h * ty / TILES).coerceAtLeast(1)
                val y1 = (h * (ty + 1) / TILES).coerceAtMost(h - 1)
                if (x1 - x0 >= 2 && y1 - y0 >= 2) scores.add(tileVariance(img, x0, y0, x1, y1))
            }
        }
        if (scores.isEmpty()) return 0.0
        scores.sort()
        return scores[(scores.size * QUARTILE_NUM / QUARTILE_DEN).coerceAtMost(scores.size - 1)]
    }

    private fun tileVariance(img: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val g = img.pixels
        val w = img.width
        var s = 0.0
        var s2 = 0.0
        var cnt = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val i = row + x
                val lap = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - LAPLACE_CENTER * g[i]).toDouble()
                s += lap
                s2 += lap * lap
                cnt++
            }
        }
        val m = s / cnt
        return s2 / cnt - m * m
    }

    private const val NEUTRAL = 0.5
    private const val MIN_SIDE = 3
}

// sivotónový obraz: jas 0–255 po riadkoch (width × height)
class GrayImage(val pixels: IntArray, val width: Int, val height: Int)
