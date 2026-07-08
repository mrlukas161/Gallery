package org.fossify.gallery.clip

import android.content.Context
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// VOLITEĽNÝ multilingválny CLIP text encoder — priama slovenčina bez SK->EN slovníka.
// Model: ajaleksa/clip-onnx-models -> text-multilingual (DistilBERT multilingual + projekcia 768->512,
// výstup L2-normalizovaný, zarovnaný na OpenAI ViT-B/32). To je ROVNAKÝ priestor ako naše obrázkové
// embeddingy (immich ViT-B-32__openai) -> teoreticky BEZ re-indexácie fotiek. <over sa až testom>
// Sťahuje sa až na výslovné zapnutie (~500 MB). Vizuálny model ostáva pôvodný.
object ClipMlModels {
    private const val TEXT_URL = "https://huggingface.co/ajaleksa/clip-onnx-models/resolve/main/text-multilingual/model.onnx"
    private const val TOKENIZER_URL = "https://huggingface.co/ajaleksa/clip-onnx-models/resolve/main/text-multilingual/tokenizer.json"
    private const val TEXT_MIN = 120_000_000L      // fp32 ~500 MB; konzervatívny prah proti useknutému súboru
    private const val TOKENIZER_MIN = 1_000_000L   // tokenizer.json (multilingválny vocab) ~ niekoľko MB

    @Volatile
    var isRunning = false
        private set

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "clip_ml").apply { mkdirs() }
    fun textFile(context: Context): File = File(dir(context), "text.onnx")
    fun tokenizerFile(context: Context): File = File(dir(context), "tokenizer.json")

    fun textPresent(context: Context): Boolean = textFile(context).let { it.exists() && it.length() >= TEXT_MIN }
    fun tokenizerPresent(context: Context): Boolean = tokenizerFile(context).let { it.exists() && it.length() >= TOKENIZER_MIN }
    fun present(context: Context): Boolean = textPresent(context) && tokenizerPresent(context)

    // stiahne chýbajúce súbory na pozadí; onProgress(fáza, done, total)
    fun download(
        context: Context,
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            try {
                val ok = ensure(appCtx, { isRunning }) { phase, pct -> onProgress(phase, pct, 100) }
                if (!isRunning) {
                    onError("zastavené")
                } else if (ok) {
                    onDone()
                } else {
                    onError("model sa nestiahol (skontroluj WiFi/pripojenie)")
                }
            } catch (e: Throwable) {
                onError(e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: ""))
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun ensure(context: Context, running: () -> Boolean, onProgress: (phase: String, pct: Int) -> Unit): Boolean {
        if (!tokenizerPresent(context)) {
            if (!downloadFile(TOKENIZER_URL, tokenizerFile(context), running) { p -> onProgress("tokenizer", p) }) return false
        }
        if (!running()) return false
        if (!textPresent(context)) {
            if (!downloadFile(TEXT_URL, textFile(context), running) { p -> onProgress("SK model", p) }) return false
        }
        return present(context)
    }

    private fun downloadFile(urlStr: String, dest: File, running: () -> Boolean, onProgress: (Int) -> Unit): Boolean {
        var url = urlStr
        var redirects = 0
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            while (redirects < 6) {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "GaleriaPlus")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return false
                    conn.disconnect()
                    url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    return false
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            if (!running()) return false
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                return dest.exists() && dest.length() > 0
            }
            return false
        } catch (e: Throwable) {
            try {
                tmp.delete()
            } catch (ignored: Throwable) {
            }
            return false
        }
    }
}
