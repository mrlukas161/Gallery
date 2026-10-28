package org.fossify.gallery.faces

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sqrt

// E1 — skóre kvality tváre a brána kvality. Čistá logika bez Androidu (FaceQualityTest).
//
// Skóre 0–1 = metóda zarovnania × veľkosť^0,4 × ostrosť^0,3 × póza:
//  - veľkosť: šírka tváre v PÔVODNOM rozlíšení fotky (nie v zmenšenej 1024 px bitmape) — malá vzdialená tvár
//    má málo detailov aj pri výreze z plného rozlíšenia;
//  - ostrosť: variancia Laplaciánu sivého obrazu výrezu tváre zmenšeného na 112×112 px (veľkosť vstupu
//    odtlačku) — rozmazaný pohyb alebo zlé zaostrenie dávajú nízku varianciu;
//  - póza: natočenie hlavy do strany (yaw) a hore/dole (pitch) z 5 bodov tváre; z profilu dáva odtlačok
//    iný vektor než zpredu a ťahá ťažisko osoby nabok.
// Súčin: jedna veľmi zlá zložka (profil, rozmazanie) stiahne skóre nadol, aj keď sú ostatné dobré. Veľkosť
// a ostrosť spolu súvisia (malá tvár je po zväčšení na 112 px aj menej ostrá), preto majú zmiernené exponenty.
// Tvár pod bránou (QUALITY_GATE) nevstupuje do ťažísk, prototypov (KnnModel) ani automatického zaraďovania;
// v návrhoch sa ukázať môže a ručne potvrdiť sa dá vždy. Neznáme skóre (null — stará tvár pred dopočítaním)
// bránou prejde (spätná kompatibilita).
@Suppress("MagicNumber")
object FaceQuality {
    // Brána kvality. Pri 0,3 neprejde napr.: tvár s pootočením hlavy nad ~55° (profil), tvár užšia ako ~40 px
    // v origináli, ktorá je zároveň neostrá (variancia Laplaciánu pod ~30), výrazne rozmazaná tvár (pod ~13)
    // alebo malá tvár zarovnaná len zmenšením (MR). Bežná ostrá tvár zpredu má 0,7–1,0, skupinová fotka
    // zďaleka 0,3–0,6. Vyššie číslo = menej, ale čistejších vzorov osoby (a menej automatických zaradení).
    // Prah treba overiť na skutočnej knižnici (rozdelenie skóre v Nastaveniach zatiaľ nie je).
    const val QUALITY_GATE = 0.3f

    // veľkosť: od SIZE_MIN (0) po SIZE_FULL (1) px šírky tváre v pôvodnom rozlíšení
    const val SIZE_MIN = 24f
    const val SIZE_FULL = 112f

    // ostrosť: variancia Laplaciánu (0–255 sivá) na 112 px — od SHARP_MIN (0) po SHARP_FULL (1)
    const val SHARP_MIN = 10f
    const val SHARP_FULL = 120f

    // póza: do POSE_OK stupňov bez trestu, od POSE_MAX nula
    const val YAW_OK = 25f
    const val YAW_MAX = 65f
    const val PITCH_OK = 20f
    const val PITCH_MAX = 50f

    // exponenty veľkosti a ostrosti
    private const val W_SIZE = 0.4f
    private const val W_SHARP = 0.3f

    // neznáma zložka (póza bez bodov tváre pri zarovnaní M2/MR, ostrosť bez výrezu) — mierny trest
    const val UNKNOWN_FACTOR = 0.8f

    // malý základ, aby jedna nulová zložka nevynulovala skóre úplne (poradie tvárí ostane)
    private const val FLOOR = 0.02f

    // rozmer výrezu pre ostrosť (= vstup odtlačku)
    const val SHARP_EDGE = 112

    // váha metódy zarovnania: 5 bodov > 2 body (BlazeFace oči) > len zmenšenie
    fun alignFactor(method: Int?): Float = when (method) {
        AlignMethod.M5 -> 1f
        AlignMethod.M2 -> 0.85f
        AlignMethod.MR -> 0.6f
        else -> 0.85f
    }

    fun sizeFactor(widthPx: Float): Float = ramp(widthPx, SIZE_MIN, SIZE_FULL)

    fun sharpFactor(laplacianVar: Float?): Float =
        if (laplacianVar == null) UNKNOWN_FACTOR else ramp(laplacianVar, SHARP_MIN, SHARP_FULL)

    fun poseFactor(yaw: Float?, pitch: Float?): Float {
        if (yaw == null || pitch == null) return UNKNOWN_FACTOR
        val y = 1f - ramp(abs(yaw), YAW_OK, YAW_MAX)
        val p = 1f - ramp(abs(pitch), PITCH_OK, PITCH_MAX)
        return y * p
    }

    // skóre 0–1; widthPx = šírka tváre v pôvodnom rozlíšení
    fun score(widthPx: Float, laplacianVar: Float?, yaw: Float?, pitch: Float?, method: Int?): Float {
        val s = sizeFactor(widthPx).coerceAtLeast(FLOOR)
        val b = sharpFactor(laplacianVar).coerceAtLeast(FLOOR)
        val p = poseFactor(yaw, pitch).coerceAtLeast(FLOOR)
        return (alignFactor(method) * s.pow(W_SIZE) * b.pow(W_SHARP) * p).coerceIn(0f, 1f)
    }

    // prejde tvár bránou? (neznáme skóre = áno)
    fun passes(quality: Float?): Boolean = quality == null || quality >= QUALITY_GATE

    fun passes(face: FaceEntity): Boolean = passes(face.quality)

    // Variancia Laplaciánu (jadro 0 1 0 / 1 −4 1 / 0 1 0) sivého obrazu ARGB pixelov width × height.
    fun laplacianVariance(argb: IntArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3 || argb.size < width * height) return 0f
        val gray = FloatArray(width * height) { i ->
            val p = argb[i]
            0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF)
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val v = gray[i - width] + gray[i + width] + gray[i - 1] + gray[i + 1] - 4f * gray[i]
                sum += v
                sumSq += v.toDouble() * v
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat().coerceAtLeast(0f)
    }

    // Natočenie hlavy [yaw, pitch] v stupňoch z 5 bodov (poradie šablóny ArcFace: oko vľavo na obrázku,
    // oko vpravo, nos, kútik úst vľavo, vpravo). Model: oči a ústa ležia v rovine, špička nosa je pred ňou
    // o NOSE_DEPTH polovice vzdialenosti očí. Pri natočení o uhol θ sa nos posunie o d·sin θ, kým polovica
    // vzdialenosti očí sa skráti na a·cos θ → θ = atan(posun / (a · NOSE_DEPTH)). Pitch analogicky z výšky
    // nosa medzi líniou očí a úst (zpredu podiel ako v šablóne ArcFace). Náklon hlavy (roll) sa najprv
    // odstráni otočením bodov tak, aby oči boli vodorovne. null = body nedávajú zmysel.
    fun pose(points: FloatArray): FloatArray? {
        if (points.size < POINTS) return null
        val ex = (points[0] + points[2]) / 2f
        val ey = (points[1] + points[3]) / 2f
        val dx = points[2] - points[0]
        val dy = points[3] - points[1]
        val iod = sqrt(dx * dx + dy * dy)
        if (iod <= 0f) return null
        val c = dx / iod
        val s = -dy / iod
        // súradnice v rámci tváre (oči vodorovne, počiatok v strede očí)
        fun rx(i: Int) = (points[i] - ex) * c - (points[i + 1] - ey) * s
        fun ry(i: Int) = (points[i] - ex) * s + (points[i + 1] - ey) * c
        val noseX = rx(NOSE)
        val noseY = ry(NOSE)
        val mouthX = (rx(MOUTH_L) + rx(MOUTH_R)) / 2f
        val mouthY = (ry(MOUTH_L) + ry(MOUTH_R)) / 2f
        if (mouthY <= 0f) return null
        val half = iod / 2f
        // stred tváre = medzi stredom očí a úst (menej citlivé na náklon hlavy)
        val yawRatio = (noseX - mouthX / 2f) / (half * NOSE_DEPTH)
        val yaw = Math.toDegrees(atan(yawRatio.toDouble())).toFloat()
        val t = noseY / mouthY
        val pitchRatio = (NOSE_T0 - t) * mouthY / (half * NOSE_DEPTH)
        val pitch = Math.toDegrees(atan(pitchRatio.toDouble())).toFloat()
        return floatArrayOf(yaw, pitch)
    }

    private fun ramp(v: Float, from: Float, to: Float): Float = ((v - from) / (to - from)).coerceIn(0f, 1f)

    private const val POINTS = 10
    private const val NOSE = 4
    private const val MOUTH_L = 6
    private const val MOUTH_R = 8

    // hĺbka špičky nosa pred rovinou očí a úst v pomere k polovici vzdialenosti očí (antropometria ~0,7–0,8)
    const val NOSE_DEPTH = 0.75f

    // poloha nosa medzi líniou očí (0) a úst (1) pri pohľade zpredu — zo šablóny ArcFace (71,7−51,6)/(92,3−51,6)
    private const val NOSE_T0 = 0.4946f
}
