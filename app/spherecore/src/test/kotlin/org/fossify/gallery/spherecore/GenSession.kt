package org.fossify.gallery.spherecore

import java.io.File

/** Vygeneruje syntetickú reláciu (vzdialená scéna, hlavný objektív): <priečinok> [šírka záberu] [výška záberu] */
object GenSession {
    @JvmStatic
    fun main(args: Array<String>) {
        nu.pattern.OpenCV.loadLocally()
        val w = args.getOrNull(1)?.toInt() ?: 1500
        val h = args.getOrNull(2)?.toInt() ?: 2000
        val room = SyntheticRoom(400.0, 400.0, 200.0, 40.0, SyntheticRoom.proceduralTextures(12, 1024, 7))
        SyntheticSession.generate(room, File(args[0]), SynthParams(imageWidth = w, imageHeight = h, pivot = doubleArrayOf(0.0, 0.0, 100.0), seed = 11))
        println("hotovo: ${args[0]}")
    }
}
