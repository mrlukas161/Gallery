package org.fossify.gallery.faces

import kotlin.math.sqrt

// Výber tváre, ktorá najlepšie zodpovedá očakávanému rámčeku (B2). Výrez okolo detekcie má okraj
// 30 %, takže na skupinovej fotke v ňom býva aj kus suseda — predtým sa brala prvá (landmarker) alebo
// najistejšia (BlazeFace) tvár vo výreze a tvár mohla dostať odtlačok suseda.
// Rámčeky sú [left, top, right, bottom] v rovnakých súradniciach. Bez Android tried (JUnit testy).
// MagicNumber: indexy rámčeka [left, top, right, bottom]
@Suppress("MagicNumber")
object FaceSelect {
    // bez prekryvu sa berie najbližší stred, ale len keď je bližšie ako polovica uhlopriečky
    const val MAX_CENTER_DIST = 0.5f

    fun iou(a: FloatArray, b: FloatArray): Float {
        val x1 = maxOf(a[0], b[0])
        val y1 = maxOf(a[1], b[1])
        val x2 = minOf(a[2], b[2])
        val y2 = minOf(a[3], b[3])
        val inter = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        if (inter <= 0f) return 0f
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    // Index kandidáta s najväčším prekryvom s cieľom (pri zhode bližší stred); bez prekryvu najbližší
    // stred do MAX_CENTER_DIST × uhlopriečka cieľa; inak -1 (radšej žiadny bod než bod suseda).
    fun bestMatch(candidates: List<FloatArray>, target: FloatArray): Int {
        if (candidates.isEmpty()) return -1
        var best = -1
        var bestIou = 0f
        var bestDist = Float.MAX_VALUE
        for ((i, c) in candidates.withIndex()) {
            val v = iou(c, target)
            val d = centerDist(c, target)
            val tieButCloser = v > 0f && v == bestIou && d < bestDist
            if (v > bestIou || tieButCloser) {
                best = i
                bestIou = v
                bestDist = d
            }
        }
        if (best >= 0) return best
        val limit = MAX_CENTER_DIST * diagonal(target)
        for ((i, c) in candidates.withIndex()) {
            val d = centerDist(c, target)
            if (d < bestDist) {
                best = i
                bestDist = d
            }
        }
        return if (best >= 0 && bestDist <= limit) best else -1
    }

    // obal bodov (napr. všetkých landmarkov jednej tváre)
    fun boundsOf(xs: FloatArray, ys: FloatArray): FloatArray {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until minOf(xs.size, ys.size)) {
            l = minOf(l, xs[i])
            r = maxOf(r, xs[i])
            t = minOf(t, ys[i])
            b = maxOf(b, ys[i])
        }
        return floatArrayOf(l, t, r, b)
    }

    // Očakávaný rámček, keď detekciu nepoznáme (výrez z Picasy = tvár je v strede).
    fun centralBox(width: Int, height: Int): FloatArray =
        floatArrayOf(width * 0.2f, height * 0.2f, width * 0.8f, height * 0.8f)

    private fun centerDist(a: FloatArray, b: FloatArray): Float {
        val dx = (a[0] + a[2]) / 2f - (b[0] + b[2]) / 2f
        val dy = (a[1] + a[3]) / 2f - (b[1] + b[3]) / 2f
        return sqrt(dx * dx + dy * dy)
    }

    private fun diagonal(a: FloatArray): Float {
        val w = a[2] - a[0]
        val h = a[3] - a[1]
        return sqrt(w * w + h * h)
    }
}
