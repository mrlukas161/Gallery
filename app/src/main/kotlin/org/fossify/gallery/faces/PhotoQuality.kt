package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.io.File

// Hodnotenie kvality fotky pre komparátor: expozícia (clipnuté tiene/svetlá) + tváre — zavreté oči
// a úsmev cez MediaPipe blendshapes (eyeBlink*, mouthSmile*); ak model blendshapes nevráti,
// geometrický fallback z landmarkov (EAR pomer oka, zdvih kútikov úst). Nie thread-safe — volať
// z jedného pozadia; close() po skončení.
class PhotoQuality(context: Context) {

    data class Result(
        val exposure: Double,   // 0..1 (1 = dobrá expozícia)
        val faces: Int,
        val closedEyes: Int,    // počet tvárí so zavretými očami
        val smiles: Int,        // počet usmiatych tvárí
    )

    private val landmarker: FaceLandmarker? = try {
        FaceLandmarker.createFromOptions(
            context.applicationContext,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face/face_landmarker.task").build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(6)
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(false)
                .build(),
        )
    } catch (e: Throwable) {
        null
    }

    fun analyze(path: String): Result {
        val bmp = decode(path) ?: return Result(0.5, 0, 0, 0)
        val exposure = exposureScore(bmp)
        var faces = 0
        var closed = 0
        var smiles = 0
        try {
            val res = landmarker?.detect(BitmapImageBuilder(bmp).build())
            if (res != null) {
                faces = res.faceLandmarks().size
                val bs = res.faceBlendshapes()
                if (bs.isPresent && bs.get().size == faces) {
                    for (shapes in bs.get()) {
                        var blinkL = 0f
                        var blinkR = 0f
                        var smileL = 0f
                        var smileR = 0f
                        for (c in shapes) {
                            when (c.categoryName()) {
                                "eyeBlinkLeft" -> blinkL = c.score()
                                "eyeBlinkRight" -> blinkR = c.score()
                                "mouthSmileLeft" -> smileL = c.score()
                                "mouthSmileRight" -> smileR = c.score()
                            }
                        }
                        if (blinkL > 0.5f && blinkR > 0.5f) closed++
                        if ((smileL + smileR) / 2f > 0.35f) smiles++
                    }
                } else {
                    // fallback z geometrie landmarkov
                    for (lm in res.faceLandmarks()) {
                        if (lm.size <= 386) continue
                        val earL = ear(lm[159].y(), lm[145].y(), lm[33].x(), lm[33].y(), lm[133].x(), lm[133].y())
                        val earR = ear(lm[386].y(), lm[374].y(), lm[362].x(), lm[362].y(), lm[263].x(), lm[263].y())
                        if (earL < 0.16 && earR < 0.16) closed++
                        // úsmev: kútiky úst (61, 291) vyššie než stred pier (13/14) vzhľadom na šírku úst
                        val mouthW = dist(lm[61].x(), lm[61].y(), lm[291].x(), lm[291].y())
                        if (mouthW > 1e-5) {
                            val lipMidY = (lm[13].y() + lm[14].y()) / 2f
                            val cornersY = (lm[61].y() + lm[291].y()) / 2f
                            if ((lipMidY - cornersY) / mouthW > 0.055) smiles++
                        }
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        bmp.recycle()
        return Result(exposure, faces, closed, smiles)
    }

    // pomer výšky otvorenia oka k jeho šírke (EAR)
    private fun ear(topY: Float, botY: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val w = dist(x1, y1, x2, y2)
        if (w < 1e-5) return 1f
        return kotlin.math.abs(botY - topY) / w
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // 0..1 podľa podielu clipnutých tmavých/svetlých pixelov (vzorkuje ~10 000 pixelov)
    private fun exposureScore(bmp: Bitmap): Double {
        return try {
            val stepX = (bmp.width / 100).coerceAtLeast(1)
            val stepY = (bmp.height / 100).coerceAtLeast(1)
            var total = 0
            var clipped = 0
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) {
                    val p = bmp.getPixel(x, y)
                    val luma = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    if (luma < 14 || luma > 243) clipped++
                    total++
                    x += stepX
                }
                y += stepY
            }
            if (total == 0) return 0.5
            (1.0 - 2.0 * clipped / total).coerceIn(0.0, 1.0)
        } catch (e: Throwable) {
            0.5
        }
    }

    private fun decode(path: String): Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > 800 || h / sample > 800) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    fun close() {
        try {
            landmarker?.close()
        } catch (ignored: Throwable) {
        }
    }
}
