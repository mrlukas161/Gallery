package org.fossify.gallery.faces

import android.graphics.Bitmap

// Spoločná cesta na vytvorenie odtlačku tváre (odtlačky v2, FaceEmbedder.PIPELINE_MODEL_ID). Poradie kvality:
//  1) 5-bodové zarovnanie (FaceLandmarker, stredy očí) — malé výrezy najprv zväčší, aby vnútorný detektor
//     landmarkera nezlyhal; z až 3 tvárí vo výreze berie tú, ktorá sedí na detekciu (B2);
//  2) 2-bodové zarovnanie (BlazeFace oči) — rovnaký ArcFace priestor ako embed5, tiež tvár podľa detekcie;
//  3) číre zmenšenie (bez zarovnania) — až posledná možnosť.
// Výrez sa berie z plného rozlíšenia fotky, keď je k dispozícii (FullResCrop, B3).
object FaceAligner {
    const val M5 = AlignMethod.M5 // 5-bodové zarovnanie
    const val M2 = AlignMethod.M2 // 2-bodové zarovnanie (BlazeFace)
    const val MR = AlignMethod.MR // resize bez zarovnania

    // okraj výrezu okolo rámčeka detekcie (podiel šírky/výšky)
    private const val MARGIN = 0.3f

    // landmarker potrebuje tvár aspoň takto veľkú (vnútorný BlazeFace 192×192)
    private const val MIN_LANDMARK_EDGE = 256

    // E1: points = 5 bodov tváre (M5, súradnice výrezu) pre natočenie hlavy, sharpness = variancia Laplaciánu
    // výrezu tváre na 112 px (FaceQuality); null = nezistené (napr. 2-bodové zarovnanie nad celou fotkou)
    class Emb(val vec: FloatArray, val method: Int, val points: FloatArray? = null, val sharpness: Float? = null)

    // Rozbor výrezu bez odtlačku (rovnaké rozhodovanie ako embedCrop): metóda zarovnania, body tváre alebo
    // detekcia pre 2-bodové zarovnanie a ostrosť. Používa ho aj dopočítanie kvality starých tvárí
    // (FaceMetaBackfill), ktoré odtlačok nemení.
    class CropAnalysis(
        val method: Int,
        val points: FloatArray?,
        val detected: FaceDetectionHelper.DetectedFace?,
        val sharpness: Float?,
    )

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
        val a = analyzeCrop(crop, landmarker, detector, expected)
        val points = a.points
        val detected = a.detected
        val vec = when {
            points != null -> embedder.embed5(crop, points)
            detected != null -> embedder.embed(crop, detected)
            else -> embedder.embedResized(crop)
        }
        return Emb(vec, a.method, points, a.sharpness)
    }

    // E1 — rozbor tváre na celej (upright) fotke bez odtlačku, rovnakou cestou ako embedDetectedEmb (výrez
    // z plného rozlíšenia, inak zo zmenšenej fotky). null = výrez sa nedá urobiť.
    fun analyzeDetected(
        bmp: Bitmap,
        face: FaceDetectionHelper.DetectedFace,
        landmarker: FaceLandmarkHelper,
        detector: FaceDetectionHelper?,
        full: FullResCrop? = null,
    ): CropAnalysis? {
        val fr = try {
            full?.crop(face.left, face.top, face.right, face.bottom, MARGIN)
        } catch (e: Throwable) {
            null
        }
        if (fr != null) {
            try {
                return analyzeCrop(fr.bitmap, landmarker, detector, fr.expected)
            } catch (ignored: Throwable) {
                // pokračuj zmenšenou fotkou
            } finally {
                fr.bitmap.recycle()
            }
        }
        val region = FullResMath.regionWithMargin(
            face.left, face.top, face.right, face.bottom, MARGIN, bmp.width, bmp.height,
        ) ?: return null
        val crop = cropOf(bmp, region) ?: return null
        val expected = floatArrayOf(
            (face.left - region[0]).toFloat(), (face.top - region[1]).toFloat(),
            (face.right - region[0]).toFloat(), (face.bottom - region[1]).toFloat(),
        )
        return try {
            analyzeCrop(crop, landmarker, detector, expected)
        } catch (ignored: Throwable) {
            null
        } finally {
            if (crop !== bmp) crop.recycle()
        }
    }

    // metóda zarovnania výrezu: 5 bodov (landmarker), 2 body (BlazeFace na výreze) alebo len zmenšenie
    fun analyzeCrop(
        crop: Bitmap,
        landmarker: FaceLandmarkHelper,
        detector: FaceDetectionHelper? = null,
        expected: FloatArray? = null,
    ): CropAnalysis {
        val target = expected ?: FaceSelect.centralBox(crop.width, crop.height)
        val sharpness = sharpnessOf(crop, target)
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
            return CropAnalysis(M5, pts, null, sharpness)
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
                return CropAnalysis(if (f.hasEyes) M2 else MR, null, f, sharpness)
            }
        }

        // 3) posledná možnosť: číre zmenšenie (nezarovnané).
        return CropAnalysis(MR, null, null, sharpness)
    }

    // E1 — ostrosť: rámček tváre z výrezu zmenšený na 112×112 a variancia Laplaciánu (FaceQuality)
    @Suppress("MagicNumber")
    fun sharpnessOf(src: Bitmap, box: FloatArray): Float? {
        val l = box[0].toInt().coerceIn(0, src.width - 1)
        val t = box[1].toInt().coerceIn(0, src.height - 1)
        val r = box[2].toInt().coerceIn(l + 1, src.width)
        val b = box[3].toInt().coerceIn(t + 1, src.height)
        val edge = FaceQuality.SHARP_EDGE
        return try {
            val face = Bitmap.createBitmap(src, l, t, r - l, b - t)
            val scaled = Bitmap.createScaledBitmap(face, edge, edge, true)
            if (face !== src && face !== scaled) face.recycle()
            val px = IntArray(edge * edge)
            scaled.getPixels(px, 0, edge, 0, 0, edge, edge)
            if (scaled !== src) scaled.recycle()
            FaceQuality.laplacianVariance(px, edge, edge)
        } catch (ignored: Throwable) {
            null
        }
    }
}
