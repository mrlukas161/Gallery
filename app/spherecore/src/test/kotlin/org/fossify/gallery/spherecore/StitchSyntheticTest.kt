package org.fossify.gallery.spherecore

import nu.pattern.OpenCV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.log10

object SynthEval {
    val outDir: File = (if (File("E:/Galeria_praca").isDirectory) File("E:/Galeria_praca/sfera/out") else File("build/sfera-out")).also { it.mkdirs() }
    val workDir: File = (if (File("E:/Galeria_praca").isDirectory) File("E:/Galeria_praca/sfera/work") else File("build/sfera-work")).also { it.mkdirs() }

    fun fresh(name: String): File {
        val d = File(workDir, name)
        d.deleteRecursively()
        d.mkdirs()
        return d
    }

    class Cmp(val psnr: Double, val meanAbs: Double, val covered: Int)

    /** PSNR a stredná absolútna chyba voči pravde na pokrytých bodoch. `fitGain` doladí globálny jas výsledku. */
    fun compare(res: StitchResult, gt0: Mat, fitGain: Boolean = false, blur: Double = 0.0): Cmp {
        var gt = gt0
        val img = Imgcodecs.imread(res.outFile.absolutePath)
        if (blur > 0) {
            org.opencv.imgproc.Imgproc.GaussianBlur(img, img, org.opencv.core.Size(0.0, 0.0), blur)
            gt = Mat(); org.opencv.imgproc.Imgproc.GaussianBlur(gt0, gt, org.opencv.core.Size(0.0, 0.0), blur)
        }
        val w = img.cols()
        val h = img.rows()
        val a = ByteArray(w * h * 3)
        img.get(0, 0, a)
        val sub = gt.submat(Rect(0, res.cropTop, w, h))
        val b = ByteArray(w * h * 3)
        sub.get(0, 0, b)
        var sa = 0.0
        var sb = 0.0
        var n = 0
        for (p in 0 until w * h) {
            val o = p * 3
            if (((a[o].toInt() and 0xFF) + (a[o + 1].toInt() and 0xFF) + (a[o + 2].toInt() and 0xFF)) < 6) continue
            for (c in 0..2) { sa += a[o + c].toInt() and 0xFF; sb += b[o + c].toInt() and 0xFF }
            n++
        }
        val k = if (fitGain && sa > 0) sb / sa else 1.0
        var se = 0.0
        var ab = 0.0
        for (p in 0 until w * h) {
            val o = p * 3
            if (((a[o].toInt() and 0xFF) + (a[o + 1].toInt() and 0xFF) + (a[o + 2].toInt() and 0xFF)) < 6) continue
            for (c in 0..2) {
                val d = (a[o + c].toInt() and 0xFF) * k - (b[o + c].toInt() and 0xFF)
                se += d * d
                ab += Math.abs(d)
            }
        }
        val mse = se / (n * 3).coerceAtLeast(1)
        img.release(); sub.release()
        return Cmp(10 * log10(255.0 * 255.0 / mse.coerceAtLeast(1e-9)), ab / (n * 3).coerceAtLeast(1), n)
    }

    /** Stredná chyba rotácie záberov po odstránení spoločného posunu (stupne). */
    fun poseError(res: StitchResult, s: SynthSession): Double {
        val rr = res.refinedRotations
        val ids = res.usedFrameIndices
        val q = rr[0] * s.trueR[ids[0]].transpose()
        var sum = 0.0
        for ((k, i) in ids.withIndex()) {
            val e = (rr[k] * s.trueR[i].transpose()) * q.transpose()
            sum += e.angleDeg()
        }
        return sum / ids.size
    }

    fun saveSmall(src: File, dst: File, maxW: Int = 1600) {
        val m = Imgcodecs.imread(src.absolutePath)
        if (m.empty()) return
        val sc = minOf(1.0, maxW.toDouble() / m.cols())
        val s = Mat()
        org.opencv.imgproc.Imgproc.resize(m, s, org.opencv.core.Size(m.cols() * sc, m.rows() * sc), 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA)
        Imgcodecs.imwrite(dst.absolutePath, s)
        m.release(); s.release()
    }
}

class StitchSyntheticTest {
    companion object {
        const val W = 2048
        /** Vzdialená scéna: kamera uprostred obrovskej miestnosti (podlaha aj strop ≥ 100 m), inak by podlaha pod 1,5 m robila paralaxu. */
        val FAR_PIVOT = doubleArrayOf(0.0, 0.0, 100.0)
        val farTex by lazy { SyntheticRoom.proceduralTextures(12, 1024, 7) }

        @BeforeClass @JvmStatic fun load() { OpenCV.loadLocally() }

        val farRoom by lazy { SyntheticRoom(400.0, 400.0, 200.0, 40.0, farTex) }
        val farMain: SynthSession by lazy {
            SyntheticSession.generate(farRoom, SynthEval.fresh("far_main"), SynthParams(armM = 0.25, sensorNoiseDeg = 1.5, seed = 11, pivot = FAR_PIVOT))
        }
    }

    private fun stitch(s: SynthSession, name: String, opt: StitchOptions): StitchResult {
        val out = File(SynthEval.outDir, "$name.jpg")
        out.delete()
        return PanoStitcher.stitch(s.session, s.dir, out, opt)
    }

    private fun opts(width: Int = W, name: String, block: StitchOptions.() -> Unit = {}): StitchOptions {
        val dbg = File(SynthEval.outDir, "${name}_dbg")
        return StitchOptions(outWidth = width, debugDir = dbg)
    }

    private fun checkFarScene(s: SynthSession, name: String, minPsnr: Double) {
        val res = stitch(s, name, opts(name = name))
        val gtFrame = s.groundTruthPano(res.panoFrame!!)
        val gt = farRoom.renderEquirect(s.pivot, gtFrame, res.fullWidth)
        Imgcodecs.imwrite(File(SynthEval.outDir, "${name}_gt.jpg").absolutePath, gt)
        val raw = SynthEval.compare(res, gt)
        // textúra je takmer biely šum na úrovni pixelov plátna; rozdiel filtrov (INTER_AREA vs. LOD) preto vyhodnocujeme po mierom rozmazaní oboch
        val cmp = SynthEval.compare(res, gt, blur = 1.5)
        println("[$name] PSNR bez rozmazania ${"%.2f".format(raw.psnr)} dB")
        val pose = SynthEval.poseError(res, s)
        println("[$name] zábery ${res.framesUsed}, doladených ${res.framesRefined}, rms ${"%.3f".format(res.rmsErrorDeg)}°, chyba póz ${"%.3f".format(pose)}°, PSNR ${"%.2f".format(cmp.psnr)} dB, MAE ${"%.2f".format(cmp.meanAbs)}, pokrytie ${"%.4f".format(res.coverage)}, ohnisko ${"%.1f".format(res.focalPx)} px, nesúlad švov ${"%.2f".format(res.seamMismatch)} varovania=${res.warnings}")
        SynthEval.saveSmall(res.outFile, File(SynthEval.outDir, "${name}_small.jpg"))
        assertTrue("chyba póz $pose", pose < 0.15)
        assertTrue("PSNR ${cmp.psnr}", cmp.psnr > minPsnr)
        assertTrue("pokrytie ${res.coverage}", res.coverage > 0.995)
        val info = XmpGPano.read(res.outFile)
        assertNotNull(info)
        assertTrue(info!!.isFullSphere)
        assertEquals(res.fullWidth, info.fullPanoWidth)
        assertEquals(2 * info.fullPanoHeight, info.fullPanoWidth)
        assertEquals(res.height, res.fullHeight)
    }

    @Test fun a_farSceneMainLens() = checkFarScene(farMain, "a_far_main", 27.0)

    @Test fun b_farSceneUltraWide() {
        val s = SyntheticSession.generate(farRoom, SynthEval.fresh("far_uw"), SynthParams(hFovDeg = 85.6, vFovDeg = 102.0, armM = 0.25, sensorNoiseDeg = 1.5, seed = 12, pivot = FAR_PIVOT))
        checkFarScene(s, "b_far_ultrawide", 27.0)
    }

    @Test fun c_gainCompensation() {
        val s = SyntheticSession.generate(farRoom, SynthEval.fresh("far_gain"), SynthParams(armM = 0.25, sensorNoiseDeg = 1.5, gainRange = 0.25, seed = 13, pivot = FAR_PIVOT))
        val on = stitch(s, "c_gain_on", StitchOptions(outWidth = W, gainCompensation = true))
        val off = stitch(s, "c_gain_off", StitchOptions(outWidth = W, gainCompensation = false))
        val gt = farRoom.renderEquirect(s.pivot, s.groundTruthPano(on.panoFrame!!), on.fullWidth)
        val cOn = SynthEval.compare(on, gt, fitGain = true)
        val cOff = SynthEval.compare(off, gt, fitGain = true)
        println("[gain] zapnuté: MAE ${"%.2f".format(cOn.meanAbs)} PSNR ${"%.2f".format(cOn.psnr)} nesúlad ${"%.2f".format(on.seamMismatch)} | vypnuté: MAE ${"%.2f".format(cOff.meanAbs)} PSNR ${"%.2f".format(cOff.psnr)} nesúlad ${"%.2f".format(off.seamMismatch)}")
        assertTrue("MAE ${cOn.meanAbs} vs ${cOff.meanAbs}", cOn.meanAbs < cOff.meanAbs * 0.85)
        assertTrue("nesúlad ${on.seamMismatch} vs ${off.seamMismatch}", on.seamMismatch < off.seamMismatch * 0.7)
    }

    @Test fun d_nearRoomSeams() {
        val room = SyntheticRoom(4.2, 5.0, 2.6, 1.5, SyntheticRoom.defaultTextures(usePhotos = true))
        val s = SyntheticSession.generate(room, SynthEval.fresh("near"), SynthParams(armM = 0.25, sensorNoiseDeg = 1.5, seed = 14))
        val on = stitch(s, "d_near_seams_on", StitchOptions(outWidth = W, seamOptimization = true))
        val off = stitch(s, "d_near_seams_off", StitchOptions(outWidth = W, seamOptimization = false))
        val gt = room.renderEquirect(s.pivot, s.groundTruthPano(on.panoFrame!!), on.fullWidth)
        val cOn = SynthEval.compare(on, gt)
        val cOff = SynthEval.compare(off, gt)
        println("[blízka izba] švy zapnuté: nesúlad ${"%.3f".format(on.seamMismatch)} MAE voči pivotu ${"%.2f".format(cOn.meanAbs)} PSNR ${"%.2f".format(cOn.psnr)} | vypnuté: nesúlad ${"%.3f".format(off.seamMismatch)} MAE ${"%.2f".format(cOff.meanAbs)} PSNR ${"%.2f".format(cOff.psnr)}; pokrytie ${"%.4f".format(on.coverage)} doladených ${on.framesRefined}/${on.framesUsed}")
        SynthEval.saveSmall(on.outFile, File(SynthEval.outDir, "d_near_on_small.jpg"))
        SynthEval.saveSmall(off.outFile, File(SynthEval.outDir, "d_near_off_small.jpg"))
        assertTrue(on.coverage > 0.995)
        assertTrue("nesúlad zap ${on.seamMismatch} vs vyp ${off.seamMismatch}", on.seamMismatch <= off.seamMismatch)
    }

    @Test fun e_partialHorizonRing() {
        val s = SyntheticSession.generate(farRoom, SynthEval.fresh("far_ring"), SynthParams(armM = 0.25, sensorNoiseDeg = 1.5, seed = 15, pivot = FAR_PIVOT, targetFilter = { it.ring == 0 }))
        val res = stitch(s, "e_ring", StitchOptions(outWidth = W))
        val info = XmpGPano.read(res.outFile)!!
        println("[pás] ${res.width}×${res.height} z ${res.fullWidth}×${res.fullHeight}, top ${res.cropTop}, pokrytie ${"%.3f".format(res.coverage)}")
        assertTrue(res.height < res.fullHeight)
        assertEquals(res.fullWidth, 2 * res.fullHeight)
        assertEquals(res.height, info.croppedHeight)
        assertEquals(res.cropTop, info.croppedTop)
        assertEquals(res.fullWidth, info.fullPanoWidth)
        assertEquals(res.fullHeight, info.fullPanoHeight)
        assertFalse(info.isFullSphere)
        val img = Imgcodecs.imread(res.outFile.absolutePath)
        assertEquals(res.height, img.rows())
        assertEquals(res.width, img.cols())
    }

    @Test fun f_noRefineAndFeatherPaths() {
        val a = stitch(farMain, "f_norefine", StitchOptions(outWidth = 1024, refine = false))
        assertTrue(a.outFile.exists())
        assertTrue(a.rmsErrorDeg.isNaN())
        assertEquals(0, a.framesRefined)
        val b = stitch(farMain, "f_feather", StitchOptions(outWidth = 1024, blendBands = 0, seamOptimization = false, gainCompensation = false))
        assertTrue(b.outFile.exists())
        assertTrue(b.coverage > 0.99)
        val c = stitch(farMain, "f_nolayers", StitchOptions(outWidth = 1024, blendBands = 2, cropUncovered = false))
        assertEquals(c.fullHeight, c.height)
    }

    @Test fun g_cancellation() {
        val out = File(SynthEval.outDir, "g_cancel.jpg")
        out.delete()
        var cancel = false
        try {
            PanoStitcher.stitch(farMain.session, farMain.dir, out, StitchOptions(
                outWidth = 1024,
                onProgress = { st, f -> if (st == StitchStage.BLEND && f > 0.6f) cancel = true },
                isCancelled = { cancel },
            ))
            fail("očakávala sa StitchCancelledException")
        } catch (e: StitchCancelledException) {
        }
        assertFalse(out.exists())
        // aj hneď na začiatku
        try {
            PanoStitcher.stitch(farMain.session, farMain.dir, out, StitchOptions(outWidth = 1024, isCancelled = { true }))
            fail()
        } catch (e: StitchCancelledException) {
        }
        assertFalse(out.exists())
    }

    @Test fun h_orientationMarkers() {
        // asymetrické značky vo svete: vpravo (yaw +90), hore-vpredu (pitch +45), vľavo-dole (yaw −90, pitch −30)
        val tp = PanoFrame(doubleArrayOf(Math.cos(Math.toRadians(30.0)), Math.sin(Math.toRadians(30.0)), 0.0),
            Vec.normalize(Vec.cross(doubleArrayOf(Math.cos(Math.toRadians(30.0)), Math.sin(Math.toRadians(30.0)), 0.0), SphereGeo.UP)), SphereGeo.UP)
        val markers = listOf(
            Marker(tp.dir(90.0, 0.0), 7.0, intArrayOf(0, 0, 255)), // červená (BGR)
            Marker(tp.dir(0.0, 45.0), 7.0, intArrayOf(0, 255, 0)), // zelená
            Marker(tp.dir(-90.0, -30.0), 7.0, intArrayOf(255, 0, 0)), // modrá
        )
        val room = SyntheticRoom(400.0, 400.0, 200.0, 40.0, farTex, markers)
        val s = SyntheticSession.generate(room, SynthEval.fresh("orient"), SynthParams(armM = 0.0, sensorNoiseDeg = 0.5, seed = 16, pivot = FAR_PIVOT, panoYawDeg = 30.0))
        val res = stitch(s, "h_orient", StitchOptions(outWidth = W))
        val img = Imgcodecs.imread(res.outFile.absolutePath)
        fun px(yaw: Double, pitch: Double): IntArray {
            val u = ((yaw / 360.0 + 0.5) * res.fullWidth).toInt()
            val v = ((0.5 - pitch / 180.0) * res.fullHeight).toInt() - res.cropTop
            val d = img.get(v, u)
            return intArrayOf(d[0].toInt(), d[1].toInt(), d[2].toInt())
        }
        val r = px(90.0, 0.0)
        val g = px(0.0, 45.0)
        val b = px(-90.0, -30.0)
        println("[orientácia] vpravo=${r.toList()} (BGR, čakám červenú), hore=${g.toList()} (zelená), vľavo-dole=${b.toList()} (modrá)")
        assertTrue("červená vpravo", r[2] > 200 && r[0] < 80 && r[1] < 80)
        assertTrue("zelená hore", g[1] > 200 && g[0] < 80 && g[2] < 80)
        assertTrue("modrá vľavo-dole", b[0] > 200 && b[1] < 80 && b[2] < 80)
    }
}
