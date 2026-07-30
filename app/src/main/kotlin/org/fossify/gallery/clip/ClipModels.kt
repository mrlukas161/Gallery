package org.fossify.gallery.clip

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Správa CLIP ONNX modelov: sťahovanie pri prvom použití do filesDir/clip/ (nie v APK).
// Model = Immich ViT-B/32 (OpenAI) — vizuál + text enkodér, 512-dim, opset 19.
object ClipModels {
    // Najprv skúsi KVANTOVANÉ modely (~88 MB vizuál + ~64 MB text namiesto 335+242 MB) — rovnaký
    // ViT-B/32 priestor, takže existujúci index fotiek ostáva platný. Ak sa nestiahnu (iná cesta v
    // repozitári), automaticky sa použijú pôvodné overené modely. Poradie = prvý fungujúci vyhráva.
    private val VISUAL_URLS = listOf(
        "https://huggingface.co/IacobIonut01/Gallery/resolve/main/clip/visual_quant.onnx",
        "https://huggingface.co/immich-app/ViT-B-32__openai/resolve/main/visual/model.onnx",
    )
    private val TEXTUAL_URLS = listOf(
        "https://huggingface.co/IacobIonut01/Gallery/resolve/main/clip/textual_quant.onnx",
        "https://huggingface.co/immich-app/ViT-B-32__openai/resolve/main/textual/model.onnx",
    )
    private const val VISUAL_MIN = 60_000_000L
    private const val TEXTUAL_MIN = 40_000_000L

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "clip").apply { mkdirs() }
    fun visualFile(context: Context): File = File(dir(context), "visual.onnx")
    fun textualFile(context: Context): File = File(dir(context), "textual.onnx")

    fun visualPresent(context: Context): Boolean = visualFile(context).let { it.exists() && it.length() >= VISUAL_MIN }
    fun textualPresent(context: Context): Boolean = textualFile(context).let { it.exists() && it.length() >= TEXTUAL_MIN }
    fun bothPresent(context: Context): Boolean = visualPresent(context) && textualPresent(context)

    // stiahne chýbajúce modely; onProgress(fáza 1..2, percentá 0..100). Vráti true ak sú oba na mieste.
    fun ensure(context: Context, running: () -> Boolean, onProgress: (phase: String, pct: Int) -> Unit): Boolean {
        if (!visualPresent(context)) {
            if (!downloadAny(VISUAL_URLS, visualFile(context), VISUAL_MIN, running) { p -> onProgress("model 1/2", p) }) return false
        }
        if (!running()) return false
        if (!textualPresent(context)) {
            if (!downloadAny(TEXTUAL_URLS, textualFile(context), TEXTUAL_MIN, running) { p -> onProgress("model 2/2", p) }) return false
        }
        return bothPresent(context)
    }

    // vyskúša adresy po poradí; berie prvú, ktorá sa stiahne a má rozumnú veľkosť
    private fun downloadAny(
        urls: List<String>, dest: File, minSize: Long,
        running: () -> Boolean, onProgress: (Int) -> Unit,
    ): Boolean {
        for (u in urls) {
            if (!running()) return false
            if (download(u, dest, running, onProgress) && dest.length() >= minSize) return true
            try {
                if (dest.exists() && dest.length() < minSize) dest.delete()
            } catch (ignored: Throwable) {
            }
        }
        return false
    }

    private fun download(urlStr: String, dest: File, running: () -> Boolean, onProgress: (Int) -> Unit): Boolean {
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
                            if (!running()) {
                                return false
                            }
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
