package org.fossify.gallery.faces

import android.graphics.Bitmap

// Spoločná cesta na vytvorenie odtlačku tváre. Poradie kvality:
//  1) 5-bodové zarovnanie (FaceLandmarker) — malé výrezy najprv zväčší, aby vnútorný detektor landmarkera nezlyhal;
//  2) 2-bodové zarovnanie (BlazeFace oči) — rovnaký ArcFace priestor ako embed5, kvalitný fallback;
//  3) číre zmenšenie (bez zarovnania) — až posledná možnosť.
object FaceAligner {
    const val M5 = 0   // 5-bodové zarovnanie
    const val M2 = 1   // 2-bodové zarovnanie (BlazeFace)
    const val MR = 2   // resize bez zarovnania

    class Emb(val vec: FloatArray, val method: Int)

    fun cropRegion(src: Bitmap, l: Int, t: Int, r: Int, b: Int, marginFrac: Float): Bitmap? {
        val fw = r - l
        val fh = b - t
        if (fw <= 0 || fh <= 0) return null
        val x0 = (l - fw * marginFrac).toInt().coerceIn(0, src.width)
        val y0 = (t - fh * marginFrac).toInt().coerceIn(0, src.height)
        val x1 = (r + fw * marginFrac).toInt().coerceIn(x0, src.width)
        val y1 = (b + fh * marginFrac).toInt().coerceIn(y0, src.height)
        if (x1 <= x0 || y1 <= y0) return null
        return try {
            Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
        } catch (e: Throwable) {
            null
        }
    }

    fun embedCrop(
        crop: Bitmap,
        landmarker: FaceLandmarkHelper,
        embedder: FaceEmbedder,
        detector: FaceDetectionHelper? = null,
    ): Emb {
        // 1) 5-bodové zarovnanie. Malý výrez najprv zväčši (~256 px dlhá hrana), aby vnútorný
        //    BlazeFace v landmarkeri (192x192) tvár našiel; body prepočítaj späť do súradníc výrezu.
        val longEdge = maxOf(crop.width, crop.height)
        var up: Bitmap = crop
        var upScale = 1f
        if (longEdge in 1 until 256) {
            upScale = 256f / longEdge
            up = try {
                Bitmap.createScaledBitmap(
                    crop,
                    (crop.width * upScale).toInt().coerceAtLeast(1),
                    (crop.height * upScale).toInt().coerceAtLeast(1),
                    true,
                )
            } catch (e: Throwable) {
                upScale = 1f
                crop
            }
        }
        val ptsUp = try {
            landmarker.landmarks5(up)
        } catch (e: Throwable) {
            null
        }
        if (up !== crop) up.recycle()
        if (ptsUp != null) {
            val pts = if (upScale != 1f) FloatArray(ptsUp.size) { ptsUp[it] / upScale } else ptsUp
            return Emb(embedder.embed5(crop, pts), M5)
        }

        // 2) 2-bodové zarovnanie cez BlazeFace na výreze (rovnaký zarovnaný priestor).
        if (detector != null) {
            val f = try {
                detector.detect(crop).maxByOrNull { it.score }
            } catch (e: Throwable) {
                null
            }
            if (f != null) return Emb(embedder.embed(crop, f), M2)
        }

        // 3) posledná možnosť: číre zmenšenie (nezarovnané).
        return Emb(embedder.embedResized(crop), MR)
    }
}
