package org.fossify.gallery.spherecore

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Random
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Textúra s mip pyramídou (BGR bajty). */
class Tex(val w: Int, val h: Int, val levels: List<ByteArray>, val dims: List<IntArray>)

class Marker(val dir: DoubleArray, val radiusDeg: Double, val bgr: IntArray)

/**
 * Syntetická scéna: osovo zarovnaná miestnosť (kváder) s texturovanými stenami.
 * Súradnice sveta: Z hore, podlaha z = 0, strop z = height; stred miestnosti (0,0).
 */
class SyntheticRoom(
    val width: Double, val depth: Double, val height: Double,
    /** Šírka jednej dlaždice textúry v metroch (na stenách ~1,5 m pri izbe). */
    val tileM: Double = 1.5,
    val textures: List<Tex>,
    val markers: List<Marker> = emptyList(),
) {
    private val faceNormals = arrayOf(
        doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), // +X stena (normála dovnútra −X), −X stena
        doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0), // podlaha, strop
    )

    /** Farba (BGR 0..255) v smere d z bodu p; zapíše do out[0..2]. */
    private fun shade(px: Double, py: Double, pz: Double, dx: Double, dy: Double, dz: Double, pixelAngle: Double, out: DoubleArray) {
        for (m in markers) {
            val c = m.dir[0] * dx + m.dir[1] * dy + m.dir[2] * dz
            if (c > 0 && Math.toDegrees(acos(c.coerceIn(-1.0, 1.0))) < m.radiusDeg) {
                out[0] = m.bgr[0].toDouble(); out[1] = m.bgr[1].toDouble(); out[2] = m.bgr[2].toDouble()
                return
            }
        }
        var t = Double.MAX_VALUE
        var face = -1
        if (dx > 1e-12) { val tt = (width / 2 - px) / dx; if (tt < t) { t = tt; face = 0 } }
        else if (dx < -1e-12) { val tt = (-width / 2 - px) / dx; if (tt < t) { t = tt; face = 1 } }
        if (dy > 1e-12) { val tt = (depth / 2 - py) / dy; if (tt < t) { t = tt; face = 2 } }
        else if (dy < -1e-12) { val tt = (-depth / 2 - py) / dy; if (tt < t) { t = tt; face = 3 } }
        if (dz > 1e-12) { val tt = (height - pz) / dz; if (tt < t) { t = tt; face = 5 } }
        else if (dz < -1e-12) { val tt = (0 - pz) / dz; if (tt < t) { t = tt; face = 4 } }
        if (face < 0 || t <= 0) { out[0] = 0.0; out[1] = 0.0; out[2] = 0.0; return }
        val hx = px + t * dx
        val hy = py + t * dy
        val hz = pz + t * dz
        val u: Double
        val v: Double
        when (face) {
            0, 1 -> { u = hy; v = hz }
            2, 3 -> { u = hx; v = hz }
            else -> { u = hx; v = hy }
        }
        val n = faceNormals[face]
        val cosInc = abs(n[0] * dx + n[1] * dy + n[2] * dz).coerceAtLeast(0.08)
        val footprint = t * pixelAngle / cosInc // v metroch
        sampleFace(face, u + face * 7.31, v + face * 3.17, footprint, out)
    }

    private fun sampleFace(face: Int, u: Double, v: Double, footprintM: Double, out: DoubleArray) {
        val su = u / tileM
        val sv = v / tileM
        val i = floor(su).toInt()
        val j = floor(sv).toInt()
        var fu = su - i
        var fv = sv - j
        if ((i and 1) != 0) fu = 1 - fu
        if ((j and 1) != 0) fv = 1 - fv
        val hsh = (face * 73856093) xor (i * 19349663) xor (j * 83492791)
        val tex = textures[Math.floorMod(hsh, textures.size)]
        val texelM = tileM / tex.w
        val lod = (ln((footprintM / texelM).coerceAtLeast(1.0)) / ln(2.0)).coerceIn(0.0, (tex.levels.size - 1).toDouble())
        val l0 = lod.toInt()
        val l1 = minOf(l0 + 1, tex.levels.size - 1)
        val a = lod - l0
        val c0 = DoubleArray(3)
        bilinear(tex, l0, fu, 1 - fv, c0)
        if (a > 1e-3 && l1 != l0) {
            val c1 = DoubleArray(3)
            bilinear(tex, l1, fu, 1 - fv, c1)
            for (k in 0..2) out[k] = c0[k] * (1 - a) + c1[k] * a
        } else for (k in 0..2) out[k] = c0[k]
    }

    private fun bilinear(t: Tex, l: Int, fu: Double, fv: Double, out: DoubleArray) {
        val w = t.dims[l][0]
        val h = t.dims[l][1]
        val x = (fu * w - 0.5).coerceIn(0.0, w - 1.0)
        val y = (fv * h - 0.5).coerceIn(0.0, h - 1.0)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = minOf(x0 + 1, w - 1)
        val y1 = minOf(y0 + 1, h - 1)
        val ax = x - x0
        val ay = y - y0
        val d = t.levels[l]
        for (c in 0..2) {
            val p00 = d[(y0 * w + x0) * 3 + c].toInt() and 0xFF
            val p10 = d[(y0 * w + x1) * 3 + c].toInt() and 0xFF
            val p01 = d[(y1 * w + x0) * 3 + c].toInt() and 0xFF
            val p11 = d[(y1 * w + x1) * 3 + c].toInt() and 0xFF
            out[c] = (p00 * (1 - ax) + p10 * ax) * (1 - ay) + (p01 * (1 - ax) + p11 * ax) * ay
        }
    }

    /** Pohľad kamery s rotáciou rCw (kamera → svet) z bodu camPos; f v pixeloch. */
    fun renderView(rCw: Mat3, camPos: DoubleArray, fPx: Double, w: Int, h: Int): Mat {
        val buf = ByteArray(w * h * 3)
        val m = rCw.m
        IntStream.range(0, h).parallel().forEach { y ->
            val c = DoubleArray(3)
            for (x in 0 until w) {
                val cx = (x + 0.5 - w / 2.0) / fPx
                val cy = (y + 0.5 - h / 2.0) / fPx
                val dx0 = m[0] * cx + m[1] * cy + m[2]
                val dy0 = m[3] * cx + m[4] * cy + m[5]
                val dz0 = m[6] * cx + m[7] * cy + m[8]
                val n = sqrt(dx0 * dx0 + dy0 * dy0 + dz0 * dz0)
                shade(camPos[0], camPos[1], camPos[2], dx0 / n, dy0 / n, dz0 / n, 1.0 / fPx, c)
                val o = (y * w + x) * 3
                buf[o] = c[0].toInt().coerceIn(0, 255).toByte()
                buf[o + 1] = c[1].toInt().coerceIn(0, 255).toByte()
                buf[o + 2] = c[2].toInt().coerceIn(0, 255).toByte()
            }
        }
        val mat = Mat(h, w, CvType.CV_8UC3)
        mat.put(0, 0, buf)
        return mat
    }

    /** Ekvidištančná panoráma (pravda) z bodu camPos podľa konvencie PanoFrame, šírka W, výška W/2. */
    fun renderEquirect(camPos: DoubleArray, pano: PanoFrame, W: Int): Mat {
        val H = W / 2
        val buf = ByteArray(W * H * 3)
        val pixelAngle = 2 * Math.PI / W
        IntStream.range(0, H).parallel().forEach { y ->
            val c = DoubleArray(3)
            val lat = (0.5 - (y + 0.5) / H) * Math.PI
            val cl = cos(lat)
            val sl = sin(lat)
            for (x in 0 until W) {
                val lon = ((x + 0.5) / W - 0.5) * 2 * Math.PI
                val a = cl * cos(lon)
                val b = cl * sin(lon)
                val dx = a * pano.forward[0] + b * pano.right[0] + sl * pano.up[0]
                val dy = a * pano.forward[1] + b * pano.right[1] + sl * pano.up[1]
                val dz = a * pano.forward[2] + b * pano.right[2] + sl * pano.up[2]
                shade(camPos[0], camPos[1], camPos[2], dx, dy, dz, pixelAngle, c)
                val o = (y * W + x) * 3
                buf[o] = c[0].toInt().coerceIn(0, 255).toByte()
                buf[o + 1] = c[1].toInt().coerceIn(0, 255).toByte()
                buf[o + 2] = c[2].toInt().coerceIn(0, 255).toByte()
            }
        }
        val mat = Mat(H, W, CvType.CV_8UC3)
        mat.put(0, 0, buf)
        return mat
    }

    companion object {
        val PHOTO_DIR = File("D:/Claude Cowork/Galeria/_testfotky")

        fun makeTex(m: Mat): Tex {
            val levels = ArrayList<ByteArray>()
            val dims = ArrayList<IntArray>()
            var cur = m
            while (true) {
                val b = ByteArray(cur.cols() * cur.rows() * 3)
                cur.get(0, 0, b)
                levels.add(b)
                dims.add(intArrayOf(cur.cols(), cur.rows()))
                if (cur.cols() <= 8 || cur.rows() <= 8) break
                val d = Mat()
                Imgproc.pyrDown(cur, d)
                if (cur !== m) cur.release()
                cur = d
            }
            return Tex(m.cols(), m.rows(), levels, dims)
        }

        /** Deterministická textúra bohatá na príznaky: viacmierkové obdĺžniky, škvrny, čiary, text, šum. */
        fun proceduralTextures(count: Int = 12, size: Int = 1024, seed: Long = 7): List<Tex> {
            val out = ArrayList<Tex>()
            for (k in 0 until count) out.add(makeTex(proceduralImage(size, Random(seed * 1000 + k))))
            return out
        }

        fun proceduralImage(size: Int, r: Random): Mat {
            val base = Scalar(60.0 + r.nextInt(140), 60.0 + r.nextInt(140), 60.0 + r.nextInt(140))
            val m = Mat(size, size, CvType.CV_8UC3, base)
            // jemný farebný prechod
            val grad = Mat(size, size, CvType.CV_8UC3)
            val g0 = Scalar(r.nextInt(80).toDouble(), r.nextInt(80).toDouble(), r.nextInt(80).toDouble())
            for (i in 0 until size step 8) Imgproc.line(grad, Point(0.0, i.toDouble()), Point(size.toDouble(), i.toDouble()),
                Scalar(g0.`val`[0] + i * 0.05, g0.`val`[1] + (size - i) * 0.05, g0.`val`[2] + 20.0), 8)
            Core.addWeighted(m, 0.8, grad, 0.2, 0.0, m)
            grad.release()
            fun col() = Scalar(r.nextInt(256).toDouble(), r.nextInt(256).toDouble(), r.nextInt(256).toDouble())
            // obdĺžniky v troch mierkach
            for (scale in intArrayOf(300, 100, 30)) {
                val cnt = if (scale == 300) 10 else if (scale == 100) 40 else 150
                for (i in 0 until cnt) {
                    val w = scale / 3 + r.nextInt(scale)
                    val h = scale / 3 + r.nextInt(scale)
                    val x = r.nextInt(size)
                    val y = r.nextInt(size)
                    val c = col()
                    if (r.nextBoolean()) Imgproc.rectangle(m, Point(x.toDouble(), y.toDouble()), Point((x + w).toDouble(), (y + h).toDouble()), c, -1)
                    else Imgproc.rectangle(m, Point(x.toDouble(), y.toDouble()), Point((x + w).toDouble(), (y + h).toDouble()), c, 2 + r.nextInt(4))
                }
            }
            for (i in 0 until 60) Imgproc.circle(m, Point(r.nextInt(size).toDouble(), r.nextInt(size).toDouble()), 5 + r.nextInt(60), col(), if (r.nextBoolean()) -1 else 3)
            for (i in 0 until 80) Imgproc.line(m, Point(r.nextInt(size).toDouble(), r.nextInt(size).toDouble()), Point(r.nextInt(size).toDouble(), r.nextInt(size).toDouble()), col(), 1 + r.nextInt(4))
            val words = arrayOf("EXIT", "42", "ABC", "Sfera", "R7", "Q", "FOTO", "3B", "WC", "hoax", "K9")
            for (i in 0 until 14) Imgproc.putText(m, words[r.nextInt(words.size)], Point(r.nextInt(size - 150).toDouble(), 60.0 + r.nextInt(size - 80)),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.8 + r.nextDouble() * 2.0, col(), 1 + r.nextInt(3))
            // viacmierkový šum
            for (octave in intArrayOf(64, 16, 4)) {
                val n = Mat(size / octave, size / octave, CvType.CV_8UC3)
                val b = ByteArray(n.cols() * n.rows() * 3)
                for (i in b.indices) b[i] = (128 + r.nextGaussian() * 30).toInt().coerceIn(0, 255).toByte()
                n.put(0, 0, b)
                val up = Mat()
                Imgproc.resize(n, up, Size(size.toDouble(), size.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
                n.release()
                val shifted = Mat()
                up.convertTo(shifted, CvType.CV_16S, 1.0, -128.0)
                val m16 = Mat()
                m.convertTo(m16, CvType.CV_16S)
                Core.addWeighted(m16, 1.0, shifted, 0.45, 0.0, m16)
                m16.convertTo(m, CvType.CV_8U)
                up.release(); shifted.release(); m16.release()
            }
            return m
        }

        /** Textúry z fotiek (D:/Claude Cowork/Galeria/_testfotky/test_NN.jpg) + voliteľný procedurálny detail. */
        fun photoTextures(maxSide: Int = 1024, addDetail: Boolean = true): List<Tex> {
            val files = PHOTO_DIR.listFiles { f -> f.name.matches(Regex("test_\\d+\\.jpg")) }?.sortedBy { it.name } ?: emptyList()
            val out = ArrayList<Tex>()
            for ((k, f) in files.withIndex()) {
                val m = Imgcodecs.imread(f.absolutePath)
                if (m.empty()) continue
                val sq = Mat()
                Imgproc.resize(m, sq, Size(maxSide.toDouble(), maxSide.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA) // dlaždica štvorcová
                m.release()
                if (addDetail) {
                    val d = proceduralImage(maxSide, Random(900L + k))
                    Core.addWeighted(sq, 0.6, d, 0.4, 0.0, sq)
                    d.release()
                }
                out.add(makeTex(sq))
            }
            return out
        }

        /** Fotky, ak priečinok existuje, inak procedurálne textúry. */
        fun defaultTextures(usePhotos: Boolean = false): List<Tex> {
            if (usePhotos && PHOTO_DIR.isDirectory) {
                val p = photoTextures()
                if (p.isNotEmpty()) return p
            }
            return proceduralTextures()
        }
    }
}

/** Jedna vygenerovaná relácia + pravda pre testy. */
class SynthSession(
    val dir: File,
    val session: SphereSession,
    val trueR: List<Mat3>,
    val positions: List<DoubleArray>,
    val gains: DoubleArray,
    val truePano: PanoFrame,
    val pivot: DoubleArray,
) {
    /** Rám panorámy výstupu (podľa senzora prvého záberu) vyjadrený v pravdivom svete: na vykreslenie pravdy. */
    fun groundTruthPano(stitchedPano: PanoFrame): PanoFrame {
        val q = session.frames[0].rCw * trueR[0].transpose() // svet_senzora = Q · svet_pravdy
        return PanoFrame(q.mulT(stitchedPano.forward), q.mulT(stitchedPano.right), q.mulT(stitchedPano.up))
    }
}

class SynthParams(
    val hFovDeg: Double = 58.9,
    val vFovDeg: Double = 73.9,
    val imageWidth: Int = 1500,
    val imageHeight: Int = 2000,
    /** Vzdialenosť mobilu od osi otáčania (0 = čistá rotácia okolo uzla). */
    val armM: Double = 0.25,
    val pivot: DoubleArray = doubleArrayOf(0.0, 0.0, 1.5),
    /** Smerodajná odchýlka chyby senzora (stupne, náhodná os). */
    val sensorNoiseDeg: Double = 1.5,
    val rollDeg: Double = 3.0,
    val aimDeg: Double = 1.5,
    /** Náhodný zisk jasu na záber: 1 ± gainRange (0 = vypnuté). */
    val gainRange: Double = 0.0,
    val seed: Long = 42,
    /** Filter cieľov (napr. len horizont). */
    val targetFilter: (Target) -> Boolean = { true },
    val panoYawDeg: Double = 30.0,
    val jpegQuality: Int = 95,
)

object SyntheticSession {
    private fun gauss(r: Random, s: Double) = r.nextGaussian() * s

    fun generate(room: SyntheticRoom, dir: File, p: SynthParams): SynthSession {
        dir.mkdirs()
        val r = Random(p.seed)
        val a = Math.toRadians(p.panoYawDeg)
        val fwd = doubleArrayOf(cos(a), sin(a), 0.0)
        val right = Vec.normalize(Vec.cross(fwd, SphereGeo.UP))
        val truePano = PanoFrame(fwd, right, SphereGeo.UP)
        val f = SphereGeo.focalPx(p.hFovDeg, p.imageWidth.toDouble())
        val targets = TargetLayout.generate(p.hFovDeg, p.vFovDeg).filter(p.targetFilter)
        val frames = ArrayList<SphereFrame>()
        val trueR = ArrayList<Mat3>()
        val pos = ArrayList<DoubleArray>()
        val gains = DoubleArray(targets.size) { 1.0 }
        for ((k, t) in targets.withIndex()) {
            var rt = PanoFrame.cameraLookingAt(truePano, t.yawDeg, t.pitchDeg)
            if (k > 0) {
                val roll = Math.toRadians((r.nextDouble() * 2 - 1) * p.rollDeg)
                rt = rt * Mat3.exp(doubleArrayOf(0.0, 0.0, roll)) // okolo osi kamery z
                val aim = doubleArrayOf(gauss(r, 1.0), gauss(r, 1.0), gauss(r, 1.0))
                val an = Vec.normalize(aim)
                val ang = Math.toRadians(minOf(abs(gauss(r, p.aimDeg * 0.6)), p.aimDeg))
                rt = Mat3.exp(Vec.scale(an, ang)) * rt
            }
            trueR.add(rt)
            val z = rt.col(2)
            val cp = doubleArrayOf(p.pivot[0] + p.armM * z[0], p.pivot[1] + p.armM * z[1], p.pivot[2] + p.armM * z[2])
            pos.add(cp)
            // chyba senzora
            val nv = doubleArrayOf(gauss(r, 1.0), gauss(r, 1.0), gauss(r, 1.0))
            val nn = Vec.normalize(nv)
            val nang = Math.toRadians(abs(gauss(r, p.sensorNoiseDeg)))
            val rec = Mat3.exp(Vec.scale(nn, nang)) * rt
            val img = room.renderView(rt, cp, f, p.imageWidth, p.imageHeight)
            if (p.gainRange > 0) {
                gains[k] = 1.0 + (r.nextDouble() * 2 - 1) * p.gainRange
                val g = Mat()
                img.convertTo(g, CvType.CV_8UC3, gains[k])
                g.copyTo(img)
                g.release()
            }
            val name = "f_%03d.jpg".format(k)
            Imgcodecs.imwrite(File(dir, name).absolutePath, img, org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, p.jpegQuality))
            img.release()
            frames.add(SphereFrame(name, t.index, 1_800_000_000_000L + k * 2500L, rec, 0.0))
        }
        val session = SphereSession(
            id = "synt", device = "synthetic", lens = SphereLens(1.0, p.hFovDeg, p.vFovDeg, p.imageWidth, p.imageHeight, "characteristics"),
            sensor = "game_rotation_vector", headingDeg = 90.0, exposureLocked = p.gainRange == 0.0, frames = frames, finished = true,
        )
        session.save(dir)
        return SynthSession(dir, session, trueR, pos, gains, truePano, p.pivot)
    }
}
