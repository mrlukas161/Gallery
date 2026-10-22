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

// MobileFaceNet odtlačok tváre (TFLite). Vstup = 112x112 zarovnaná tvár, výstup = vektor (L2-normalizovaný).
// Odtlačky v2: zarovnanie zmenšuje zdroj postupne po polovici (antialiasing, B3) a výsledok je súčet
// odtlačku tváre a jej zrkadlového obrazu, L2-normalizovaný (B4, stabilnejšie pri selfie a natočení).
class FaceEmbedder(context: Context) {
    // 2 vlákna: odtlačok je malý model, viac vlákien len pridá réžiu a súperí s ostatnými úlohami
    private val interpreter: Interpreter = Interpreter(loadModel(context), Interpreter.Options().setNumThreads(THREADS))
    val outputSize: Int = interpreter.getOutputTensor(0).shape().last()
    private val inputSize = 112

    // vstup modelu sa alokuje raz na inštanciu (predtým nový priamy buffer pri každom odtlačku)
    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())

    // 2-bodové zarovnanie (oči z BlazeFace) do rovnakej ArcFace šablóny, bez očí len zmenšený rámček
    fun embed(source: Bitmap, face: FaceDetectionHelper.DetectedFace): FloatArray {
        if (face.hasEyes) {
            // oko s menším x -> ľavá šablónová pozícia
            val firstIsLeft = face.eye1X <= face.eye2X
            val src = if (firstIsLeft) {
                floatArrayOf(face.eye1X, face.eye1Y, face.eye2X, face.eye2Y)
            } else {
                floatArrayOf(face.eye2X, face.eye2Y, face.eye1X, face.eye1Y)
            }
            val dst = AlignMath.ARCFACE_112.copyOfRange(0, 4)
            return warpAndRun(source, AlignMath.similarity(src, dst))
        }
        val l = face.left.coerceIn(0, source.width)
        val t = face.top.coerceIn(0, source.height)
        val r = face.right.coerceIn(l, source.width)
        val b = face.bottom.coerceIn(t, source.height)
        if (r <= l || b <= t) return runModel(Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888), true)
        val crop = Bitmap.createBitmap(source, l, t, r - l, b - t)
        try {
            return embedResized(crop)
        } finally {
            if (crop !== source) crop.recycle()
        }
    }

    // 5-bodové zarovnanie (oči/nos/kútiky úst -> ArcFace šablóna) cez podobnostnú transformáciu.
    fun embed5(source: Bitmap, points: FloatArray): FloatArray =
        warpAndRun(source, AlignMath.similarity(points, AlignMath.ARCFACE_112))

    // Fallback bez bodov: len zmenší výrez na 112x112 (postupne po polovici).
    fun embedResized(source: Bitmap): FloatArray {
        var cur = source
        try {
            while (cur.width >= inputSize * 4 && cur.height >= inputSize * 4) cur = halve(cur, cur !== source)
            val out = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            Canvas(out).drawBitmap(cur, Rect(0, 0, cur.width, cur.height), Rect(0, 0, inputSize, inputSize), paint)
            return runModel(out, true)
        } finally {
            if (cur !== source) cur.recycle()
        }
    }

    // Zarovnanie podľa transformácie [a, b, tx, ty] (AlignMath.similarity). Pri silnom zmenšení sa zdroj
    // najprv zmenší po polovici (každý krok spriemeruje 2×2 pixely), aby jeden bilineárny krok nepreskakoval
    // pixely a odtlačok nezávisel od rozlíšenia výrezu.
    private fun warpAndRun(source: Bitmap, transform: FloatArray): FloatArray {
        val k = AlignMath.halvings(AlignMath.scaleOf(transform))
        var cur = source
        try {
            repeat(k) {
                if (cur.width >= 2 && cur.height >= 2) cur = halve(cur, cur !== source)
            }
            // presný pomer zmenšeného zdroja k pôvodnému (pri nepárnych rozmeroch nie je presne 2^k)
            val fx = source.width.toFloat() / cur.width
            val fy = source.height.toFloat() / cur.height
            val m = AlignMath.forScaledSource(transform, fx, fy)
            val out = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            // afinná 2×3 matica doplnená na 3×3
            val matrix = Matrix().apply { setValues(m + floatArrayOf(0f, 0f, 1f)) }
            Canvas(out).drawBitmap(cur, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            return runModel(out, true)
        } finally {
            if (cur !== source) cur.recycle()
        }
    }

    private fun halve(b: Bitmap, recycleInput: Boolean): Bitmap {
        val out = Bitmap.createScaledBitmap(b, maxOf(1, b.width / 2), maxOf(1, b.height / 2), true)
        if (recycleInput && out !== b) b.recycle()
        return out
    }

    // Odtlačok tváre + odtlačok jej zrkadlového obrazu (B4). Šablóna ArcFace je súmerná podľa x = 56,
    // takže zrkadlený zarovnaný obraz je opäť správne zarovnaný.
    private fun runModel(face112: Bitmap, recycle: Boolean): FloatArray {
        val pixels = IntArray(inputSize * inputSize)
        face112.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        if (recycle) face112.recycle()
        return AlignMath.combineTta(infer(pixels, false), infer(pixels, true))
    }

    // synchronized: zdieľaný vstupný buffer (inštanciu dnes používa vždy len jedno vlákno, toto je poistka)
    @Synchronized
    private fun infer(pixels: IntArray, mirrored: Boolean): FloatArray {
        input.clear()
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val p = pixels[y * inputSize + if (mirrored) inputSize - 1 - x else x]
                input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        input.rewind()
        val output = Array(1) { FloatArray(outputSize) }
        interpreter.run(input, output)
        return output[0]
    }

    fun close() {
        try {
            interpreter.close()
        } catch (ignored: Exception) {
        }
    }

    companion object {
        private const val THREADS = 2

        // Identifikátor odtlačkov ULOŽENÝCH vo faces.db a v používaných vzoroch (asset face/mobilefacenet.tflite,
        // vstup 112×112, L2-normalizovaný výstup). Prenáša sa s odtlačkami do druhého telefónu
        // (family.AnchorsSet.faceModelId, PhotoBundle, záloha osôb) — vektory rôznych verzií sa nesmú miešať.
        // Nové výpočty idú vždy cestou v2 (PIPELINE_MODEL_ID); kým jednorazový prepočet pri nabíjaní
        // (FaceReembedV2) nepreklopí faces.db, sú uložené vektory ešte v1 a táto hodnota vracia v1, aby sa
        // v1 vektory neposlali ako v2. Po prepnutí (a pri novej inštalácii) vracia v2.
        // PRI VÝMENE MODELU ALEBO ZMENE PREDSPRACOVANIA ZMEŇ PIPELINE_MODEL_ID A PRIDAJ PREPOČET.
        val MODEL_ID: String
            get() = FaceEmbedVersion.storedModelId()

        const val PIPELINE_MODEL_ID = EmbedVersionRules.MODEL_ID_V2

        fun toBytes(vector: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.nativeOrder())
            vector.forEach { bb.putFloat(it) }
            return bb.array()
        }

        fun toFloats(bytes: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            return FloatArray(bytes.size / 4) { bb.float }
        }

        private fun loadModel(context: Context): MappedByteBuffer {
            val afd = context.assets.openFd("face/mobilefacenet.tflite")
            val inputStream = FileInputStream(afd.fileDescriptor)
            val channel = inputStream.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }
}
