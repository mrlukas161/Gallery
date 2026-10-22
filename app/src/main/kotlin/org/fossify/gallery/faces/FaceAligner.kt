package org.fossify.gallery.faces

import android.graphics.Bitmap

// Spoločná cesta na vytvorenie odtlačku tváre (odtlačky v2, FaceEmbedder.PIPELINE_MODEL_ID). Poradie kvality:
//  1) 5-bodové zarovnanie (FaceLandmarker, stredy očí) — malé výrezy najprv zväčší, aby vnútorný detektor
//     landmarkera nezlyhal; z až 3 tvárí vo výreze berie tú, ktorá sedí na detekciu (B2);
//  2) 2-bodové zarovnanie (BlazeFace oči) — rovnaký ArcFace priestor ako embed5, tiež tvár podľa detekcie;
//  3) číre zmenšenie (bez zarovnania) — až posledná možnosť.
// Výrez sa berie z plného rozlíšenia fotky, keď je k dispozícii (FullResCrop, B3).
object FaceAligner {
    const val M5 = 0   // 5-bodové zarovnanie
    const val M2 = 1   // 2-bodové zarovnanie (BlazeFace)
    const val MR = 2   // resize bez zarovnania

    // okraj výrezu okolo rámčeka detekcie (podiel šírky/výšky)
    private const val MARGIN = 0.3f

    // landmarker potrebuje tvár aspoň takto veľkú (vnútorný BlazeFace 192×192)
    private const val MIN_LANDMARK_EDGE = 256

    class Emb(val vec: FloatArray, val method: Int)

    fun cropRegion(src: Bitmap, l: Int, t: Int, r: Int, b: Int, marginFrac: Float): Bitmap? {
        val region = FullResMath.regionWithMargin(l, t, r, b, marginFrac, src.width, src.height) ?: return null
        return cropOf(src, region)
    }

    // výrez [left, top, right, bottom]; null pri nedostatku pamäte
    @Suppress("MagicNumber")
    private fun cropOf(src: Bitmap, region: IntArray): Bitmap? = try {
        Bitmap.createBitmap(src, region[0], region[1], region[2] - region[0], region[3] - region[1])
    } catch (ignored: Throwable) {
        null
    }

    // Odtlačok jednej detegovanej tváre z celej (upright) fotky ako bajty do DB, alebo null.
    fun embedDetected(
        bmp: Bitmap,
        face: FaceDetectionHelper.DetectedFace,
        landmarker: FaceLandmarkHelper,
        embedder: FaceEmbedder,
        detector: FaceDetectionHelper?,
        full: FullResCrop? = null,
    ): ByteArray? =
        embedDetectedEmb(bmp, face, landmarker, embedder, detector, full)?.let { FaceEmbedder.toBytes(it.vec) }

    // Výrez sa uvoľní VŽDY, aj keď embedCrop vyhodí výnimku, a nikdy sa neuvoľní samotná fotka
    // (createBitmap vráti tú istú inštanciu, keď výrez pokryje celú fotku — recycle by ju zničil a ďalšie
    // tváre na nej by ostali bez odtlačku). Keď zarovnaný výrez zlyhá, skúsi sa ešte 2-bodové zarovnanie
    // nad celou fotkou: tvár bez odtlačku je v appke neviditeľná.
    @Suppress("ReturnCount")
    fun embedDetectedEmb(
        bmp: Bitmap,
        face: FaceDetectionHelper.DetectedFace,
        landmarker: FaceLandmarkHelper,
        embedder: FaceEmbedder,
        detector: FaceDetectionHelper?,
        full: FullResCrop? = null,
    ): Emb? {
        // 1) výrez z plného rozlíšenia (len keď je ostrejší než zo zmenšenej fotky)
        val fr = try {
            full?.crop(face.left, face.top, face.right, face.bottom, MARGIN)
        } catch (e: Throwable) {
            null
        }
        if (fr != null) {
            try {
                return embedCrop(fr.bitmap, landmarker, embedder, detector, fr.expected)
            } catch (ignored: Throwable) {
                // pokračuj zmenšenou fotkou
            } finally {
                fr.bitmap.recycle()
            }
        }
        // 2) výrez zo zmenšenej fotky
        embedDownscaled(bmp, face, landmarker, embedder, detector)?.let { return it }
        // 3) 2-bodové zarovnanie nad celou fotkou
        return try {
            Emb(embedder.embed(bmp, face), if (face.hasEyes) M2 else MR)
        } catch (e: Throwable) {
            null
        }
    }

    private fun embedDownscaled(
        bmp: Bitmap,
        face: FaceDetectionHelper.DetectedFace,
        landmarker: FaceLandmarkHelper,
        embedder: FaceEmbedder,
        detector: FaceDetectionHelper?,
    ): Emb? {
        val region = FullResMath.regionWithMargin(
            face.left, face.top, face.right, face.bottom, MARGIN, bmp.width, bmp.height,
        ) ?: return null
        val crop = cropOf(bmp, region) ?: return null
        val expected = floatArrayOf(
            (face.left - region[0]).toFloat(), (face.top - region[1]).toFloat(),
            (face.right - region[0]).toFloat(), (face.bottom - region[1]).toFloat(),
        )
        return try {
            embedCrop(crop, landmarker, embedder, detector, expected)
        } catch (ignored: Throwable) {
            null // pokračuj náhradnou cestou
        } finally {
            if (crop !== bmp) crop.recycle()
        }
    }

    // [expected] = rámček tváre v pixloch výrezu (null = tvár je v strede výrezu, napr. výrez z Picasy).
    fun embedCrop(
        crop: Bitmap,
        landmarker: FaceLandmarkHelper,
        embedder: FaceEmbedder,
        detector: FaceDetectionHelper? = null,
        expected: FloatArray? = null,
    ): Emb {
        val target = expected ?: FaceSelect.centralBox(crop.width, crop.height)
        // 1) 5-bodové zarovnanie. Malý výrez najprv zväčši (~256 px dlhá hrana), aby vnútorný
        //    BlazeFace v landmarkeri (192x192) tvár našiel; body prepočítaj späť do súradníc výrezu.
        val longEdge = maxOf(crop.width, crop.height)
        var up: Bitmap = crop
        var upScale = 1f
        if (longEdge in 1 until MIN_LANDMARK_EDGE) {
            upScale = MIN_LANDMARK_EDGE.toFloat() / longEdge
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
            landmarker.landmarks5(up, FloatArray(target.size) { target[it] * upScale })
        } catch (e: Throwable) {
            null
        }
        if (up !== crop) up.recycle()
        if (ptsUp != null) {
            val pts = if (upScale != 1f) FloatArray(ptsUp.size) { ptsUp[it] / upScale } else ptsUp
            return Emb(embedder.embed5(crop, pts), M5)
        }

        // 2) 2-bodové zarovnanie cez BlazeFace na výreze — tvár, ktorá sedí na očakávaný rámček
        //    (predtým tvár s najvyšším skóre, aj keď to bol sused).
        if (detector != null) {
            val found = try {
                detector.detect(crop)
            } catch (e: Throwable) {
                emptyList()
            }
            val i = FaceSelect.bestMatch(found.map { it.box() }, target)
            if (i >= 0) {
                val f = found[i]
                return Emb(embedder.embed(crop, f), if (f.hasEyes) M2 else MR)
            }
        }

        // 3) posledná možnosť: číre zmenšenie (nezarovnané).
        return Emb(embedder.embedResized(crop), MR)
    }
}
