package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareScoringTest {
    private fun m(
        path: String,
        sharp: Double = 100.0,
        exposure: Double = 1.0,
        faces: Int = 0,
        closed: Int = 0,
        smiles: Int = 0,
        checked: Boolean = true,
        w: Int = 4000,
        h: Int = 3000,
        modified: Long = 1_000L,
    ) = PhotoMeasure(path, sharp, exposure, faces, closed, smiles, checked, w, h, modified)

    private fun best(scores: Map<String, PhotoScore>) = scores.entries.first { it.value.isBest }.key

    @Test
    fun emptyGroupGivesNothing() {
        assertTrue(CompareScoring.score(emptyList()).isEmpty())
    }

    @Test
    fun groupWithoutFacesUsesOnlySharpnessAndExposure() {
        val s = CompareScoring.score(listOf(m("/DCIM/Camera/a.jpg"), m("/DCIM/Camera/b.jpg", sharp = 50.0)))
        assertEquals(1.0, s.getValue("/DCIM/Camera/a.jpg").score, 1e-9)
        // (0.55 * 0.5 + 0.15) / 0.70
        assertEquals((0.55 * 0.5 + 0.15) / 0.70, s.getValue("/DCIM/Camera/b.jpg").score, 1e-9)
        assertFalse(s.values.any { it.faces > 0 })
    }

    @Test
    fun photoWithoutFoundFaceDoesNotGetOpenEyes() {
        // rovnako ostré; na B sa tvár nenašla (otočená hlava) — predtým dostala plných 25 % za oči
        val s = CompareScoring.score(
            listOf(m("/c/a.jpg", faces = 1), m("/c/b.jpg", faces = 0)),
        )
        val a = s.getValue("/c/a.jpg")
        val b = s.getValue("/c/b.jpg")
        assertEquals(0.95, a.score, 1e-9)
        assertEquals(0.55 + 0.15 - CompareScoring.MISSING_FACE_PENALTY, b.score, 1e-9)
        assertEquals(0, b.eyesOpen)
        assertEquals("/c/a.jpg", best(s))
    }

    @Test
    fun fewerFacesThanBestFrameLosesPoints() {
        val s = CompareScoring.score(listOf(m("/c/a.jpg", faces = 3), m("/c/b.jpg", faces = 2)))
        val b = s.getValue("/c/b.jpg")
        val expected = 0.55 + 0.15 + 0.25 * 2 / 3 - CompareScoring.MISSING_FACE_PENALTY / 3
        assertEquals(expected, b.score, 1e-9)
        assertTrue(b.reasons.contains(Reason(Reason.Kind.FEWER_FACES, 2, 3)))
    }

    @Test
    fun closedEyesLoseToOpenEyesEvenIfSlightlySharper() {
        val s = CompareScoring.score(
            listOf(m("/c/a.jpg", sharp = 100.0, faces = 2, closed = 1), m("/c/b.jpg", sharp = 90.0, faces = 2)),
        )
        assertEquals("/c/b.jpg", best(s))
        val bestReasons = s.getValue("/c/b.jpg").reasons
        assertTrue(bestReasons.contains(Reason(Reason.Kind.ALL_EYES_OPEN, 2)))
        assertTrue(s.getValue("/c/a.jpg").reasons.contains(Reason(Reason.Kind.EYES_CLOSED, 1)))
    }

    @Test
    fun facesAreIgnoredWhenNotCheckedOnAllPhotos() {
        val s = CompareScoring.score(
            listOf(m("/c/a.jpg", faces = 2, closed = 2), m("/c/b.jpg", sharp = 80.0, checked = false)),
        )
        // bez porovnania tvárí rozhoduje ostrosť
        assertEquals("/c/a.jpg", best(s))
        assertFalse(s.getValue("/c/a.jpg").facesChecked)
        assertEquals(0, s.getValue("/c/a.jpg").faces)
    }

    @Test
    fun nearTieIsDecidedByResolution() {
        // presné kópie: WhatsApp kópia je o kúsok „ostrejšia“ (šum), ale originál má vyššie rozlíšenie
        val s = CompareScoring.score(
            listOf(
                m("/WhatsApp/Media/WhatsApp Images/a.jpg", sharp = 100.0, w = 1600, h = 1200),
                m("/DCIM/Camera/a.jpg", sharp = 96.0),
            ),
        )
        assertEquals("/DCIM/Camera/a.jpg", best(s))
        assertEquals(Reason.Kind.HIGHER_RESOLUTION, s.getValue("/DCIM/Camera/a.jpg").reasons.first().kind)
        assertEquals(120, s.getValue("/DCIM/Camera/a.jpg").reasons.first().value)
    }

    @Test
    fun nearTieWithSameResolutionPrefersCameraFolder() {
        val s = CompareScoring.score(
            listOf(
                m("/Download/a.jpg", sharp = 100.0),
                m("/Pictures/a.jpg", sharp = 99.0),
                m("/DCIM/Camera/a.jpg", sharp = 97.0),
            ),
        )
        assertEquals("/DCIM/Camera/a.jpg", best(s))
        assertEquals(Reason(Reason.Kind.ORIGINAL_FOLDER), s.getValue("/DCIM/Camera/a.jpg").reasons.first())
        // poradie: Fotoaparát, ostatné, kópie
        assertEquals(1, s.getValue("/Pictures/a.jpg").rank)
        assertEquals(2, s.getValue("/Download/a.jpg").rank)
    }

    @Test
    fun nearTieInSameFolderPrefersOlderFile() {
        val s = CompareScoring.score(
            listOf(
                m("/DCIM/Camera/b.jpg", sharp = 100.0, modified = 5_000L),
                m("/DCIM/Camera/a.jpg", sharp = 98.0, modified = 2_000L),
            ),
        )
        assertEquals("/DCIM/Camera/a.jpg", best(s))
        assertEquals(Reason(Reason.Kind.OLDER_FILE), s.getValue("/DCIM/Camera/a.jpg").reasons.first())
    }

    @Test
    fun clearWinnerIsNotOverruledByTieBreak() {
        val s = CompareScoring.score(
            listOf(m("/DCIM/Camera/a.jpg", sharp = 50.0), m("/Download/b.jpg", sharp = 100.0, w = 800, h = 600)),
        )
        assertEquals("/Download/b.jpg", best(s))
        val reasons = s.getValue("/Download/b.jpg").reasons
        assertEquals(Reason(Reason.Kind.SHARPER, 100), reasons.first())
        assertTrue(s.getValue("/DCIM/Camera/a.jpg").reasons.contains(Reason(Reason.Kind.BLURRIER, 50)))
    }

    @Test
    fun ranksAreUniqueAndOrdered() {
        val s = CompareScoring.score((1..6).map { m("/DCIM/Camera/$it.jpg", sharp = it * 10.0, modified = it * 1L) })
        assertEquals((0..5).toSet(), s.values.map { it.rank }.toSet())
        assertEquals("/DCIM/Camera/6.jpg", best(s))
        // mapa ostáva v poradí vstupu
        assertEquals((1..6).map { "/DCIM/Camera/$it.jpg" }, s.keys.toList())
    }

    @Test
    fun reasonsAreLimitedToThree() {
        val s = CompareScoring.score(
            listOf(
                m("/c/a.jpg", sharp = 100.0, faces = 3, smiles = 2),
                m("/c/b.jpg", sharp = 50.0, exposure = 0.5, faces = 2, closed = 1, w = 1000, h = 1000),
            ),
        )
        assertEquals(3, s.getValue("/c/a.jpg").reasons.size)
        assertEquals(3, s.getValue("/c/b.jpg").reasons.size)
        assertTrue(s.getValue("/c/a.jpg").reasons.all { it.kind.positive })
        assertTrue(s.getValue("/c/b.jpg").reasons.none { it.kind.positive })
    }

    @Test
    fun folderKinds() {
        val root = "/storage/emulated/0"
        assertEquals(CompareScoring.FOLDER_CAMERA, CompareScoring.folderKind("$root/DCIM/Camera/a.jpg"))
        assertEquals(CompareScoring.FOLDER_CAMERA, CompareScoring.folderKind("$root/DCIM/OpenCamera/a.jpg"))
        assertEquals(CompareScoring.FOLDER_COPY, CompareScoring.folderKind("$root/DCIM/Screenshots/a.jpg"))
        val whatsapp = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/a.jpg"
        assertEquals(CompareScoring.FOLDER_COPY, CompareScoring.folderKind(whatsapp))
        assertEquals(CompareScoring.FOLDER_COPY, CompareScoring.folderKind("$root/Download/a.jpg"))
        assertEquals(CompareScoring.FOLDER_OTHER, CompareScoring.folderKind("$root/Pictures/Dovolenka/a.jpg"))
    }

    @Test
    fun reasonCodesAreStable() {
        assertEquals("all_eyes_open", Reason(Reason.Kind.ALL_EYES_OPEN, 3).code)
        assertEquals(87, PhotoScore(1.0, 1.0, 0, 0, 0, 0.871, emptyList()).percent)
    }
}
