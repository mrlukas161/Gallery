package org.fossify.gallery.clip

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import kotlin.math.sqrt

// Multilingválny text enkodér (DistilBERT + projekcia) cez ONNX Runtime. Dopyt v ktoromkoľvek
// z 50+ jazykov (vrátane slovenčiny) -> 512-dim L2-normalizovaný vektor v rovnakom priestore ako
// obrázkové embeddingy. Vstupy modelu: input_ids + attention_mask (int64). Session je lazy.
class ClipMlEncoder(context: Context) {
    private val appCtx = context.applicationContext
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession? by lazy {
        try {
            val f = ClipMlModels.textFile(appCtx)
            if (f.exists() && f.length() > 0) ortEnv.createSession(f.absolutePath, OrtSession.SessionOptions()) else null
        } catch (e: Throwable) {
            null
        }
    }

    private val tokenizer: BertTokenizer? by lazy {
        try {
            BertTokenizer.load(ClipMlModels.tokenizerFile(appCtx))
        } catch (e: Throwable) {
            null
        }
    }

    fun ready(): Boolean = session != null && tokenizer != null

    // dopyt (SK/EN/…) -> 512-dim L2-normalizovaný vektor (alebo null)
    fun encodeText(query: String): FloatArray? {
        val s = session ?: return null
        val tok = tokenizer ?: return null
        return try {
            val ids = tok.encode(query, 128)
            val len = ids.size
            if (len == 0) return null
            val idsL = LongArray(len) { ids[it].toLong() }
            val maskL = LongArray(len) { 1L }
            val shape = longArrayOf(1, len.toLong())
            val names = s.inputNames.toList()
            val idsName = names.firstOrNull { it.contains("input_ids") } ?: names.getOrNull(0) ?: "input_ids"
            val maskName = names.firstOrNull { it.contains("attention_mask") || it.contains("mask") } ?: names.getOrNull(1)
            OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(idsL), shape).use { idsT ->
                OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(maskL), shape).use { maskT ->
                    val feeds = HashMap<String, OnnxTensor>()
                    feeds[idsName] = idsT
                    if (maskName != null) feeds[maskName] = maskT
                    s.run(feeds).use { res ->
                        @Suppress("UNCHECKED_CAST")
                        val out = (res.get(0).value as Array<FloatArray>)[0]
                        l2(out)
                    }
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    fun close() {
        try {
            session?.close()
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        private fun l2(v: FloatArray): FloatArray {
            var s = 0f
            for (x in v) s += x * x
            val norm = sqrt(s).coerceAtLeast(1e-9f)
            return FloatArray(v.size) { v[it] / norm }
        }
    }
}
