package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap

// Detekcia, odtlačky v2 a zápis tvárí jednej NOVEJ fotky (FaceIndexer).
//  - Ukladajú sa len tváre, ktoré prejdú FaceFilter.isUsable. Slabšie detekcie sa neukladajú vôbec:
//    všetky pohľady (Ľudia, návrhy, automatika) ich aj tak skrývajú cez FaceFilter.isGood, „Prepracovať
//    tváre" ich tiež vynecháva, odtlačok (výrez v plnom rozlíšení + 2 behy modelu) by stál zbytočne
//    a uložený riadok sa už nikdy nesmie zmazať (id môže niesť menovku) — ostal by navždy ako šum
//    (falošné detekcie na predmetoch). Ak sa prah niekedy zníži, doplní ich „Prepracovať tváre".
//  - Kým beží prepočet na v2 (FaceEmbedVersion.isPending), sú vo faces.db ešte v1 vektory: nová tvár sa
//    preto uloží BEZ odtlačku a jej v2 vektor čaká vo faces_v2.db — do faces.db ho zapíše až prepnutie,
//    aby sa v1 a v2 nikdy neporovnávali.
//  - E1: každá nová tvár dostane skóre kvality (FaceQuality), natočenie hlavy, ostrosť, metódu zarovnania,
//    box_space = upright v2, verziu odtlačku a dátum nasnímania fotky.
//  - E3 ([multiScale]): navyše tváre z dlaždíc (FaceMultiScale); tie sa uložia len nad bránou kvality.
object FaceStore {
    @Suppress("LongParameterList")
    fun detectAndInsert(
        context: Context,
        dao: FaceDao,
        path: String,
        mediaStoreId: Long,
        bmp: Bitmap,
        maxDecode: Int,
        detector: FaceDetectionHelper,
        landmarker: FaceLandmarkHelper,
        embedder: FaceEmbedder,
        sample: Int = 1,
        multiScale: Boolean = false,
    ): Int {
        val base = detector.detect(bmp).filter { FaceFilter.isUsable(it) }
        val extra = if (multiScale) {
            try {
                FaceMultiScale.extraFaces(path, bmp, detector, base)
            } catch (ignored: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val all = base + extra
        if (all.isEmpty()) return 0
        val full = FullResCrop.open(path, maxDecode, bmp.width, bmp.height)
        val embs = try {
            all.map { FaceAligner.embedDetectedEmb(bmp, it, landmarker, embedder, detector, full) }
        } finally {
            full?.close()
        }
        val meta = all.mapIndexed { i, d -> metaOf(d, embs[i], sample) }
        // E3: tvár nájdená len v dlaždici sa uloží, len keď prejde bránou kvality (známe skóre, ako B5)
        val kept = all.indices.filter { i ->
            val q = meta[i].quality
            i < base.size || (q != null && FaceQuality.passes(q))
        }
        if (kept.isEmpty()) return 0
        val pending = FaceEmbedVersion.isPending(context)
        val now = System.currentTimeMillis()
        val takenAt = try {
            FaceTakenAt.of(context, path, mediaStoreId)
        } catch (ignored: Throwable) {
            null
        }
        val vectors = kept.map { embs[it]?.let { e -> FaceEmbedder.toBytes(e.vec) } }
        dao.insertFaces(
            kept.mapIndexed { order, i ->
                val f = all[i]
                val m = meta[i]
                val vector = if (pending) null else vectors[order]
                FaceEntity(
                    id = null, mediaFullPath = path, mediaStoreId = mediaStoreId, faceIndex = order,
                    bboxLeft = f.left, bboxTop = f.top, bboxRight = f.right, bboxBottom = f.bottom,
                    score = f.score,
                    embedding = vector,
                    detectedAt = now,
                    quality = m.quality, yaw = m.yaw, pitch = m.pitch, blur = m.sharpness, alignMethod = m.method,
                    boxSpace = FaceBoxSpace.UPRIGHT_V2,
                    embedVersion = if (vector != null) FaceEmbedVersion.PIPELINE_VERSION else null,
                    takenAt = takenAt,
                )
            }
        )
        if (pending) stage(context, dao, path, vectors)
        return kept.size
    }

    // E1 — údaje o kvalite jednej tváre
    class Meta(val quality: Float?, val yaw: Float?, val pitch: Float?, val sharpness: Float?, val method: Int?)

    // [sample] = koľkokrát je originál väčší ako bitmapa, v ktorej je rámček (veľkosť tváre v pôvodnom rozlíšení)
    fun metaOf(d: FaceDetectionHelper.DetectedFace, emb: FaceAligner.Emb?, sample: Int): Meta = if (emb == null) {
        Meta(null, null, null, null, null)
    } else {
        metaOf(d, emb.method, emb.points, emb.sharpness, sample)
    }

    fun metaOf(
        d: FaceDetectionHelper.DetectedFace,
        method: Int,
        points: FloatArray?,
        sharpness: Float?,
        sample: Int,
    ): Meta {
        val pose = points?.let { FaceQuality.pose(it) }
        val width = (d.right - d.left).toFloat() * sample.coerceAtLeast(1)
        val quality = FaceQuality.score(width, sharpness, pose?.get(0), pose?.get(1), method)
        return Meta(quality, pose?.get(0), pose?.get(1), sharpness, method)
    }

    // v2 vektory práve uložených tvárí (podľa poradia na fotke) do faces_v2.db
    private fun stage(context: Context, dao: FaceDao, path: String, vectors: List<ByteArray?>) {
        try {
            val byIndex = dao.getFacesForPath(path).associateBy { it.faceIndex }
            val items = vectors.mapIndexedNotNull { i, v ->
                val id = byIndex[i]?.id
                if (id != null && v != null) Triple(id, v, null as ByteArray?) else null
            }
            FaceV2Staging.get(context).putDone(items)
        } catch (ignored: Throwable) {
            // prepočet (FaceReembedV2) tieto tváre pred prepnutím spracuje sám
        }
    }
}
