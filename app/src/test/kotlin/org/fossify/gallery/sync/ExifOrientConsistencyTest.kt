package org.fossify.gallery.sync

import org.fossify.gallery.family.RelBox
import org.junit.Assert.assertEquals
import org.junit.Test

// Spojenie 0.66.0: ExifOrient (náhľady pre PC porovnávač: najprv otoč, potom zrkadli) a RelBox.exifTransform
// (tváre, UprightDecoder: najprv zrkadli, potom otoč) zapisujú tú istú transformáciu dvomi rozkladmi.
// Zrkadlenie M a otočenie R spĺňajú M·R(φ) = R(−φ)·M, preto „otoč 90° + zrkadli" = „zrkadli + otoč 270°".
// Test to drží pokope pre všetkých osem EXIF orientácií: ak sa jedna strana raz zmení, porovnávač a tváre
// sa nesmú rozísť (fotka by bola v PC otočená inak než v appke a rámčeky tvárí).
class ExifOrientConsistencyTest {

    private val boxes = listOf(
        RelBox(0.1f, 0.2f, 0.3f, 0.6f),
        RelBox(0.0f, 0.0f, 1.0f, 1.0f),
        RelBox(0.55f, 0.05f, 0.9f, 0.35f),
    )

    @Test
    fun porovnavac_a_tvare_otacaju_vsetkych_osem_orientacii_rovnako() {
        for (orientation in 1..8) {
            val server = ExifOrient.of(orientation)
            val faces = RelBox.exifTransform(orientation)
            for (box in boxes) {
                // porovnávač: najprv otočenie, potom vodorovné zrkadlenie
                val rotated = box.transformed(server.degrees, false)
                val viaServer = if (server.mirror) rotated.transformed(0, true) else rotated
                // tváre: transformed() = najprv zrkadlenie, potom otočenie
                val viaFaces = box.transformed(faces.rotationDegrees, faces.mirrored)
                assertBox("EXIF $orientation", viaFaces, viaServer)
            }
        }
    }

    @Test
    fun exif_5_je_transpozicia_a_7_anti_transpozicia() {
        val src = RelBox(0.1f, 0.2f, 0.3f, 0.6f)
        // 5: (x, y) -> (y, x)
        val t5 = ExifOrient.of(5)
        assertBox("EXIF 5", RelBox(0.2f, 0.1f, 0.6f, 0.3f), src.transformed(t5.degrees, false).transformed(0, t5.mirror))
        // 7: (x, y) -> (1 - y, 1 - x)
        val t7 = ExifOrient.of(7)
        assertBox("EXIF 7", RelBox(0.4f, 0.7f, 0.8f, 0.9f), src.transformed(t7.degrees, false).transformed(0, t7.mirror))
    }

    private fun assertBox(msg: String, expected: RelBox, actual: RelBox) {
        assertEquals("$msg left", expected.left, actual.left, EPS)
        assertEquals("$msg top", expected.top, actual.top, EPS)
        assertEquals("$msg right", expected.right, actual.right, EPS)
        assertEquals("$msg bottom", expected.bottom, actual.bottom, EPS)
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
