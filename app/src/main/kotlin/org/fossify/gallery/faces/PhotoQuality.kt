package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.fossify.gallery.helpers.ImageStats
import org.fossify.gallery.helpers.Sharpness

// Hodnotenie kvality fotky pre porovnávač: expozícia (clipnuté tiene/svetlá) + tváre — počet tvárí,
// zavreté oči a úsmev z geometrie landmarkov (EAR pomer oka, zdvih kútikov úst).
// CompareAnalysis volá faces() nad už dekódovanou vzpriamenou bitmapou (jedno dekódovanie na fotku)
// a model vytvára raz na beh až po získaní miesta v HeavyGate.
// Blendshapes sa NEžiadajú: balík face/face_landmarker.task (2023-04) obsahuje len face_detector,
// face_landmarks_detector a geometry_pipeline — bez face_blendshapes.tflite. S požiadavkou na blendshapes
// MediaPipe graf pre chýbajúci model nevytvorí (výnimka → landmarker null → porovnávač nevidel žiadne
// tváre; potvrdiť na zariadení), a vetva s blendshapes sa preto nikdy nepoužila.
// Nie thread-safe — volať z jedného pozadia; close() po skončení.
class PhotoQuality(context: Context) {

    data class Result(
        val exposure: Double,   // 0..1 (1 = dobrá expozícia)
        val faces: Int,
        val closedEyes: Int,    // počet tvárí so zavretými očami
        val smiles: Int,        // počet usmiatych tvárí
    )

    data class Faces(val faces: Int, val closedEyes: Int, val smiles: Int)

    private val landmarker: FaceLandmarker? = try {
        FaceLandmarker.createFromOptions(
            context.applicationContext,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face/face_landmarker.task").build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(MAX_FACES)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .build(),
        )
    } catch (ignored: Throwable) {
        null
    }

    // false = model sa nepodarilo načítať (tváre sa nehodnotia)
    val isAvailable: Boolean get() = landmarker != null

    fun analyze(path: String): Result {
        val bmp = decode(path) ?: return Result(NEUTRAL_EXPOSURE, 0, 0, 0)
        val exposure = ImageStats.exposure(Sharpness.grayOf(bmp).pixels)
        val f = faces(bmp)
        bmp.recycle()
        return Result(exposure, f?.faces ?: 0, f?.closedEyes ?: 0, f?.smiles ?: 0)
    }

    // tváre na VZPRIAMENEJ bitmape (UprightDecoder); null = model nie je k dispozícii alebo detekcia zlyhala
    fun faces(bmp: Bitmap): Faces? {
        val lm = landmarker ?: return null
        return try {
            val found = lm.detect(BitmapImageBuilder(bmp).build()).faceLandmarks()
            Faces(found.size, found.count { eyesClosed(it) }, found.count { smiling(it) })
        } catch (ignored: Throwable) {
            null
        }
    }

    // obe oči zavreté: pomer výšky otvorenia oka k jeho šírke (EAR) pod EAR_CLOSED
    private fun eyesClosed(lm: List<NormalizedLandmark>): Boolean {
        if (lm.size <= R_TOP) return false
        val earL = ear(lm[L_TOP], lm[L_BOTTOM], lm[L_OUTER], lm[L_INNER])
        val earR = ear(lm[R_TOP], lm[R_BOTTOM], lm[R_INNER], lm[R_OUTER])
        return earL < EAR_CLOSED && earR < EAR_CLOSED
    }

    // úsmev: kútiky úst vyššie než stred pier vzhľadom na šírku úst
    private fun smiling(lm: List<NormalizedLandmark>): Boolean {
        if (lm.size <= R_TOP) return false
        val mouthW = dist(lm[MOUTH_LEFT], lm[MOUTH_RIGHT])
        if (mouthW <= MIN_DIST) return false
        val lipMidY = (lm[LIP_TOP].y() + lm[LIP_BOTTOM].y()) / 2f
        val cornersY = (lm[MOUTH_LEFT].y() + lm[MOUTH_RIGHT].y()) / 2f
        return (lipMidY - cornersY) / mouthW > SMILE_LIFT
    }

    private fun ear(
        top: NormalizedLandmark,
        bottom: NormalizedLandmark,
        c1: NormalizedLandmark,
        c2: NormalizedLandmark,
    ): Float {
        val w = dist(c1, c2)
        if (w < MIN_DIST) return 1f
        return kotlin.math.abs(bottom.y() - top.y()) / w
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = b.x() - a.x()
        val dy = b.y() - a.y()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // VŽDY „na stojato" podľa EXIF (ako indexer tvárí) — landmarker zvláda náklon len ~±30–45°,
    // takže na neotočenej fotke na výšku tváre nenašiel a zavreté oči/úsmev sa nikdy nezapočítali
    private fun decode(path: String): Bitmap? = UprightDecoder.decode(path, DECODE_SIZE)?.bitmap

    fun close() {
        try {
            landmarker?.close()
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        const val DECODE_SIZE = 800
        private const val MAX_FACES = 6
        private const val NEUTRAL_EXPOSURE = 0.5
        private const val EAR_CLOSED = 0.16f
        private const val SMILE_LIFT = 0.055f
        private const val MIN_DIST = 1e-5f

        // body MediaPipe Face Mesh: viečka a kútiky ľavého/pravého oka, kútiky úst, stred pier
        private const val L_TOP = 159
        private const val L_BOTTOM = 145
        private const val L_OUTER = 33
        private const val L_INNER = 133
        private const val R_TOP = 386
        private const val R_BOTTOM = 374
        private const val R_INNER = 362
        private const val R_OUTER = 263
        private const val MOUTH_LEFT = 61
        private const val MOUTH_RIGHT = 291
        private const val LIP_TOP = 13
        private const val LIP_BOTTOM = 14
    }
}
