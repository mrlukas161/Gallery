package org.fossify.gallery.faces

import kotlin.math.sqrt

// Čistá matematika zarovnania tváre pre odtlačky v2 (bez Android tried, pokrytá JUnit testami):
//  - body očí zo stredov očí (B1): stred dúhovky 468/473, inak priemer kútikov (33+133)/2 a (362+263)/2,
//  - podobnostná transformácia (Umeyama, uzavretý tvar) bodov na šablónu ArcFace 112×112,
//  - postupné zmenšovanie po polovici pred zarovnaním (B3, antialiasing),
//  - spojenie odtlačku a zrkadlového odtlačku (B4).
//
// Indexy landmarkov MediaPipe Face Mesh (face_mesh_connections.py): „pravé" oko osoby (na fotke vľavo)
// = kruh 33…133, dúhovka 469–472 so stredom 468; „ľavé" oko (na fotke vpravo) = kruh 263…362, dúhovka
// 474–477 so stredom 473. Nos = 1, kútiky úst 61 (na fotke vľavo) a 291 (vpravo). Model
// face_landmarker.task vracia 478 bodov (468 siete + 10 dúhovky).
object AlignMath {
    // ArcFace šablóna (insightface) v poradí: oko vľavo na obrázku, oko vpravo, nos, kútik úst vľavo, vpravo
    val ARCFACE_112 = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f,
    )

    const val RIGHT_EYE_OUTER = 33
    const val RIGHT_EYE_INNER = 133
    const val LEFT_EYE_INNER = 362
    const val LEFT_EYE_OUTER = 263
    const val RIGHT_IRIS = 468
    const val LEFT_IRIS = 473
    const val NOSE_TIP = 1
    const val MOUTH_RIGHT = 61
    const val MOUTH_LEFT = 291

    // dúhovka sa berie, len keď leží blízko stredu medzi kútikmi (pri zavretom oku alebo chybe siete
    // býva mimo); hranica je podiel šírky oka
    const val IRIS_MAX_OFFSET = 0.35f

    // cieľová mierka jedného kroku prevzorkovania; menšia mierka = bilineárny filter preskakuje pixely
    const val MIN_WARP_SCALE = 0.5f

    // Stred oka: dúhovka, ak je k dispozícii a dáva zmysel, inak priemer kútikov.
    fun eyeCenter(xs: FloatArray, ys: FloatArray, cornerA: Int, cornerB: Int, iris: Int): FloatArray {
        val mx = (xs[cornerA] + xs[cornerB]) / 2f
        val my = (ys[cornerA] + ys[cornerB]) / 2f
        if (iris < xs.size && iris < ys.size) {
            val width = dist(xs[cornerA], ys[cornerA], xs[cornerB], ys[cornerB])
            val ix = xs[iris]
            val iy = ys[iris]
            if (width > 0f && dist(ix, iy, mx, my) <= IRIS_MAX_OFFSET * width) return floatArrayOf(ix, iy)
        }
        return floatArrayOf(mx, my)
    }

    // 5 bodov pre šablónu ArcFace v jej poradí, alebo null, keď landmarkov nie je dosť.
    fun fivePoints(xs: FloatArray, ys: FloatArray): FloatArray? {
        if (xs.size != ys.size || xs.size <= MOUTH_LEFT || xs.size <= LEFT_EYE_INNER) return null
        val right = eyeCenter(xs, ys, RIGHT_EYE_OUTER, RIGHT_EYE_INNER, RIGHT_IRIS)
        val left = eyeCenter(xs, ys, LEFT_EYE_OUTER, LEFT_EYE_INNER, LEFT_IRIS)
        return floatArrayOf(
            right[0], right[1],
            left[0], left[1],
            xs[NOSE_TIP], ys[NOSE_TIP],
            xs[MOUTH_RIGHT], ys[MOUTH_RIGHT],
            xs[MOUTH_LEFT], ys[MOUTH_LEFT],
        )
    }

    // Podobnostná transformácia (rotácia + rovnomerná mierka + posun, bez zrkadlenia) src → dst
    // najmenšími štvorcami pre ľubovoľný počet bodov (≥ 2). Výsledok [a, b, tx, ty]:
    // x' = a·x − b·y + tx, y' = b·x + a·y + ty (mierka = √(a²+b²)).
    fun similarity(src: FloatArray, dst: FloatArray): FloatArray {
        val n = minOf(src.size, dst.size) / 2
        require(n >= 2) { "treba aspoň 2 body" }
        var sMeanX = 0f
        var sMeanY = 0f
        var dMeanX = 0f
        var dMeanY = 0f
        for (i in 0 until n) {
            sMeanX += src[i * 2]
            sMeanY += src[i * 2 + 1]
            dMeanX += dst[i * 2]
            dMeanY += dst[i * 2 + 1]
        }
        sMeanX /= n
        sMeanY /= n
        dMeanX /= n
        dMeanY /= n
        var dotSum = 0f
        var crossSum = 0f
        var srcSumSq = 0f
        for (i in 0 until n) {
            val sx = src[i * 2] - sMeanX
            val sy = src[i * 2 + 1] - sMeanY
            val dx = dst[i * 2] - dMeanX
            val dy = dst[i * 2 + 1] - dMeanY
            dotSum += sx * dx + sy * dy
            crossSum += sx * dy - sy * dx
            srcSumSq += sx * sx + sy * sy
        }
        val a = dotSum / (srcSumSq + 1e-8f)
        val b = crossSum / (srcSumSq + 1e-8f)
        val tx = dMeanX - (a * sMeanX - b * sMeanY)
        val ty = dMeanY - (b * sMeanX + a * sMeanY)
        return floatArrayOf(a, b, tx, ty)
    }

    fun scaleOf(t: FloatArray): Float = sqrt(t[0] * t[0] + t[1] * t[1])

    fun apply(t: FloatArray, x: Float, y: Float): FloatArray =
        floatArrayOf(t[0] * x - t[1] * y + t[2], t[1] * x + t[0] * y + t[3])

    // Koľkokrát treba zdroj zmenšiť na polovicu, aby záverečné zarovnanie nezmenšovalo viac ako 2:1.
    fun halvings(scale: Float, maxSteps: Int = 8): Int {
        if (scale <= 0f) return 0
        var s = scale
        var k = 0
        while (s < MIN_WARP_SCALE && k < maxSteps) {
            s *= 2f
            k++
        }
        return k
    }

    // Afinná matica 2×3 [m00, m01, m02, m10, m11, m12] pre zdroj zmenšený fx-krát / fy-krát
    // (bod zmenšeného zdroja × f = bod pôvodného), ktorá ho zobrazí rovnako ako [t] pôvodný zdroj.
    fun forScaledSource(t: FloatArray, fx: Float, fy: Float): FloatArray =
        floatArrayOf(t[0] * fx, -t[1] * fy, t[2], t[1] * fx, t[0] * fy, t[3])

    // B4: odtlačok + zrkadlový odtlačok → súčet jednotkových vektorov a L2 normalizácia (rovnaká váha).
    fun combineTta(a: FloatArray, b: FloatArray): FloatArray {
        val na = l2normalize(a)
        if (b.size != a.size) return na
        val nb = l2normalize(b)
        return l2normalize(FloatArray(a.size) { na[it] + nb[it] })
    }

    fun l2normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum).coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
}
