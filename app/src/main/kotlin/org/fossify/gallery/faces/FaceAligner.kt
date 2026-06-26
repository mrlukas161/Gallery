package org.fossify.gallery.faces

import android.graphics.Bitmap

// Spoločná cesta na vytvorenie odtlačku tváre: 5-bodové zarovnanie (FaceLandmarker) s fallbackom na zmenšenie.
object FaceAligner {
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

    fun embedCrop(crop: Bitmap, landmarker: FaceLandmarkHelper, embedder: FaceEmbedder): FloatArray {
        val pts = landmarker.landmarks5(crop)
        return if (pts != null) embedder.embed5(crop, pts) else embedder.embedResized(crop)
    }
}
