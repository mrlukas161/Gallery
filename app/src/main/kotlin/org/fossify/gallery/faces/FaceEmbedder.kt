package org.fossify.gallery.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

// MobileFaceNet odtlačok tváre (TFLite). Vstup = 112x112 zarovnaná tvár, výstup = vektor (L2-normalizovaný).
class FaceEmbedder(context: Context) {
    private val interpreter: Interpreter = Interpreter(loadModel(context), Interpreter.Options())
    val outputSize: Int = interpreter.getOutputTensor(0).shape().last()
    private val inputSize = 112

    fun embed(source: Bitmap, face: FaceDetectionHelper.DetectedFace): FloatArray {
        val aligned = alignFace(source, face)
        val result = runModel(aligned)
        aligned.recycle()
        return result
    }

    private fun alignFace(source: Bitmap, face: FaceDetectionHelper.DetectedFace): Bitmap {
        val out = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (face.hasEyes) {
            // 2-bodové zarovnanie: oko s menším x -> ľavá šablónová pozícia
            val firstIsLeft = face.eye1X <= face.eye2X
            val lx = if (firstIsLeft) face.eye1X else face.eye2X
            val ly = if (firstIsLeft) face.eye1Y else face.eye2Y
            val rx = if (firstIsLeft) face.eye2X else face.eye1X
            val ry = if (firstIsLeft) face.eye2Y else face.eye1Y
            val src = floatArrayOf(lx, ly, rx, ry)
            val dst = floatArrayOf(38.3f, 51.7f, 73.5f, 51.5f)
            val matrix = Matrix()
            matrix.setPolyToPoly(src, 0, dst, 0, 2)
            canvas.drawBitmap(source, matrix, paint)
        } else {
            // fallback bez očí: orež bbox a zmenši na 112x112
            val l = face.left.coerceIn(0, source.width)
            val t = face.top.coerceIn(0, source.height)
            val r = face.right.coerceIn(l, source.width)
            val b = face.bottom.coerceIn(t, source.height)
            if (r > l && b > t) {
                canvas.drawBitmap(source, Rect(l, t, r, b), Rect(0, 0, inputSize, inputSize), paint)
            }
        }
        return out
    }

    private fun runModel(face112: Bitmap): FloatArray {
        val input = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        face112.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixels) {
            input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        input.rewind()
        val output = Array(1) { FloatArray(outputSize) }
        interpreter.run(input, output)
        return l2normalize(output[0])
    }

    fun close() {
        try {
            interpreter.close()
        } catch (ignored: Exception) {
        }
    }

    companion object {
        fun toBytes(vector: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.nativeOrder())
            vector.forEach { bb.putFloat(it) }
            return bb.array()
        }

        fun toFloats(bytes: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            return FloatArray(bytes.size / 4) { bb.float }
        }

        private fun l2normalize(v: FloatArray): FloatArray {
            var sum = 0f
            for (x in v) sum += x * x
            val norm = sqrt(sum).coerceAtLeast(1e-9f)
            return FloatArray(v.size) { v[it] / norm }
        }

        private fun loadModel(context: Context): MappedByteBuffer {
            val afd = context.assets.openFd("face/mobilefacenet.tflite")
            val inputStream = FileInputStream(afd.fileDescriptor)
            val channel = inputStream.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }
}
