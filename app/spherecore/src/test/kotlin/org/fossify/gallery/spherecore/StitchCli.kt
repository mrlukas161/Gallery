package org.fossify.gallery.spherecore

import java.io.File
import java.lang.management.ManagementFactory

/**
 * Zloženie relácie z príkazového riadka:
 *   <priečinok relácie> <výstup.jpg> [šírka] [--no-refine] [--no-seams] [--no-gain] [--bands N] [--debug <priečinok>]
 * Vypíše súhrn výsledku, čas a špičkovú pamäť procesu (z JMX; natívna pamäť OpenCV sa meria zvonka).
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("použitie: <priečinok relácie> <výstup.jpg> [šírka] [--no-refine] [--no-seams] [--no-gain] [--bands N] [--debug dir]")
        return
    }
    nu.pattern.OpenCV.loadLocally()
    val dir = File(args[0])
    val out = File(args[1])
    var width = 8192
    var refine = true
    var seams = true
    var gain = true
    var bands = 5
    var debug: File? = null
    var i = 2
    while (i < args.size) {
        when (val a = args[i]) {
            "--no-refine" -> refine = false
            "--no-seams" -> seams = false
            "--no-gain" -> gain = false
            "--bands" -> bands = args[++i].toInt()
            "--debug" -> debug = File(args[++i])
            else -> width = a.toInt()
        }
        i++
    }
    val session = SphereSession.load(dir)
    val t0 = System.nanoTime()
    var lastStage = ""
    val res = PanoStitcher.stitch(
        session, dir, out,
        StitchOptions(
            outWidth = width, refine = refine, seamOptimization = seams, gainCompensation = gain, blendBands = bands,
            debugDir = debug ?: out.parentFile,
            onProgress = { s, f -> if (s != lastStage) { lastStage = s; println("  fáza $s (${"%.0f".format(f * 100)} %)") } },
        ),
    )
    val sec = (System.nanoTime() - t0) / 1e9
    println("výsledok: ${res.outFile} ${res.width}×${res.height} z ${res.fullWidth}×${res.fullHeight} (top ${res.cropTop}), zábery ${res.framesUsed}, doladených ${res.framesRefined}")
    println("ohnisko ${"%.1f".format(res.focalPx)} px, rms ${"%.3f".format(res.rmsErrorDeg)}°, pokrytie ${"%.4f".format(res.coverage)}, nesúlad švov ${"%.2f".format(res.seamMismatch)}")
    for (w in res.warnings) println("varovanie: $w")
    println("čas ${"%.1f".format(sec)} s")
    val mem = ManagementFactory.getMemoryMXBean()
    println("halda JVM použitá ${mem.heapMemoryUsage.used / (1 shl 20)} MB (maximum ${mem.heapMemoryUsage.max / (1 shl 20)} MB)")
}
