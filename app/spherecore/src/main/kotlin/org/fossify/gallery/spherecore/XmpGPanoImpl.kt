package org.fossify.gallery.spherecore

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

internal object XmpGPanoImpl {
    private const val NS = "http://ns.google.com/photos/1.0/panorama/"
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) // 29 bajtov
    private val EXIF_HEADER = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun num(d: Double): String {
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e12) return d.toLong().toString()
        return String.format(Locale.ROOT, "%.4f", d).trimEnd('0').trimEnd('.')
    }

    private fun boolStr(b: Boolean) = if (b) "True" else "False"

    fun buildPacket(info: GPanoInfo): String {
        val a = ArrayList<Pair<String, String>>()
        a += "UsePanoramaViewer" to boolStr(info.usePanoramaViewer)
        a += "CaptureSoftware" to info.software
        a += "StitchingSoftware" to info.software
        a += "ProjectionType" to info.projectionType
        if (info.poseHeadingDeg != null) a += "PoseHeadingDegrees" to num(info.poseHeadingDeg)
        a += "InitialViewHeadingDegrees" to num(info.initialViewHeadingDeg)
        a += "InitialViewPitchDegrees" to "0"
        a += "InitialViewRollDegrees" to "0"
        a += "InitialHorizontalFOVDegrees" to num(info.initialHorizontalFovDeg)
        a += "CroppedAreaLeftPixels" to info.croppedLeft.toString()
        a += "CroppedAreaTopPixels" to info.croppedTop.toString()
        a += "CroppedAreaImageWidthPixels" to info.croppedWidth.toString()
        a += "CroppedAreaImageHeightPixels" to info.croppedHeight.toString()
        a += "FullPanoWidthPixels" to info.fullPanoWidth.toString()
        a += "FullPanoHeightPixels" to info.fullPanoHeight.toString()
        if (info.firstPhotoDate.isNotEmpty()) a += "FirstPhotoDate" to info.firstPhotoDate
        if (info.lastPhotoDate.isNotEmpty()) a += "LastPhotoDate" to info.lastPhotoDate
        a += "SourcePhotosCount" to info.sourcePhotosCount.toString()
        a += "ExposureLockUsed" to boolStr(info.exposureLockUsed)
        val sb = StringBuilder()
        sb.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
        sb.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        sb.append("<rdf:Description rdf:about=\"\" xmlns:GPano=\"$NS\"")
        for ((k, v) in a) sb.append("\n GPano:").append(k).append("=\"").append(esc(v)).append('"')
        sb.append("/></rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>")
        return sb.toString()
    }

    // ---- JPEG segmenty ----

    private class Seg(val marker: Int, val start: Int, val end: Int) // start = pozícia 0xFF, end = za segmentom

    /** Prejde hlavičkové segmenty po SOS. null = štruktúra nie je platný JPEG. */
    private fun walk(b: ByteArray): List<Seg>? {
        if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
        val out = ArrayList<Seg>()
        var p = 2
        while (p + 1 < b.size) {
            if ((b[p].toInt() and 0xFF) != 0xFF) return if (out.isEmpty()) null else out
            var q = p
            while (q < b.size && (b[q].toInt() and 0xFF) == 0xFF) q++
            if (q >= b.size) return out
            val marker = b[q].toInt() and 0xFF
            val segStart = q - 1
            if (marker == 0xDA || marker == 0xD9) { out += Seg(marker, segStart, b.size); return out }
            if (marker == 0x01 || marker in 0xD0..0xD8) { p = q + 1; continue }
            if (q + 2 >= b.size) return out
            val len = ((b[q + 1].toInt() and 0xFF) shl 8) or (b[q + 2].toInt() and 0xFF)
            if (len < 2) return null
            val end = minOf(q + 1 + len, b.size) // pri skrátenom vstupe čiastočný segment
            out += Seg(marker, segStart, end)
            p = q + 1 + len
        }
        return out
    }

    private fun startsWith(b: ByteArray, off: Int, end: Int, h: ByteArray): Boolean {
        if (end - off < h.size) return false
        for (i in h.indices) if (b[off + i] != h[i]) return false
        return true
    }

    private fun payloadOff(s: Seg) = s.start + 4 // FF, marker, 2 bajty dĺžky

    private fun isXmp(b: ByteArray, s: Seg) = s.marker == 0xE1 && startsWith(b, payloadOff(s), s.end, XMP_HEADER)
    private fun isExif(b: ByteArray, s: Seg) = s.marker == 0xE1 && startsWith(b, payloadOff(s), s.end, EXIF_HEADER)

    fun write(jpeg: File, info: GPanoInfo) {
        val b = jpeg.readBytes()
        val segs = walk(b) ?: throw IllegalArgumentException("nie je JPEG: ${jpeg.name}")
        val packet = buildPacket(info).toByteArray(Charsets.UTF_8)
        val payloadLen = XMP_HEADER.size + packet.size
        require(payloadLen + 2 <= 0xFFFF) { "XMP paket je príliš veľký" }
        val xmpSeg = ByteArrayOutputStream(payloadLen + 4)
        xmpSeg.write(0xFF); xmpSeg.write(0xE1)
        xmpSeg.write((payloadLen + 2) ushr 8); xmpSeg.write((payloadLen + 2) and 0xFF)
        xmpSeg.write(XMP_HEADER); xmpSeg.write(packet)

        val out = ByteArrayOutputStream(b.size + xmpSeg.size())
        out.write(b, 0, 2) // SOI
        var leading = true
        var tailStart = -1
        for (s in segs) {
            if (s.marker == 0xDA || s.marker == 0xD9) {
                if (leading) { xmpSeg.writeTo(out); leading = false }
                tailStart = s.start
                break
            }
            if (isXmp(b, s)) continue // nahrádza sa novým
            if (leading && (s.marker == 0xE0 || isExif(b, s))) {
                out.write(b, s.start, s.end - s.start)
                continue
            }
            if (leading) { xmpSeg.writeTo(out); leading = false }
            out.write(b, s.start, s.end - s.start)
        }
        if (tailStart < 0) {
            // žiadny SOS (poškodený súbor): XMP na koniec hlavičiek, zvyšok nedotknutý
            if (leading) xmpSeg.writeTo(out)
            val last = segs.lastOrNull()?.end ?: 2
            out.write(b, last, b.size - last)
        } else {
            out.write(b, tailStart, b.size - tailStart)
        }
        val tmp = File(jpeg.parentFile, jpeg.name + ".xmp.tmp")
        tmp.writeBytes(out.toByteArray())
        if (!tmp.renameTo(jpeg)) {
            jpeg.delete()
            if (!tmp.renameTo(jpeg)) { jpeg.writeBytes(tmp.readBytes()); tmp.delete() }
        }
    }

    fun read(jpeg: File): GPanoInfo? {
        // prvých 512 kB stačí (XMP je v hlavičke); väčšie súbory sa nečítajú celé
        val n = minOf(jpeg.length(), 512L * 1024).toInt()
        val buf = ByteArray(n)
        jpeg.inputStream().use { ins ->
            var o = 0
            while (o < n) { val r = ins.read(buf, o, n - o); if (r <= 0) break; o += r }
        }
        return read(buf)
    }

    fun read(head: ByteArray): GPanoInfo? {
        val segs = walk(head)
        val text: String = if (segs != null) {
            val sb = StringBuilder()
            for (s in segs) {
                if (isXmp(head, s)) {
                    val o = payloadOff(s) + XMP_HEADER.size
                    if (o < s.end) sb.append(String(head, o, s.end - o, Charsets.UTF_8))
                }
            }
            sb.toString()
        } else {
            // záloha: slepé hľadanie paketu v bajtoch
            val all = String(head, Charsets.ISO_8859_1)
            val i = all.indexOf("GPano:")
            if (i < 0) return null
            val from = all.lastIndexOf("<x:xmpmeta", i).let { if (it < 0) maxOf(0, i - 2000) else it }
            val to = all.indexOf("</x:xmpmeta>", i).let { if (it < 0) minOf(all.length, i + 6000) else it + 12 }
            String(head, from, to - from, Charsets.UTF_8)
        }
        if (!text.contains("GPano", ignoreCase = true)) return null
        return parse(text)
    }

    private fun value(t: String, name: String): String? {
        val attr = Regex("GPano:" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(t)
        if (attr != null) return unesc(attr.groupValues[1].ifEmpty { attr.groupValues[2] })
        val el = Regex("<GPano:" + name + "\\s*>([^<]*)</GPano:" + name + "\\s*>", RegexOption.IGNORE_CASE).find(t)
        return el?.let { unesc(it.groupValues[1].trim()) }
    }

    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun int(t: String, name: String): Int? = value(t, name)?.trim()?.toDoubleOrNull()?.let { Math.round(it).toInt() }
    private fun dbl(t: String, name: String): Double? = value(t, name)?.trim()?.toDoubleOrNull()
    private fun bool(t: String, name: String): Boolean? = value(t, name)?.trim()?.lowercase()?.let { it == "true" || it == "1" }

    private fun parse(t: String): GPanoInfo? {
        val fullW = int(t, "FullPanoWidthPixels")
        val fullH = int(t, "FullPanoHeightPixels")
        val cw = int(t, "CroppedAreaImageWidthPixels")
        val ch = int(t, "CroppedAreaImageHeightPixels")
        val proj = value(t, "ProjectionType")?.trim()
        if (proj != null && !proj.equals("equirectangular", ignoreCase = true)) return null
        // bez ProjectionType a bez rozmerov nejde o panorámu (len UsePanoramaViewer)
        if (proj == null && fullW == null) return null
        val fw: Int
        val fh: Int
        if (fullW != null && fullH != null) { fw = fullW; fh = fullH }
        else if (cw != null && ch != null) { fw = cw; fh = ch }
        else return null
        val cropW = cw ?: fw
        val cropH = ch ?: fh
        return GPanoInfo(
            projectionType = "equirectangular",
            fullPanoWidth = fw,
            fullPanoHeight = fh,
            croppedWidth = cropW,
            croppedHeight = cropH,
            croppedLeft = int(t, "CroppedAreaLeftPixels") ?: 0,
            croppedTop = int(t, "CroppedAreaTopPixels") ?: 0,
            usePanoramaViewer = bool(t, "UsePanoramaViewer") ?: true,
            poseHeadingDeg = dbl(t, "PoseHeadingDegrees"),
            initialViewHeadingDeg = dbl(t, "InitialViewHeadingDegrees") ?: 0.0,
            initialHorizontalFovDeg = dbl(t, "InitialHorizontalFOVDegrees") ?: 75.0,
            sourcePhotosCount = int(t, "SourcePhotosCount") ?: 0,
            exposureLockUsed = bool(t, "ExposureLockUsed") ?: false,
            firstPhotoDate = value(t, "FirstPhotoDate") ?: "",
            lastPhotoDate = value(t, "LastPhotoDate") ?: "",
            software = value(t, "StitchingSoftware") ?: value(t, "CaptureSoftware") ?: "",
        )
    }
}
