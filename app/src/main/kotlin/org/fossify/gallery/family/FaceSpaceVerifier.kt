package org.fossify.gallery.family

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.fossify.gallery.faces.FaceAligner
import org.fossify.gallery.faces.FaceDetectionHelper
import org.fossify.gallery.faces.FaceEmbedVersion
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FaceEntity
import org.fossify.gallery.faces.FaceLandmarkHelper
import org.fossify.gallery.faces.FullResCrop
import org.fossify.gallery.faces.UprightDecoder
import java.io.Closeable

// D3: overenie priestoru rámčeka tváre na otočenej fotke odtlačkom. Pre obe hypotézy (upright a surová)
// sa z výrezu prepočíta odtlačok rovnakou cestou ako pri indexovaní (FaceAligner.embedDetectedEmb, odtlačky
// v2 vrátane výrezu z plného rozlíšenia) a porovná sa s uloženým (FaceSpaceRules.fromCosines). Surová
// hypotéza sa počíta z neotočenej bitmapy (tak vznikali staré záznamy) aj z jej prepočtu do upright bitmapy,
// lepší z oboch.
//
// Porovnávajú sa len odtlačky rovnakej verzie: prepočet ide cestou FaceEmbedder.PIPELINE_MODEL_ID, takže
// overuje sa, len keď uložené odtlačky sú v tej istej verzii (FaceEmbedder.MODEL_ID) a prepočet odtlačkov
// v2 nebeží (FaceEmbedVersion.isPending) — inak canVerifyNow() = false a overenie sa odloží (tvár sa
// zatiaľ nepošle). Výsledok sa ukladá s MODEL_ID, pri inej verzii sa overuje znova. Uložený vektor inej
// dĺžky = BROKEN.
//
// Modely sa načítajú lenivo až pri prvom overení (len tváre na otočených fotkách s nejasným priestorom,
// len pri odosielaní). Jedna inštancia na jeden beh, nie je thread-safe; po použití close().
class FaceSpaceVerifier(context: Context) : Closeable {
    private val app = context.applicationContext
    private var embedder: FaceEmbedder? = null
    private var landmarker: FaceLandmarkHelper? = null
    private var detector: FaceDetectionHelper? = null
    private var failed = false

    // B: uložené odtlačky sú v tej istej verzii ako prepočet a prepočet v2 práve nebeží
    @Suppress("TooGenericExceptionCaught")
    fun canVerifyNow(): Boolean = try {
        FaceEmbedder.MODEL_ID == FaceEmbedder.PIPELINE_MODEL_ID && !FaceEmbedVersion.isPending(app)
    } catch (e: RuntimeException) {
        android.util.Log.w(TAG, "stav odtlačkov sa nedá prečítať", e)
        false
    }

    // null = overiť sa nedá (chýba odtlačok, súbor, modely); inak UPRIGHT/RAW/BROKEN
    @Suppress("TooGenericExceptionCaught")
    internal fun verify(face: FaceEntity, geometry: PhotoGeometry): BoxSpace? {
        val stored = face.embedding?.let { FaceEmbedder.toFloats(it) }?.takeIf { it.isNotEmpty() } ?: return null
        if (!ensureModels()) return null
        val emb = embedder ?: return null
        if (stored.size != emb.outputSize) return BoxSpace.BROKEN // iný model, vektory sa nemiešajú
        return try {
            val (upright, rawMapped) = cosinesOnUpright(face, geometry, stored)
            val raw = maxOfNullable(cosineRawDecoded(face, geometry, stored), rawMapped)
            FaceSpaceRules.fromCosines(upright, raw)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "overenie priestoru tváre zlyhalo", e)
            null
        }
    }

    // nad bitmapou z UprightDecoder (FaceIndexer od 0.47): hypotéza UPRIGHT (rámček ako je) a hypotéza RAW
    // prepočítaná do upright priestoru (odtlačok mohol prepočítať RefreshFaces z upright výrezu)
    private fun cosinesOnUpright(face: FaceEntity, g: PhotoGeometry, stored: FloatArray): Pair<Float?, Float?> {
        val bmp = UprightDecoder.decode(face.mediaFullPath, FaceMatch.DECODE_MAX_SIZE)?.bitmap ?: return null to null
        val full = FullResCrop.open(face.mediaFullPath, FaceMatch.DECODE_MAX_SIZE, bmp.width, bmp.height)
        return try {
            val upright = embedBox(bmp, face.bboxLeft, face.bboxTop, face.bboxRight, face.bboxBottom, full)
                ?.let { FaceSpaceRules.cosine(it, stored) }
            val box = StoredBox(face.bboxLeft, face.bboxTop, face.bboxRight, face.bboxBottom, face.detectedAt)
            val mapped = FaceSpaceRules.relBox(box, g.rawWidth, g.rawHeight, g.transform, BoxSpace.RAW)
                ?.toAbsolute(bmp.width, bmp.height)
                ?.let { embedBox(bmp, it) }
                ?.let { FaceSpaceRules.cosine(it, stored) }
            upright to mapped
        } finally {
            full?.close()
            bmp.recycle()
        }
    }

    // hypotéza RAW: rámček nad neotočenou bitmapou (staré FaceIndexer pred 0.47)
    private fun cosineRawDecoded(face: FaceEntity, g: PhotoGeometry, stored: FloatArray): Float? {
        val sample = RelBox.sampleSize(g.rawWidth, g.rawHeight, FaceMatch.DECODE_MAX_SIZE)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(face.mediaFullPath, options) ?: return null
        return try {
            embedBox(bmp, face.bboxLeft, face.bboxTop, face.bboxRight, face.bboxBottom)
                ?.let { FaceSpaceRules.cosine(it, stored) }
        } finally {
            bmp.recycle()
        }
    }

    private fun embedBox(bmp: Bitmap, box: IntArray): FloatArray? =
        embedBox(bmp, box.first(), box[1], box[box.size - 2], box.last())

    // rovnaká cesta ako FaceIndexer/FaceReembedV2 (detekcia bez bodov očí, ako pri tvári bez novej detekcie)
    private fun embedBox(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, full: FullResCrop? = null): FloatArray? {
        val emb = embedder ?: return null
        val lm = landmarker ?: return null
        if (r <= l || b <= t) return null
        val face = FaceDetectionHelper.DetectedFace(l, t, r, b, 1f, 0f, 0f, 0f, 0f, false)
        return FaceAligner.embedDetectedEmb(bmp, face, lm, emb, detector, full)?.vec
    }

    @Suppress("TooGenericExceptionCaught")
    private fun ensureModels(): Boolean {
        if (embedder != null) return true
        if (failed) return false
        return try {
            embedder = FaceEmbedder(app)
            landmarker = FaceLandmarkHelper(app)
            detector = FaceDetectionHelper(app)
            true
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "modely tvárí sa nedajú načítať", e)
            failed = true
            close()
            false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        try {
            detector?.close()
            landmarker?.close()
            embedder?.close()
        } catch (ignored: Throwable) {
        }
        detector = null
        landmarker = null
        embedder = null
    }

    private fun maxOfNullable(a: Float?, b: Float?): Float? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private companion object {
        const val TAG = "FaceSpaceVerifier"
    }
}
