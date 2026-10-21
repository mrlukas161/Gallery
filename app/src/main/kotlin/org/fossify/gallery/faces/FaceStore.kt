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
object FaceStore {
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
    ): Int {
        val usable = detector.detect(bmp).filter { FaceFilter.isUsable(it) }
        if (usable.isEmpty()) return 0
        val full = FullResCrop.open(path, maxDecode, bmp.width, bmp.height)
        val vectors = try {
            usable.map { FaceAligner.embedDetected(bmp, it, landmarker, embedder, detector, full) }
        } finally {
            full?.close()
        }
        val pending = FaceEmbedVersion.isPending(context)
        val now = System.currentTimeMillis()
        dao.insertFaces(
            usable.mapIndexed { i, f ->
                FaceEntity(
                    id = null, mediaFullPath = path, mediaStoreId = mediaStoreId, faceIndex = i,
                    bboxLeft = f.left, bboxTop = f.top, bboxRight = f.right, bboxBottom = f.bottom,
                    score = f.score,
                    embedding = if (pending) null else vectors[i],
                    detectedAt = now,
                )
            }
        )
        if (pending) stage(context, dao, path, vectors)
        return usable.size
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
