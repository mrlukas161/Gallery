package org.fossify.gallery.clip

import android.content.Context
import android.graphics.Bitmap
import android.util.JsonReader
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.Collections
import kotlin.math.sqrt

// CLIP enkodér cez ONNX Runtime. Vizuál (fotka -> 512-dim) + text (dopyt -> 512-dim), oba L2-normalizované.
// Sessiony sa vytvárajú lazy z filesDir (stiahnuté modely); tokenizer/vocab z assets. Nie je thread-safe naprieč
// viacerými enkodérmi, ale jedna session je thread-safe pre run(). Volaj close().
class ClipEncoder(context: Context) {
    private val appCtx = context.applicationContext
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val visual: OrtSession? by lazy { createSession(ClipModels.visualFile(appCtx)) }
    private val textual: OrtSession? by lazy { createSession(ClipModels.textualFile(appCtx)) }

    private val tokenizer: ClipTokenizer by lazy { ClipTokenizer(loadVocab(), loadMerges()) }

    private fun createSession(f: File): OrtSession? = try {
        if (f.exists() && f.length() > 0) ortEnv.createSession(f.absolutePath, OrtSession.SessionOptions()) else null
    } catch (e: Throwable) {
        null
    }

    fun visualReady(): Boolean = visual != null
    fun textualReady(): Boolean = textual != null

    // fotka -> 512-dim L2-normalizovaný vektor (alebo null)
    fun encodeImage(bitmap: Bitmap): FloatArray? {
        val s = visual ?: return null
        return try {
            val data = preprocess(bitmap)
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(data), longArrayOf(1, 3, 224, 224)).use { t ->
                val name = s.inputNames.first()
                s.run(Collections.singletonMap(name, t)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = (res.get(0).value as Array<FloatArray>)[0]
                    l2(out)
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    // textový dopyt (už v angličtine, ASCII) -> 512-dim L2-normalizovaný vektor (alebo null)
    fun encodeText(query: String): FloatArray? {
        val s = textual ?: return null
        return try {
            val (ids, realLen) = tokenize(query)
            OnnxTensor.createTensor(ortEnv, IntBuffer.wrap(ids), longArrayOf(1, 77)).use { idsT ->
                val names = s.inputNames.toList()
                val feeds = HashMap<String, OnnxTensor>()
                feeds[names[0]] = idsT
                var maskT: OnnxTensor? = null
                if (names.size > 1) {
                    val mask = IntArray(77) { if (it < realLen) 1 else 0 }
                    maskT = OnnxTensor.createTensor(ortEnv, IntBuffer.wrap(mask), longArrayOf(1, 77))
                    feeds[names[1]] = maskT
                }
                try {
                    s.run(feeds).use { res ->
                        @Suppress("UNCHECKED_CAST")
                        val out = (res.get(0).value as Array<FloatArray>)[0]
                        l2(out)
                    }
                } finally {
                    maskT?.close()
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    fun close() {
        try {
            visual?.close()
        } catch (ignored: Throwable) {
        }
        try {
            textual?.close()
        } catch (ignored: Throwable) {
        }
    }

    // BOS + BPE tokeny + EOS, pad 0 na 77. Vráti (ids[77], realLen).
    private fun tokenize(query: String): Pair<IntArray, Int> {
        val clean = query.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex(" +"), " ")
        val toks = ArrayList<Int>()
        toks.add(49406) // BOS
        if (clean.isNotEmpty()) {
            try {
                toks.addAll(tokenizer.encode(clean))
            } catch (ignored: Throwable) {
            }
        }
        toks.add(49407) // EOS
        val realLen = minOf(toks.size, 77)
        val ids = IntArray(77) { if (it < toks.size) toks[it] else 0 }
        return ids to realLen
    }

    private fun loadVocab(): Map<String, Int> {
        val vocab = HashMap<String, Int>()
        appCtx.assets.open("clip/vocab.json").use { ins ->
            val r = JsonReader(InputStreamReader(ins, "UTF-8"))
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName().replace("</w>", " ")
                val value = r.nextInt()
                vocab[key] = value
            }
            r.close()
        }
        return vocab
    }

    private fun loadMerges(): HashMap<Pair<String, String>, Int> {
        val merges = HashMap<Pair<String, String>, Int>()
        appCtx.assets.open("clip/merges.txt").use { ins ->
            BufferedReader(InputStreamReader(ins)).useLines { seq ->
                seq.drop(1).forEachIndexed { i, s ->
                    val list = s.split(" ")
                    if (list.size >= 2) {
                        merges[list[0] to list[1].replace("</w>", " ")] = i
                    }
                }
            }
        }
        return merges
    }

    companion object {
        private val CLIP_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val CLIP_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        private const val SIZE = 224

        // OpenAI CLIP _transform: orež centrálny štvorec -> zmenši na 224 -> RGB -> normalizuj -> CHW
        private fun preprocess(bitmap: Bitmap): FloatArray {
            val cropX: Int
            val cropY: Int
            val cropSize: Int
            if (bitmap.width >= bitmap.height) {
                cropX = bitmap.width / 2 - bitmap.height / 2
                cropY = 0
                cropSize = bitmap.height
            } else {
                cropX = 0
                cropY = bitmap.height / 2 - bitmap.width / 2
                cropSize = bitmap.width
            }
            val square = Bitmap.createBitmap(bitmap, cropX, cropY, cropSize, cropSize)
            val resized = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
            if (square != bitmap) square.recycle()
            val n = SIZE * SIZE
            val out = FloatArray(3 * n)
            val pixels = IntArray(n)
            resized.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            if (resized != bitmap) resized.recycle()
            for (i in 0 until n) {
                val p = pixels[i]
                val r = (p shr 16 and 0xFF) / 255f
                val g = (p shr 8 and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                out[i] = (r - CLIP_MEAN[0]) / CLIP_STD[0]
                out[i + n] = (g - CLIP_MEAN[1]) / CLIP_STD[1]
                out[i + 2 * n] = (b - CLIP_MEAN[2]) / CLIP_STD[2]
            }
            return out
        }

        private fun l2(v: FloatArray): FloatArray {
            var s = 0f
            for (x in v) s += x * x
            val norm = sqrt(s).coerceAtLeast(1e-9f)
            return FloatArray(v.size) { v[it] / norm }
        }

        fun toBytes(v: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.nativeOrder())
            v.forEach { bb.putFloat(it) }
            return bb.array()
        }

        fun toFloats(bytes: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            return FloatArray(bytes.size / 4) { bb.float }
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var d = 0f
            val n = minOf(a.size, b.size)
            for (i in 0 until n) d += a[i] * b[i]
            return d
        }
    }
}
