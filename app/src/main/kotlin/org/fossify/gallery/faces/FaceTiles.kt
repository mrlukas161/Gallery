package org.fossify.gallery.faces

import kotlin.math.roundToInt

// E3 — detekcia malých tvárí v dlaždiciach: rozloženie dlaždíc, prepočet rámčekov do kanonického priestoru
// (bitmapa z UprightDecoder.decode(path, 1024), v ňom sú všetky uložené rámčeky) a potlačenie duplicít (NMS).
// Čistá logika bez Androidu (FaceTilesTest).
//
// Prečo: BlazeFace (short range) zmenší vstup na 128×128 px, takže na celej 1024 px fotke nájde len tváre
// široké aspoň ~6–8 % obrázka. Na 2×2 dlaždiciach z ~2048 px dekódovania je každá tvár pre detektor asi
// 2× väčšia (malé tváre na skupinových fotkách). Dlaždice sa prekrývajú, aby tvár na hrane nebola rozrezaná.
// MagicNumber: indexy rámčeka [left, top, right, bottom]
@Suppress("MagicNumber")
object FaceTiles {
    // mriežka dlaždíc (2×2) a prekryv susedných dlaždíc (podiel strany obrázka)
    const val GRID = 2
    const val OVERLAP = 0.2f

    // tvár z dlaždice je duplicitou už nájdenej, keď sa prekrývajú aspoň takto (IoU) …
    const val NMS_IOU = 0.3f

    // … alebo keď z menšej z nich leží aspoň takáto časť v tej druhej (polovičná tvár na hrane dlaždice)
    const val NMS_CONTAIN = 0.6f

    // obdĺžnik dlaždice v pixeloch veľkej bitmapy [left, top, right, bottom)
    data class Tile(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    // GRID × GRID dlaždíc pokrývajúcich width × height; krajné sú zarovnané s okrajom obrázka
    fun tiles(width: Int, height: Int, grid: Int = GRID, overlap: Float = OVERLAP): List<Tile> {
        if (width <= 0 || height <= 0 || grid < 1) return emptyList()
        val xs = spans(width, grid, overlap)
        val ys = spans(height, grid, overlap)
        return ys.flatMap { (t, b) -> xs.map { (l, r) -> Tile(l, t, r, b) } }
    }

    private fun spans(size: Int, grid: Int, overlap: Float): List<Pair<Int, Int>> {
        if (grid == 1) return listOf(0 to size)
        val span = (size * (1f / grid + overlap / 2f)).roundToInt().coerceIn(1, size)
        return (0 until grid).map { i ->
            val start = ((size - span) * i.toFloat() / (grid - 1)).roundToInt()
            start to (start + span)
        }
    }

    // rámček [l, t, r, b] z pixelov dlaždice do kanonického priestoru (sx, sy = kanonická / veľká bitmapa)
    fun toCanonical(box: FloatArray, tile: Tile, sx: Float, sy: Float): FloatArray = floatArrayOf(
        (box[0] + tile.left) * sx,
        (box[1] + tile.top) * sy,
        (box[2] + tile.left) * sx,
        (box[3] + tile.top) * sy,
    )

    fun iou(a: FloatArray, b: FloatArray): Float {
        val inter = intersection(a, b)
        if (inter <= 0f) return 0f
        val union = area(a) + area(b) - inter
        return if (union <= 0f) 0f else inter / union
    }

    // podiel menšieho rámčeka, ktorý leží v tom druhom
    fun containment(a: FloatArray, b: FloatArray): Float {
        val inter = intersection(a, b)
        val small = minOf(area(a), area(b))
        return if (inter <= 0f || small <= 0f) 0f else inter / small
    }

    fun isDuplicate(a: FloatArray, b: FloatArray): Boolean =
        iou(a, b) >= NMS_IOU || containment(a, b) >= NMS_CONTAIN

    // NMS: detekcie z celej fotky [base] ostávajú vždy (sú to tie isté tváre ako doteraz — párovanie s už
    // uloženými ostane stabilné); z dlaždíc [extra] (rámček, skóre) sa po zostupnom skóre pridá len tá,
    // ktorá nie je duplicitou žiadnej ponechanej. Vráti indexy ponechaných z [extra].
    fun mergeExtras(base: List<FloatArray>, extra: List<Pair<FloatArray, Float>>): List<Int> {
        val kept = ArrayList(base)
        val out = ArrayList<Int>()
        for (i in extra.indices.sortedByDescending { extra[it].second }) {
            val box = extra[i].first
            if (kept.any { isDuplicate(it, box) }) continue
            kept.add(box)
            out.add(i)
        }
        return out
    }

    private fun intersection(a: FloatArray, b: FloatArray): Float {
        val w = minOf(a[2], b[2]) - maxOf(a[0], b[0])
        val h = minOf(a[3], b[3]) - maxOf(a[1], b[1])
        return if (w <= 0f || h <= 0f) 0f else w * h
    }

    private fun area(b: FloatArray): Float = maxOf(0f, b[2] - b[0]) * maxOf(0f, b[3] - b[1])
}
