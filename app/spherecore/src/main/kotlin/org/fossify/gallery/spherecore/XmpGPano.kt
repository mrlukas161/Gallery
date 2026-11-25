package org.fossify.gallery.spherecore

import java.io.File

/** Metadáta guľovej/panoramatickej fotky podľa špecifikácie Google Photo Sphere XMP (GPano). */
data class GPanoInfo(
    val projectionType: String = "equirectangular",
    val fullPanoWidth: Int,
    val fullPanoHeight: Int,
    val croppedWidth: Int,
    val croppedHeight: Int,
    val croppedLeft: Int = 0,
    val croppedTop: Int = 0,
    val usePanoramaViewer: Boolean = true,
    /** Kompasový azimut stredu panorámy, null = neznámy. */
    val poseHeadingDeg: Double? = null,
    val initialViewHeadingDeg: Double = 0.0,
    val initialHorizontalFovDeg: Double = 75.0,
    val sourcePhotosCount: Int = 0,
    val exposureLockUsed: Boolean = false,
    /** ISO 8601 UTC, napr. 2026-11-26T08:15:00.000Z; prázdne = nezapisovať. */
    val firstPhotoDate: String = "",
    val lastPhotoDate: String = "",
    val software: String = "Galéria+ Sféra",
) {
    /** Celá guľa 360° × 180° (nie výrez). */
    val isFullSphere: Boolean
        get() = croppedLeft == 0 && croppedTop == 0 && croppedWidth == fullPanoWidth && croppedHeight == fullPanoHeight &&
            fullPanoWidth == 2 * fullPanoHeight
}

/**
 * Zápis a čítanie XMP GPano v JPEG. XMP ide do segmentu APP1 s hlavičkou "http://ns.adobe.com/xap/1.0/\u0000",
 * hneď za SOI/APP0/EXIF. Čítanie rozumie atribútovému aj elementovému zápisu (staré fotky z Photo Sphere).
 */
object XmpGPano {
    /** Vloží (alebo nahradí existujúci) XMP paket s GPano do JPEG súboru na mieste. */
    fun write(jpeg: File, info: GPanoInfo) = XmpGPanoImpl.write(jpeg, info)

    /** Prečíta GPano z JPEG; null, ak tam nie je alebo nie je ekvidištančné. */
    fun read(jpeg: File): GPanoInfo? = XmpGPanoImpl.read(jpeg)

    /** To isté z prvých bajtov súboru (napr. z content URI); stačí prvých ~256 kB. */
    fun read(head: ByteArray): GPanoInfo? = XmpGPanoImpl.read(head)

    fun buildPacket(info: GPanoInfo): String = XmpGPanoImpl.buildPacket(info)
}
