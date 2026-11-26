package org.fossify.gallery.spherecore

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sqrt

/** Vlastné skladanie; verejné rozhranie je [PanoStitcher]. Pipeline je popísaná v docs/05_Sfera_navrh.md, časť 4. */
internal class PanoStitcherImpl(
    private val session: SphereSession,
    private val sessionDir: File,
    private val outFile: File,
    private val options: StitchOptions,
) {
    private val log = StringBuilder()
    private val warnings = ArrayList<String>()
    private var lastProgress = 0f
    private var wroteOut = false
    private val t0 = System.nanoTime()

    private class Loaded(val idx: Int, val frame: SphereFrame, val w: Int, val h: Int, val work: Mat)

    private fun progress(stage: String, frac: Float) {
        val f = maxOf(lastProgress, frac.coerceIn(0f, 1f))
        lastProgress = f
        options.onProgress(stage, f)
    }

    private fun check() {
        if (options.isCancelled()) throw StitchCancelledException()
    }

    private fun note(s: String) {
        log.append(String.format(Locale.ROOT, "[%6.1f s] ", (System.nanoTime() - t0) / 1e9)).append(s).append('\n')
    }

    /** Súkromná (commit) pamäť procesu v MB, len na PC cez JMX; na Androide prázdne. */
    private fun memInfo(): String {
        if (options.debugDir == null) return ""
        return try {
            val b = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            val v = Class.forName("com.sun.management.OperatingSystemMXBean").getMethod("getCommittedVirtualMemorySize").invoke(b) as Long
            ", ${v shr 20} MB"
        } catch (_: Throwable) {
            ""
        }
    }

    private fun warn(s: String) {
        warnings.add(s)
        note("VAROVANIE: $s")
    }

    fun run(): StitchResult {
        try {
            return doRun()
        } catch (e: StitchCancelledException) {
            note("zrušené")
            if (wroteOut) {
                outFile.delete()
                File(outFile.parentFile, outFile.name + ".xmp.tmp").delete()
            }
            throw e
        } catch (e: Throwable) {
            note("CHYBA: $e")
            if (wroteOut) outFile.delete()
            throw e
        } finally {
            val d = options.debugDir
            if (d != null) {
                try {
                    d.mkdirs()
                    File(d, "stitch_log.txt").writeText(log.toString())
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun loadFull(sf: SphereFrame): Mat? {
        val f = File(sessionDir, sf.file)
        if (!f.exists()) return null
        val m = Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_COLOR)
        if (m.empty()) return null
        val lw = session.lens.imageWidth
        val lh = session.lens.imageHeight
        if (lw != lh && (m.cols() < m.rows()) != (lw < lh)) {
            val r = Mat()
            Core.rotate(m, r, Core.ROTATE_90_CLOCKWISE)
            m.release()
            return r
        }
        return m
    }

    private fun doRun(): StitchResult {
        val opt = options
        note("začiatok: ${session.frames.size} záberov, výstup ${opt.outWidth}, refine=${opt.refine}, gain=${opt.gainCompensation}, seams=${opt.seamOptimization}, bands=${opt.blendBands}, vlákna=${Par.threads}")
        // ---------- 1. načítanie ----------
        val used = ArrayList<Loaded>()
        for ((i, sf) in session.frames.withIndex()) {
            check()
            val full = loadFull(sf)
            if (full == null) {
                warn("záber ${sf.file} sa nepodarilo načítať, preskočený")
                continue
            }
            val w = full.cols()
            val h = full.rows()
            val sc = minOf(1.0, 1280.0 / maxOf(w, h))
            val work = Mat()
            if (sc < 0.999) Imgproc.resize(full, work, Size(Math.round(w * sc).toDouble(), Math.round(h * sc).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            else full.copyTo(work)
            full.release()
            used.add(Loaded(i, sf, w, h, work))
            progress(StitchStage.LOAD, 0.05f * (i + 1) / session.frames.size)
        }
        if (used.isEmpty()) throw IllegalStateException("relácia nemá žiadny použiteľný záber")
        val n = used.size
        note("načítaných $n záberov (${used[0].w}×${used[0].h}), pracovná kópia ${used[0].work.cols()}×${used[0].work.rows()}")

        // ---------- geometria záberov ----------
        val lens = session.lens
        val wRef = used[0].w
        val fH = SphereGeo.focalPx(lens.hFovDeg, wRef.toDouble())
        val fV = SphereGeo.focalPx(lens.vFovDeg, used[0].h.toDouble())
        var fRef = fH
        if (Math.abs(fH / fV - 1.0) > 0.12) warn("zorné pole h/v nesedí (f ${"%.0f".format(fH)} vs ${"%.0f".format(fV)}), beriem vodorovné")
        else fRef = sqrt(fH * fV)
        var geoms = Array(n) { FrameGeom(used[it].w, used[it].h, fRef * used[it].w / wRef) }
        val sens = Array(n) { used[it].frame.rCw }
        var rot: Array<Mat3> = Array(n) { sens[it] }
        var rmsDeg = Double.NaN
        var refined = 0
        var focalScale = 1.0

        // ---------- 2–4. príznaky, zhody, doladenie ----------
        if (opt.refine && n >= 2) {
            val feats = arrayOfNulls<FrameFeat>(n)
            var done = 0
            val tf = System.nanoTime()
            val batch = maxOf(Par.threads, 1)
            var s0 = 0
            while (s0 < n) {
                check()
                val cnt = minOf(batch, n - s0)
                Par.forRange(cnt) { k ->
                    val i = s0 + k
                    val gray = Mat()
                    Imgproc.cvtColor(used[i].work, gray, Imgproc.COLOR_BGR2GRAY)
                    feats[i] = Matching.detect(gray, used[i].w, used[i].h)
                    gray.release()
                }
                s0 += cnt
                done = s0
                progress(StitchStage.MATCH, 0.05f + 0.07f * done / n)
            }
            note("SIFT: ${"%.1f".format((System.nanoTime() - tf) / 1e9)} s, príznakov na záber ~${feats.sumOf { it!!.n } / n}")

            // kandidátne dvojice podľa senzorov
            val cand = ArrayList<IntArray>()
            for (i in 0 until n) for (j in i + 1 until n) {
                val ai = SphereGeo.opticalAxis(sens[i])
                val aj = SphereGeo.opticalAxis(sens[j])
                val ang = Vec.angleDeg(ai, aj)
                val diag = 2 * Math.toDegrees(atan(sqrt(geoms[i].tanHalfW * geoms[i].tanHalfW + geoms[i].tanHalfH * geoms[i].tanHalfH)))
                if (ang < 0.95 * diag) cand.add(intArrayOf(i, j))
            }
            note("kandidátnych dvojíc: ${cand.size}")
            val results = arrayOfNulls<PairMatches>(cand.size)
            val tm = System.nanoTime()
            var c0 = 0
            val pb = maxOf(Par.threads * 2, 2)
            while (c0 < cand.size) {
                check()
                val cnt = minOf(pb, cand.size - c0)
                Par.forRange(cnt) { k ->
                    val (i, j) = cand[c0 + k]
                    results[c0 + k] = Matching.matchPair(i, j, feats[i]!!, feats[j]!!, geoms[i], geoms[j], sens[i], sens[j])
                }
                c0 += cnt
                progress(StitchStage.MATCH, 0.12f + 0.10f * c0 / cand.size.coerceAtLeast(1))
            }
            val pairs = ArrayList<PairMatches>()
            for (r in results) {
                if (r == null) continue
                note("dvojica ${r.i}-${r.j}: surové ${r.raw}, po bráne ${r.gated}, RANSAC ${r.ransacInliers}, ponechané ${r.count}")
                if (r.count >= Matching.MIN_INLIERS) pairs.add(r)
            }
            note("zhody: ${"%.1f".format((System.nanoTime() - tm) / 1e9)} s, prijatých dvojíc ${pairs.size}")
            progress(StitchStage.OPTIMIZE, 0.22f)
            if (pairs.isNotEmpty()) {
                val res = PoseRefiner.refine(sens, geoms, 0, pairs, { check() }, { note(it) })
                val inPair = BooleanArray(n)
                for (p in pairs) { inPair[p.i] = true; inPair[p.j] = true }
                rot = res.rot
                focalScale = res.focalScale
                rmsDeg = res.rmsDeg
                for (k in 0 until n) {
                    val corr = (sens[k].transpose() * rot[k]).angleDeg()
                    if (corr > 12.0) {
                        warn("záber ${used[k].frame.file} sa po doladení vzdialil o ${"%.1f".format(corr)}° od senzora, vrátený na senzor")
                        rot[k] = sens[k]
                        inPair[k] = false
                    }
                    if (k != 0 && inPair[k]) refined++
                }
                if (inPair[0]) refined++
                geoms = Array(n) { FrameGeom(used[it].w, used[it].h, geoms[it].f * focalScale) }
                for (k in 0 until n) note("záber $k (${used[k].frame.file}): oprava od senzora ${"%.2f".format((sens[k].transpose() * rot[k]).angleDeg())}°${if (inPair[k]) "" else " (bez zhôd)"}")
                if (refined < n) warn("${n - refined} záberov ostalo podľa senzorov (málo zhôd)")
            } else {
                warn("žiadne dvojice zhôd, ostávam pri orientáciách zo senzorov")
            }
            for (f in feats) { /* pamäť sa uvoľní s poľom */ }
        } else if (opt.refine) {
            note("jediný záber, doladenie sa preskakuje")
        }
        progress(StitchStage.OPTIMIZE, 0.25f)
        check()
        val focalOut = geoms[0].f
        note("ohnisko ${"%.1f".format(focalOut)} px (počiatočné ${"%.1f".format(fRef)})")

        // ---------- plátno ----------
        val outW = maxOf(256, Math.round(opt.outWidth / 64.0).toInt() * 64)
        val W = outW
        val H = W / 2
        val panoFrame = PanoFrame.fromFirstFrame(sens[0])
        val pw = panoFrame.worldToPano()
        val cams = Array(n) { FrameCam(pw * rot[it], geoms[it].f, used[it].w, used[it].h) }
        // nízke rozlíšenie pre zisky a švy: plátno/4, pri veľkom výstupe plátno/8 (rez grafom je inak veľmi pomalý)
        val lowK = if (W > 4096) 8 else 4
        val lw = W / lowK
        val lh = H / lowK

        // ---------- 5. nízke rozlíšenie, zisky ----------
        val lows = ArrayList<LowFrame>()
        val lowPos = IntArray(n) { -1 }
        val tl = System.nanoTime()
        for (i in 0 until n) {
            check()
            val cam = cams[i]
            val rr = Warp.rowRange(cam, lw, lh, 4)
            if (rr == null) { warn("záber ${used[i].frame.file} nepadá do panorámy"); continue }
            val y0 = maxOf(0, rr[0])
            val y1 = minOf(lh, rr[1])
            val work = used[i].work
            val scW = work.cols().toDouble() / cam.w
            val sLo = minOf(1.0, (lw / (2 * Math.PI)) / (cam.f * scW))
            val src = if (sLo < 0.95) Mat().also { Imgproc.resize(work, it, Size(Math.round(work.cols() * sLo).toDouble(), Math.round(work.rows() * sLo).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA) } else work
            val sc = src.cols().toDouble() / cam.w
            val mx = Mat()
            val my = Mat()
            Warp.coarseMaps(cam, sc, lw, lh, 0, Roi(0, y0, lw, y1 - y0), mx, my)
            val img = Warp.remap(src, mx, my)
            val wg = Warp.weightMask(mx, my, src.cols(), src.rows())
            mx.release(); my.release()
            if (src !== work) src.release()
            lowPos[i] = lows.size
            lows.add(LowFrame(i, y0, y1 - y0, img, wg))
            progress(StitchStage.EXPOSURE, 0.25f + 0.06f * (i + 1) / n)
        }
        note("nízke rozlíšenie ${lw}×${lh}: ${"%.1f".format((System.nanoTime() - tl) / 1e9)} s")
        var gains = DoubleArray(lows.size) { 1.0 }
        if (opt.gainCompensation && lows.size >= 2) {
            gains = GainCompensator.solve(lows, lw) { check() }
            note("zisky: " + gains.joinToString(" ") { "%.3f".format(it) })
        }
        progress(StitchStage.EXPOSURE, 0.33f)

        // ---------- 6. švy ----------
        val ts = System.nanoTime()
        val seam = SeamFinder.run(lows, gains, lw, lh, opt.seamOptimization, { check() }) { progress(StitchStage.SEAMS, 0.33f + 0.12f * it) }
        note("švy: ${"%.1f".format((System.nanoTime() - ts) / 1e9)} s, rez grafom pre ${seam.optimizedFrames} záberov (najviac ${seam.maxNodes} uzlov), nesúlad vo švoch ${"%.2f".format(seam.mismatch)}")
        val labels = seam.labels
        for (l in lows) l.release()
        lows.clear()
        val gainOf = DoubleArray(n) { 1.0 }
        // zisky patria záberom v poradí lows; premietnuť podľa lowPos
        for (i in 0 until n) if (lowPos[i] >= 0) gainOf[i] = gains[lowPos[i]]
        progress(StitchStage.SEAMS, 0.45f)
        for (u in used) u.work.release()

        // ---------- 7. prelínanie ----------
        val tb = System.nanoTime()
        val bands = opt.blendBands.coerceIn(0, 8)
        val margin = Blender.roundUp(if (bands > 0) maxOf(64, (1 shl bands) * 8) else 64, maxOf(1 shl bands, 8))
        val blender = Blender(W, H, bands, margin)
        val canvasPxPerRad = W / (2 * Math.PI)
        for (i in 0 until n) {
            check()
            val cam = cams[i]
            val rois = Warp.findRois(cam, W, H, margin, blender.extW, blender.padH, Warp.STEP, blender.align)
            if (rois.isEmpty()) continue
            val full = loadFull(used[i].frame) ?: continue
            val s = minOf(1.0, canvasPxPerRad / cam.f)
            val src = if (s < 0.9) Mat().also { Imgproc.resize(full, it, Size(Math.round(full.cols() * s).toDouble(), Math.round(full.rows() * s).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA); full.release() } else full
            val sc = src.cols().toDouble() / cam.w
            for (roi in rois) {
                check()
                val mx = Mat()
                val my = Mat()
                Warp.coarseMaps(cam, sc, W, H, margin, roi, mx, my)
                val img8 = Warp.remap(src, mx, my)
                val valid8 = Warp.validMask(mx, my, src.cols(), src.rows())
                mx.release(); my.release()
                val mask8 = labelMask(labels, i, lw, lh, margin, roi, lowK)
                Core.bitwise_and(mask8, valid8, mask8)
                if (Core.countNonZero(mask8) > 0) {
                    Warp.fillInvalid(img8, valid8, bands)
                    blender.addRoi(img8, mask8, gainOf[i], roi)
                }
                img8.release(); valid8.release(); mask8.release()
            }
            src.release()
            progress(StitchStage.BLEND, 0.45f + 0.45f * (i + 1) / n)
        }
        val out = blender.finish()
        note("prelínanie: ${"%.1f".format((System.nanoTime() - tb) / 1e9)} s")
        progress(StitchStage.BLEND, 0.90f)
        check()

        // ---------- pokrytie, orezanie ----------
        var top = 0
        var bottom = H - 1
        if (opt.cropUncovered) {
            while (top < H && out.rowCoverage[top] == 0) top++
            while (bottom >= top && out.rowCoverage[bottom] == 0) bottom--
            if (top > bottom) { top = 0; bottom = H - 1 }
        }
        var cw = 0.0
        var cwTot = 0.0
        for (y in 0 until H) {
            val c = cos((0.5 - (y + 0.5) / H) * Math.PI)
            cw += c * out.rowCoverage[y]
            cwTot += c * W
        }
        val coverage = if (cwTot > 0) cw / cwTot else 0.0
        val cropH = bottom - top + 1
        note("pokrytie ${"%.4f".format(coverage)}, výrez riadkov $top..$bottom (výška $cropH z $H)")

        // ---------- 8. uloženie ----------
        progress(StitchStage.SAVE, 0.92f)
        val result = out.image.submat(Rect(0, top, W, cropH))
        outFile.parentFile?.mkdirs()
        wroteOut = true
        val ok = Imgcodecs.imwrite(outFile.absolutePath, result, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, opt.jpegQuality.coerceIn(30, 100)))
        result.release()
        out.image.release()
        if (!ok) throw java.io.IOException("zápis JPEG zlyhal: ${outFile.absolutePath}")
        check()
        val times = used.map { it.frame.timeMs }.filter { it > 0 }
        val info = GPanoInfo(
            fullPanoWidth = W, fullPanoHeight = H, croppedWidth = W, croppedHeight = cropH,
            croppedLeft = 0, croppedTop = top,
            poseHeadingDeg = session.headingDeg,
            sourcePhotosCount = n, exposureLockUsed = session.exposureLocked,
            firstPhotoDate = if (times.isEmpty()) "" else isoUtc(times.min()),
            lastPhotoDate = if (times.isEmpty()) "" else isoUtc(times.max()),
        )
        XmpGPano.write(outFile, info)
        progress(StitchStage.SAVE, 1f)
        note("hotovo: ${outFile.name} ${W}×$cropH, ${outFile.length() / 1024} kB")
        return StitchResult(
            outFile = outFile, width = W, height = cropH, fullWidth = W, fullHeight = H, cropLeft = 0, cropTop = top,
            framesUsed = n, framesRefined = refined, focalPx = focalOut, rmsErrorDeg = rmsDeg, coverage = coverage,
            warnings = warnings.toList(),
            refinedRotations = rot.toList(), usedFrameIndices = used.map { it.idx },
            panoFrame = panoFrame, gains = gainOf.toList(), seamMismatch = seam.mismatch,
        )
    }

    /** Maska (8U, 0/255) územia záberu `idx` vo výreze rozšíreného plátna: štítky z nízkeho rozlíšenia, zväčšené bilineárne. */
    private fun labelMask(labels: IntArray, idx: Int, lw: Int, lh: Int, margin: Int, roi: Roi, k: Int): Mat {
        val wl = roi.w / k
        val hl = roi.h / k
        val buf = ByteArray(wl * hl)
        val mOff = margin / k
        for (y in 0 until hl) {
            val yl = roi.y0 / k + y
            if (yl >= lh) break
            for (x in 0 until wl) {
                val xl = roi.x0 / k + x - mOff
                val col = ((xl % lw) + lw) % lw
                if (labels[yl * lw + col] == idx) buf[y * wl + x] = -1
            }
        }
        val low = Mat(hl, wl, CvType.CV_8U)
        low.put(0, 0, buf)
        val up = Mat()
        Imgproc.resize(low, up, Size(roi.w.toDouble(), roi.h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        low.release()
        Imgproc.threshold(up, up, 127.0, 255.0, Imgproc.THRESH_BINARY)
        return up
    }

    private fun isoUtc(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }
}
