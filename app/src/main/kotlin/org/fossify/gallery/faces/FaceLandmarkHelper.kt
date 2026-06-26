package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

// MediaPipe FaceLandmarker — presné body tváre pre 5-bodové zarovnanie (oči/nos/kútiky úst).
class FaceLandmarkHelper(context: Context) {
    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder().setModelAssetPath("face/face_landmarker.task").build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false)
            .build(),
    )

    // 5 bodov v PIXLOCH daného bitmapu v poradí [pravé oko, ľavé oko, nos, ľavý kútik úst, pravý kútik úst],
    // alebo null ak tvár nenájdená.
    fun landmarks5(bitmap: Bitmap): FloatArray? {
        return try {
            val result = landmarker.detect(BitmapImageBuilder(bitmap).build())
            val faces = result.faceLandmarks()
            if (faces.isEmpty()) return null
            val lm = faces[0]
            if (lm.size <= 291) return null
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            floatArrayOf(
                lm[33].x() * w, lm[33].y() * h,
                lm[263].x() * w, lm[263].y() * h,
                lm[1].x() * w, lm[1].y() * h,
                lm[61].x() * w, lm[61].y() * h,
                lm[291].x() * w, lm[291].y() * h,
            )
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
}
