package org.fossify.gallery.faces

import android.graphics.Bitmap
import kotlin.math.abs

// E3 — doplnková detekcia malých tvárí: fotka sa dekóduje na ~2048 px (UprightDecoder, rovnaká orientácia ako
// kanonická 1024 px bitmapa), detekuje sa na 2×2 prekrývajúcich sa dlaždiciach (FaceTiles) a rámčeky sa
// prepočítajú presnou mierkou (pomer skutočných rozmerov oboch bitmáp) do kanonického priestoru 1024 px —
// v ňom sú všetky uložené rámčeky, takže existujúce tváre sa nerozbijú. Vráti len NOVÉ tváre (NMS proti
// detekciám z celej fotky [base]); bránu kvality pre ne uplatní FaceStore (pod ňou sa neuložia).
// Zapína ju len FaceIndexer pri nabíjaní alebo v Plnom profile (ProcessingPolicy.multiScale).
object FaceMultiScale {
    const val BIG_DECODE = 2048

    // pomer strán veľkej a kanonickej bitmapy sa smie líšiť najviac o toľko (zaokrúhlenie pri dekódovaní);
    // inak (napr. dekodér pri nedostatku pamäte neotočil) sa dlaždice nepoužijú
    private const val MAX_ASPECT_DIFF = 0.02f

    fun extraFaces(
        path: String,
        canonical: Bitmap,
        detector: FaceDetectionHelper,
        base: List<FaceDetectionHelper.DetectedFace>,
    ): List<FaceDetectionHelper.DetectedFace> {
        val big = try {
            UprightDecoder.decode(path, BIG_DECODE)?.bitmap
        } catch (ignored: OutOfMemoryError) {
            null
        } ?: return emptyList()
        return try {
            detectTiles(big, canonical, detector, base)
        } finally {
            big.recycle()
        }
    }

    private fun detectTiles(
        big: Bitmap,
        canonical: Bitmap,
        detector: FaceDetectionHelper,
        base: List<FaceDetectionHelper.DetectedFace>,
    ): List<FaceDetectionHelper.DetectedFace> {
        val sx = canonical.width.toFloat() / big.width
        val sy = canonical.height.toFloat() / big.height
        if (abs(sx - sy) > MAX_ASPECT_DIFF * sx) return emptyList()
        val found = ArrayList<FaceDetectionHelper.DetectedFace>()
        for (tile in FaceTiles.tiles(big.width, big.height)) {
            val crop = try {
                Bitmap.createBitmap(big, tile.left, tile.top, tile.width, tile.height)
            } catch (ignored: Throwable) {
                continue
            }
            try {
                detector.detect(crop).forEach { found.add(toCanonical(it, tile, sx, sy)) }
            } catch (ignored: Throwable) {
                // dlaždica bez výsledku — ostatné pokračujú
            } finally {
                if (crop !== big) crop.recycle()
            }
        }
        val usable = found.filter { FaceFilter.isUsable(it) }
        val keep = FaceTiles.mergeExtras(base.map { it.box() }, usable.map { it.box() to it.score })
        return keep.map { usable[it] }
    }

    private fun toCanonical(
        d: FaceDetectionHelper.DetectedFace,
        tile: FaceTiles.Tile,
        sx: Float,
        sy: Float,
    ): FaceDetectionHelper.DetectedFace {
        val b = FaceTiles.toCanonical(d.box(), tile, sx, sy)
        return FaceDetectionHelper.DetectedFace(
            b[0].toInt(), b[1].toInt(), b[2].toInt(), b[b.size - 1].toInt(), d.score,
            (d.eye1X + tile.left) * sx, (d.eye1Y + tile.top) * sy,
            (d.eye2X + tile.left) * sx, (d.eye2Y + tile.top) * sy,
            d.hasEyes,
        )
    }
}
