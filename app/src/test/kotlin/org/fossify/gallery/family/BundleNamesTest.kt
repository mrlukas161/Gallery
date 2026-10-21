package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// D4: WhatsApp kópia, ktorú prijímateľ spracoval ešte pred synchronizáciou — tváre z balíka sa nezapíšu
// (už má svoje), ale mená sa prevezmú cez párovanie relatívnych rámčekov.
class BundleNamesTest {
    private val mama = RelBox(0.10f, 0.20f, 0.30f, 0.50f)
    private val ocko = RelBox(0.60f, 0.20f, 0.80f, 0.50f)

    // vlastná detekcia prijímateľa na prekomprimovanej kópii: rámčeky mierne posunuté
    private val local = listOf(
        11L to listOf(RelBox(0.11f, 0.21f, 0.31f, 0.52f)),
        12L to listOf(RelBox(0.59f, 0.19f, 0.79f, 0.49f)),
    )
    private val bundle = listOf(
        BundleFace(mama, 0.9f, familyUuid = "u-mama", manual = true),
        BundleFace(ocko, 0.9f, familyUuid = "u-ocko", manual = false),
    )
    private val persons = mapOf("u-mama" to 1L, "u-ocko" to 2L)

    @Test
    fun whatsappCopyProcessedBeforeSyncGetsNames() {
        val pairs = BundleNames.pair(bundle, local, FaceMatch.MIN_IOU)
        assertEquals(mapOf(0 to 11L, 1 to 12L), pairs)
        val writes = BundleNames.writes(bundle, pairs, NamePolicy.FULL, { persons[it] }, { null })
        assertEquals(
            listOf(BundleNames.Write(11L, 1L, true), BundleNames.Write(12L, 2L, false)),
            writes,
        )
    }

    @Test
    fun exactCopyNeedsHigherOverlap() {
        val shifted = listOf(11L to listOf(RelBox(0.14f, 0.24f, 0.34f, 0.54f)))
        assertEquals(mapOf(0 to 11L), BundleNames.pair(bundle.take(1), shifted, FaceMatch.MIN_IOU))
        assertTrue(BundleNames.pair(bundle.take(1), shifted, FaceMatch.MIN_IOU_EXACT).isEmpty())
    }

    @Test
    fun manualAssignmentOfReceiverIsNeverOverwritten() {
        val pairs = BundleNames.pair(bundle, local, FaceMatch.MIN_IOU)
        val current = mapOf(11L to LocalAssignment(3L, manual = true), 12L to LocalAssignment(3L, manual = false))
        val writes = BundleNames.writes(bundle, pairs, NamePolicy.FULL, { persons[it] }, { current[it] })
        // ručné (11) ostane, automatické (12) neprepíše automatické z balíka
        assertTrue(writes.isEmpty())
        // ručné z balíka prepíše automatické
        val auto = mapOf(11L to LocalAssignment(3L, manual = false))
        assertEquals(
            listOf(BundleNames.Write(11L, 1L, true), BundleNames.Write(12L, 2L, false)),
            BundleNames.writes(bundle, pairs, NamePolicy.FULL, { persons[it] }, { auto[it] }),
        )
    }

    @Test
    fun seriesPolicyLimitsNames() {
        val pairs = BundleNames.pair(bundle, local, FaceMatch.MIN_IOU)
        val auto = BundleNames.writes(bundle, pairs, NamePolicy.AUTOMATIC, { persons[it] }, { null })
        assertTrue(auto.none { it.manual })
        assertTrue(BundleNames.writes(bundle, pairs, NamePolicy.NONE, { persons[it] }, { null }).isEmpty())
        val forNew = BundleNames.forNewFaces(bundle, NamePolicy.AUTOMATIC) { persons[it] }
        assertEquals(false, forNew[0]?.manual)
        assertTrue(BundleNames.forNewFaces(bundle, NamePolicy.NONE) { persons[it] }.values.all { it == null })
    }

    @Test
    fun twoBundleFacesOnOneLocalFaceAreNotPaired() {
        val one = listOf(11L to listOf(RelBox(0.10f, 0.20f, 0.80f, 0.50f)))
        val wide = listOf(
            BundleFace(RelBox(0.10f, 0.20f, 0.75f, 0.50f), 0.9f, familyUuid = "u-mama"),
            BundleFace(RelBox(0.12f, 0.20f, 0.80f, 0.50f), 0.9f, familyUuid = "u-ocko"),
        )
        assertTrue(BundleNames.pair(wide, one, FaceMatch.MIN_IOU).isEmpty())
    }

    @Test
    fun ignoredAndUnknownPersonsAreSkipped() {
        val faces = listOf(
            BundleFace(mama, 0.9f, familyUuid = "u-mama", manual = true, ignored = true),
            BundleFace(ocko, 0.9f, familyUuid = "u-neznamy", manual = true),
        )
        val pairs = BundleNames.pair(faces, local, FaceMatch.MIN_IOU)
        assertTrue(BundleNames.writes(faces, pairs, NamePolicy.FULL, { persons[it] }, { null }).isEmpty())
    }
}
