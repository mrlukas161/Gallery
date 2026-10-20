package org.fossify.gallery.family

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Balík hotových výsledkov k jednej fotke (F4, spec 2.3). Kľúč je obsahový kľúč fotky (ContentKey).
// Pole null = odosielateľ to ešte nespracoval (alebo sa to nesmie poslať) a prijímateľ si to spraví sám.
// Prázdna hodnota (žiadne tváre, prázdny text…) = spracované, nič sa nenašlo.
// Čistá logika bez Androidu (testovaná v PhotoBundleTest, org.json je v testoch skutočná knižnica).
data class PhotoBundle(
    val contentKey: String,
    val name: String,
    val mime: String,
    val size: Long,
    // dátum nasnímania z EXIF v ms (záloha FuzzyMatch), null = neznámy
    val takenAtMs: Long? = null,
    // verzia embeddera tvárí (FaceEmbedder.MODEL_ID); bez nej sa tváre neprevezmú
    val faceModelId: String? = null,
    val faces: List<BundleFace>? = null,
    val ocr: String? = null,
    val geo: BundleGeo? = null,
    // pHash (PhashIndexer); posiela sa vždy, keď sa dá, lebo slúži aj na záložnú zhodu
    val phash: Long? = null,
    val clip: BundleClip? = null,
    val qr: String? = null,
)

// Tvár v relatívnych súradniciach voči vzpriamenému obrázku (RelBox), odtlačok ako Base64 surového blobu
// FaceEntity.embedding (formát určuje faceModelId). familyUuid = rodinné UUID priradenej osoby.
data class BundleFace(
    val box: RelBox,
    val score: Float,
    val embeddingB64: String? = null,
    val familyUuid: String? = null,
    val manual: Boolean = false,
    val ignored: Boolean = false,
)

// hasGeo = false: fotka bola spracovaná, ale nemá GPS (rovnako ako GeoEntity)
data class BundleGeo(val lat: Double, val lon: Double, val hasGeo: Boolean)

// CLIP vektor ako Base64 blobu ClipEntity.embedding, modelId = FamilyModels.CLIP_MODEL_ID odosielateľa
data class BundleClip(val modelId: String, val vectorB64: String)

// Modely, ktorých výsledky sa medzi telefónmi porovnávajú.
object FamilyModels {
    // clip.db neeviduje model pri vektore. Appka má jediný CLIP priestor: ViT-B/32 (OpenAI) 512-dim
    // (kvantovaná aj pôvodná verzia z ClipModels dávajú ten istý priestor). Pri zmene modelu zmeň aj toto id.
    const val CLIP_MODEL_ID = "clip-vit-b32-openai-512"
}

// JSON formát balíka. Je súčasťou prenosu medzi telefónmi: kľúče len pridávať, nikdy nepremenovať.
object PhotoBundleCodec {
    const val VERSION = 1

    private const val V = "v"
    private const val KEY = "ck"
    private const val NAME = "name"
    private const val MIME = "mime"
    private const val SIZE = "size"
    private const val TAKEN = "taken"
    private const val FACE_MODEL = "fm"
    private const val FACES = "faces"
    private const val BOX = "b"
    private const val SCORE = "s"
    private const val EMBEDDING = "e"
    private const val UUID = "u"
    private const val MANUAL = "m"
    private const val IGNORED = "ig"
    private const val OCR = "ocr"
    private const val GEO = "geo"
    private const val LAT = "lat"
    private const val LON = "lon"
    private const val HAS = "has"
    private const val PHASH = "ph"
    private const val CLIP = "clip"
    private const val MODEL = "model"
    private const val QR = "qr"
    private const val BOX_VALUES = 4
    private const val LEFT = 0
    private const val TOP = 1
    private const val RIGHT = 2
    private const val BOTTOM = 3

    fun encode(b: PhotoBundle): String {
        val o = JSONObject()
            .put(V, VERSION)
            .put(KEY, b.contentKey)
            .put(NAME, b.name)
            .put(MIME, b.mime)
            .put(SIZE, b.size)
        b.takenAtMs?.let { o.put(TAKEN, it) }
        b.faceModelId?.let { o.put(FACE_MODEL, it) }
        b.faces?.let { faces -> o.put(FACES, JSONArray().also { a -> faces.forEach { a.put(faceJson(it)) } }) }
        b.ocr?.let { o.put(OCR, it) }
        b.geo?.let { o.put(GEO, JSONObject().put(LAT, it.lat).put(LON, it.lon).put(HAS, it.hasGeo)) }
        b.phash?.let { o.put(PHASH, it) }
        b.clip?.let { o.put(CLIP, JSONObject().put(MODEL, it.modelId).put(EMBEDDING, it.vectorB64)) }
        b.qr?.let { o.put(QR, it) }
        return o.toString()
    }

    // null = poškodený balík (chýba kľúč, zlé typy). Neznáme kľúče (novšia appka) sa ignorujú.
    fun decode(json: String): PhotoBundle? = try {
        val o = JSONObject(json)
        val key = o.getString(KEY)
        if (key.isEmpty()) {
            null
        } else {
            PhotoBundle(
                contentKey = key,
                name = o.optString(NAME),
                mime = o.optString(MIME),
                size = o.getLong(SIZE),
                takenAtMs = if (o.has(TAKEN)) o.getLong(TAKEN) else null,
                faceModelId = o.optStr(FACE_MODEL),
                faces = o.optJSONArray(FACES)?.let { a -> (0 until a.length()).map { face(a.getJSONObject(it)) } },
                ocr = o.optStr(OCR),
                geo = o.optJSONObject(GEO)?.let { BundleGeo(it.getDouble(LAT), it.getDouble(LON), it.getBoolean(HAS)) },
                phash = if (o.has(PHASH)) o.getLong(PHASH) else null,
                clip = o.optJSONObject(CLIP)?.let { BundleClip(it.getString(MODEL), it.getString(EMBEDDING)) },
                qr = o.optStr(QR),
            )
        }
    } catch (ignored: JSONException) {
        null
    } catch (ignored: IllegalArgumentException) {
        null
    }

    private fun faceJson(f: BundleFace): JSONObject {
        val box = JSONArray()
            .put(f.box.left.toDouble())
            .put(f.box.top.toDouble())
            .put(f.box.right.toDouble())
            .put(f.box.bottom.toDouble())
        val score = if (f.score.isFinite()) f.score.toDouble() else 0.0 // NaN v JSON neexistuje
        val o = JSONObject().put(BOX, box).put(SCORE, score)
        f.embeddingB64?.let { o.put(EMBEDDING, it) }
        f.familyUuid?.let { o.put(UUID, it) }
        if (f.manual) o.put(MANUAL, true)
        if (f.ignored) o.put(IGNORED, true)
        return o
    }

    private fun face(o: JSONObject): BundleFace {
        val b = o.getJSONArray(BOX)
        require(b.length() == BOX_VALUES) { "box" }
        val box = RelBox(
            b.getDouble(LEFT).toFloat(),
            b.getDouble(TOP).toFloat(),
            b.getDouble(RIGHT).toFloat(),
            b.getDouble(BOTTOM).toFloat(),
        )
        val valid = listOf(box.left, box.top, box.right, box.bottom).all { it in 0f..1f } &&
            box.right > box.left && box.bottom > box.top
        require(valid) { "box mimo 0–1" }
        return BundleFace(
            box = box,
            score = o.optDouble(SCORE, 0.0).toFloat(),
            embeddingB64 = o.optStr(EMBEDDING),
            familyUuid = o.optStr(UUID),
            manual = o.optBoolean(MANUAL),
            ignored = o.optBoolean(IGNORED),
        )
    }

    private fun JSONObject.optStr(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}
