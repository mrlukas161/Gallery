package org.fossify.gallery.spherecore

import nu.pattern.OpenCV
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class XmpGPanoTest {
    companion object {
        @BeforeClass @JvmStatic fun load() { OpenCV.loadLocally() }
    }

    private val info = GPanoInfo(
        fullPanoWidth = 4096, fullPanoHeight = 2048, croppedWidth = 4096, croppedHeight = 2048,
        poseHeadingDeg = 123.5, initialViewHeadingDeg = 10.0, sourcePhotosCount = 29, exposureLockUsed = true,
        firstPhotoDate = "2026-11-26T08:15:00.000Z", lastPhotoDate = "2026-11-26T08:16:10.000Z",
    )

    private fun tmpDir(): File = Files.createTempDirectory("xmptest").toFile().also { it.deleteOnExit() }

    private fun testJpeg(f: File) {
        val m = Mat(64, 96, CvType.CV_8UC3, Scalar(30.0, 90.0, 200.0))
        for (i in 0 until 64) for (j in 0 until 96) m.put(i, j, (i * 4).toDouble(), (j * 2).toDouble(), ((i + j) % 256).toDouble())
        assertTrue(Imgcodecs.imwrite(f.absolutePath, m))
    }

    /** Spočíta XMP APP1 segmenty v hlavičke. */
    private fun countXmp(b: ByteArray): Int {
        var n = 0
        var p = 2
        while (p + 3 < b.size && (b[p].toInt() and 0xFF) == 0xFF) {
            val mk = b[p + 1].toInt() and 0xFF
            if (mk == 0xDA) break
            val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (mk == 0xE1 && String(b, p + 4, minOf(28, len - 2), Charsets.ISO_8859_1) == "http://ns.adobe.com/xap/1.0/") n++
            p += 2 + len
        }
        return n
    }

    private fun markers(b: ByteArray): List<Int> {
        val r = ArrayList<Int>()
        var p = 2
        while (p + 3 < b.size && (b[p].toInt() and 0xFF) == 0xFF) {
            val mk = b[p + 1].toInt() and 0xFF
            r += mk
            if (mk == 0xDA) break
            p += 2 + (((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF))
        }
        return r
    }

    @Test fun packetHasAttributes() {
        val p = XmpGPano.buildPacket(info)
        assertTrue(p.startsWith("<?xpacket begin="))
        assertTrue(p.endsWith("<?xpacket end=\"w\"?>"))
        assertTrue(p.contains("GPano:UsePanoramaViewer=\"True\""))
        assertTrue(p.contains("GPano:PoseHeadingDegrees=\"123.5\""))
        assertTrue(p.contains("GPano:FullPanoWidthPixels=\"4096\""))
        assertFalse(XmpGPano.buildPacket(info.copy(poseHeadingDeg = null, firstPhotoDate = "")).contains("PoseHeading"))
    }

    @Test fun roundTripOpenCvJpeg() {
        val f = File(tmpDir(), "a.jpg")
        testJpeg(f)
        assertNull(XmpGPano.read(f))
        XmpGPano.write(f, info)
        val r = XmpGPano.read(f)!!
        assertEquals(info.fullPanoWidth, r.fullPanoWidth)
        assertEquals(info.fullPanoHeight, r.fullPanoHeight)
        assertEquals(123.5, r.poseHeadingDeg!!, 1e-9)
        assertEquals(29, r.sourcePhotosCount)
        assertTrue(r.exposureLockUsed)
        assertEquals(info.firstPhotoDate, r.firstPhotoDate)
        assertTrue(r.isFullSphere)
        assertEquals("equirectangular", r.projectionType)
        assertEquals(info.software, r.software)
        // záloha: holý paket bez JPEG štruktúry sa nájde slepým hľadaním
        assertNotNull(XmpGPano.read(XmpGPano.buildPacket(info).toByteArray()))
    }

    @Test fun pixelsUnchangedAndOtherSegmentsIdentical() {
        val f = File(tmpDir(), "b.jpg")
        testJpeg(f)
        val before = f.readBytes()
        val m0 = Imgcodecs.imread(f.absolutePath)
        XmpGPano.write(f, info)
        val after = f.readBytes()
        val m1 = Imgcodecs.imread(f.absolutePath)
        val diff = Mat()
        org.opencv.core.Core.absdiff(m0, m1, diff)
        assertEquals(0.0, org.opencv.core.Core.norm(diff), 0.0)
        // všetko okrem vloženého segmentu je bajt po bajte rovnaké
        val xmpLen = after.size - before.size
        val seg = ((after[4].toInt() and 0xFF) shl 8 or (after[5].toInt() and 0xFF))
        // APP0 JFIF z OpenCV je prvý; XMP za ním
        val app0Len = 2 + ((before[4].toInt() and 0xFF) shl 8 or (before[5].toInt() and 0xFF))
        assertEquals(0xE0, before[3].toInt() and 0xFF)
        assertEquals(0xE1, after[2 + app0Len + 1].toInt() and 0xFF)
        assertTrue(seg > 0)
        val ins = 2 + app0Len
        assertArrayEquals(before.copyOfRange(0, ins), after.copyOfRange(0, ins))
        assertArrayEquals(before.copyOfRange(ins, before.size), after.copyOfRange(ins + xmpLen, after.size))
    }

    @Test fun landsAfterExif() {
        val src = File(tmpDir(), "c.jpg")
        testJpeg(src)
        val b = src.readBytes()
        // SOI + APP0 + minimálny Exif APP1 + zvyšok pôvodného (po jeho APP0)
        val app0Len = 2 + ((b[4].toInt() and 0xFF) shl 8 or (b[5].toInt() and 0xFF))
        val exifPayload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
        val out = ByteArrayOutputStream()
        out.write(b, 0, 2 + app0Len)
        out.write(0xFF); out.write(0xE1); out.write((exifPayload.size + 2) ushr 8); out.write((exifPayload.size + 2) and 0xFF)
        out.write(exifPayload)
        out.write(b, 2 + app0Len, b.size - 2 - app0Len)
        val f = File(src.parentFile, "d.jpg")
        f.writeBytes(out.toByteArray())
        val mk0 = markers(f.readBytes())
        XmpGPano.write(f, info)
        val mk = markers(f.readBytes())
        assertEquals(mk0[0], 0xE0); assertEquals(mk0[1], 0xE1)
        assertEquals(listOf(0xE0, 0xE1, 0xE1), mk.take(3))
        // druhý E1 je XMP, nie Exif
        val bytes = f.readBytes()
        val exifSegLen = 2 + exifPayload.size + 2
        val xmpStart = 2 + app0Len + exifSegLen
        assertEquals("http://ns.adobe.com/xap/1.0/", String(bytes, xmpStart + 4, 28, Charsets.ISO_8859_1))
        assertNotNull(XmpGPano.read(f))
        assertNotNull(Imgcodecs.imread(f.absolutePath).takeIf { !it.empty() })
    }

    @Test fun doubleWriteKeepsOneSegmentAndReplaces() {
        val f = File(tmpDir(), "e.jpg")
        testJpeg(f)
        XmpGPano.write(f, info)
        XmpGPano.write(f, info.copy(sourcePhotosCount = 7, fullPanoWidth = 8192, fullPanoHeight = 4096, croppedWidth = 8192, croppedHeight = 1000, croppedTop = 1500))
        val b = f.readBytes()
        assertEquals(1, countXmp(b))
        val r = XmpGPano.read(f)!!
        assertEquals(7, r.sourcePhotosCount)
        assertEquals(1500, r.croppedTop)
        assertEquals(1000, r.croppedHeight)
        assertFalse(r.isFullSphere)
        assertFalse(File(f.parentFile, f.name + ".xmp.tmp").exists())
    }

    @Test fun elementFormAndDefaults() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmlns:GPano="http://ns.google.com/photos/1.0/panorama/">
            <GPano:UsePanoramaViewer>true</GPano:UsePanoramaViewer>
            <GPano:ProjectionType>EQUIRECTANGULAR</GPano:ProjectionType>
            <GPano:FullPanoWidthPixels>4000</GPano:FullPanoWidthPixels>
            <GPano:FullPanoHeightPixels>2000</GPano:FullPanoHeightPixels>
            <GPano:PoseHeadingDegrees>90.5</GPano:PoseHeadingDegrees>
            </rdf:Description></rdf:RDF></x:xmpmeta>""".trimIndent()
        val f = File(tmpDir(), "g.jpg")
        testJpeg(f)
        // vlož cudzí XMP (element form) ručne
        val payload = ("http://ns.adobe.com/xap/1.0/\u0000" + xmp).toByteArray(Charsets.UTF_8)
        val b = f.readBytes()
        val app0Len = 2 + ((b[4].toInt() and 0xFF) shl 8 or (b[5].toInt() and 0xFF))
        val out = ByteArrayOutputStream()
        out.write(b, 0, 2 + app0Len)
        out.write(0xFF); out.write(0xE1); out.write((payload.size + 2) ushr 8); out.write((payload.size + 2) and 0xFF); out.write(payload)
        out.write(b, 2 + app0Len, b.size - 2 - app0Len)
        f.writeBytes(out.toByteArray())
        val r = XmpGPano.read(f)!!
        assertEquals(4000, r.fullPanoWidth); assertEquals(2000, r.fullPanoHeight)
        assertEquals(4000, r.croppedWidth); assertEquals(2000, r.croppedHeight) // bez Cropped* = plná
        assertEquals(90.5, r.poseHeadingDeg!!, 1e-9)
        assertTrue(r.isFullSphere)
        // zápis nahradí cudzí XMP, nezduplikuje
        XmpGPano.write(f, info)
        assertEquals(1, countXmp(f.readBytes()))
    }

    @Test fun rejectsNonPanoramas() {
        val f = File(tmpDir(), "h.jpg")
        testJpeg(f)
        assertNull(XmpGPano.read(f))
        assertNull(XmpGPano.read(ByteArray(10)))
        val mat = MatOfByte()
        val m = Mat(8, 8, CvType.CV_8UC3, Scalar(1.0, 2.0, 3.0))
        Imgcodecs.imencode(".jpg", m, mat)
        // len UsePanoramaViewer
        val onlyUse = ("<x:xmpmeta><rdf:Description xmlns:GPano=\"x\" GPano:UsePanoramaViewer=\"True\"/></x:xmpmeta>")
        val payload = ("http://ns.adobe.com/xap/1.0/\u0000" + onlyUse).toByteArray()
        val b = mat.toArray()
        val out = ByteArrayOutputStream()
        out.write(b, 0, 2)
        out.write(0xFF); out.write(0xE1); out.write((payload.size + 2) ushr 8); out.write((payload.size + 2) and 0xFF); out.write(payload)
        out.write(b, 2, b.size - 2)
        assertNull(XmpGPano.read(out.toByteArray()))
        // iná projekcia
        val cyl = onlyUse.replace("GPano:UsePanoramaViewer=\"True\"", "GPano:ProjectionType=\"cylindrical\" GPano:FullPanoWidthPixels=\"10\" GPano:FullPanoHeightPixels=\"5\"")
        val p2 = ("http://ns.adobe.com/xap/1.0/\u0000" + cyl).toByteArray()
        val out2 = ByteArrayOutputStream()
        out2.write(b, 0, 2)
        out2.write(0xFF); out2.write(0xE1); out2.write((p2.size + 2) ushr 8); out2.write((p2.size + 2) and 0xFF); out2.write(p2)
        out2.write(b, 2, b.size - 2)
        assertNull(XmpGPano.read(out2.toByteArray()))
    }
}
