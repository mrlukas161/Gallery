package org.fossify.gallery.spherecore

import java.io.File

internal object XmpGPanoImpl {
    fun write(jpeg: File, info: GPanoInfo): Unit = TODO("zápis XMP")
    fun read(jpeg: File): GPanoInfo? = TODO("čítanie XMP")
    fun read(head: ByteArray): GPanoInfo? = TODO("čítanie XMP")
    fun buildPacket(info: GPanoInfo): String = TODO("paket XMP")
}
