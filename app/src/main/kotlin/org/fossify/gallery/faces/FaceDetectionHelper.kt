package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

// Wrapper nad MediaPipe FaceDetector (BlazeFace). Vracia bbox + body očí (na zarovnanie pred odtlačkom).
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
        val eye1X: Float, val eye1Y: Float, val eye2X: Float, val eye2Y: Float, val hasEyes: Boolean,
    )

    fun detect(bitmap: Bitmap): List<DetectedFace> {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = detector.detect(mpImage)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return result.detections().map { detection ->
            val box = detection.boundingBox()
            val score = detection.categories().firstOrNull()?.score() ?: 0f
            var e1x = 0f
            var e1y = 0f
            var e2x = 0f
            var e2y = 0f
            var hasEyes = false
            try {
                val kps = detection.keypoints().orElse(null)
                if (kps != null && kps.size >= 2) {
                    e1x = kps[0].x() * w
                    e1y = kps[0].y() * h
                    e2x = kps[1].x() * w
                    e2y = kps[1].y() * h
                    hasEyes = true
                }
            } catch (ignored: Throwable) {
            }
            DetectedFace(
                box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt(), score,
                e1x, e1y, e2x, e2y, hasEyes,
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
