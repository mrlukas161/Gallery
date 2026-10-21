package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

// MediaPipe FaceLandmarker — presné body tváre pre 5-bodové zarovnanie (oči/nos/kútiky úst).
// Až 3 tváre naraz: výrez okolo detekcie má okraj, takže na skupinovej fotke v ňom býva aj sused;
// berie sa tvár, ktorá najlepšie sedí na očakávaný rámček (FaceSelect, B2).
class FaceLandmarkHelper(context: Context) {
    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder().setModelAssetPath("face/face_landmarker.task").build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(MAX_FACES)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false)
            .build(),
    )

    // 5 bodov v PIXLOCH daného bitmapu v poradí šablóny ArcFace (AlignMath.fivePoints): oko vľavo na
    // obrázku, oko vpravo, nos, kútik úst vľavo, vpravo. Oči = stredy očí (dúhovka, inak priemer kútikov).
    // [expected] = očakávaný rámček tváre v pixloch bitmapu (null = stred bitmapu). null = tvár nenájdená
    // alebo žiadna nesedí na očakávaný rámček.
    fun landmarks5(bitmap: Bitmap, expected: FloatArray? = null): FloatArray? {
        return try {
            val result = landmarker.detect(BitmapImageBuilder(bitmap).build())
            val faces = result.faceLandmarks()
            if (faces.isEmpty()) return null
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val coords = faces.map { lm ->
                FloatArray(lm.size) { lm[it].x() * w } to FloatArray(lm.size) { lm[it].y() * h }
            }
            val target = expected ?: FaceSelect.centralBox(bitmap.width, bitmap.height)
            val pick = FaceSelect.bestMatch(coords.map { FaceSelect.boundsOf(it.first, it.second) }, target)
            if (pick < 0) return null
            AlignMath.fivePoints(coords[pick].first, coords[pick].second)
        } catch (e: Throwable) {
            null
        }
    }

    fun close() {
        try {
            landmarker.close()
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        private const val MAX_FACES = 3
    }
}
