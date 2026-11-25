package org.fossify.gallery.spherecore

import java.io.File

/** Vlastné skladanie; verejné rozhranie je [PanoStitcher]. */
internal class PanoStitcherImpl(
    private val session: SphereSession,
    private val sessionDir: File,
    private val outFile: File,
    private val options: StitchOptions,
) {
    fun run(): StitchResult = TODO("skladanie")
}
