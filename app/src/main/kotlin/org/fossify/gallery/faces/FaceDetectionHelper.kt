package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

// Tenký wrapper nad MediaPipe FaceDetector (BlazeFace). Vracia bounding boxy tvárí.
class FaceDetectionHelper(context: Context) {
    private val detector: FaceDetector = FaceDetector.createFromOptions(
        context,
        FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("face/blaze_face_short_range.tflite")
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setMinDetectionConfidence(0.5f)
            .build()
    )

    data class DetectedFace(
        val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Float,
    )

    fun detect(bitmap: Bitmap): List<DetectedFace> {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = detector.detect(mpImage)
        return result.detections().map { detection ->
            val box = detection.boundingBox()
            val score = detection.categories().firstOrNull()?.score() ?: 0f
            DetectedFace(
                left = box.left.toInt(),
                top = box.top.toInt(),
                right = box.right.toInt(),
                bottom = box.bottom.toInt(),
                score = score,
            )
        }
    }

    fun close() {
        try {
            detector.close()
        } catch (ignored: Exception) {
        }
    }
}
