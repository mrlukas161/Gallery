package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Železné pravidlo (20. 10. 2026): zamknuté a vylúčené priečinky sa bez odomknutia nesmú ukázať v žiadnom
// zozname mimo hlavnej obrazovky. Všetky tieto zoznamy (Ľudia/Hľadať, Spomienky, Špeciálne, Dokumenty
// a Preukazy, mapa, hľadanie, mriežka, Obľúbené, prehliadač, potvrdenie mazania z PC) idú cez VisibilityRules.
class VisibilityRulesTest {
    private val root = "/storage/emulated/0"
    private val locked = "$root/DCIM/Doklady"
    private val excluded = "$root/Pictures/Scr"
    private val includedInExcluded = "$root/WhatsApp/Media"

    private fun rules(
        showHidden: Boolean = false,
        noMedia: Set<String> = emptySet(),
        lockedFolders: Collection<String> = listOf(locked),
        excludedFolders: Collection<String> = listOf(excluded, "$root/WhatsApp"),
        includedFolders: Collection<String> = listOf(includedInExcluded),
        isLockedExact: (String) -> Boolean = { false },
    ) = VisibilityRules(
        lockedFolders = lockedFolders,
        excludedFolders = excludedFolders,
        includedFolders = includedFolders,
        showHidden = showHidden,
        hasNoMedia = { noMedia.contains(it) },
        isLockedExact = isLockedExact,
    )

    // knižnica s fotkami zo všetkých druhov priečinkov — z nej „čítajú" všetky obrazovky
    private val library = listOf(
        "$root/DCIM/Camera/IMG_1.jpg",
        "$locked/op.jpg",
        "$locked/2025/pas.jpg",
        "/sdcard/DCIM/Doklady/vodicak.jpg",
        "$root/dcim/doklady/MALE.jpg",
        "$root/DCIM/DOKLADY/velke.JPG",
        "$root/DCIM/Doklady2/ok.jpg",
        "$excluded/a.png",
        "$excluded/sub/b.png",
        "$root/PICTURES/scr/c.png",
        "$root/Pictures/Screenshots/ok.png",
        "$root/WhatsApp/Media/ok.jpg",
        "$root/WhatsApp/Backups/zle.jpg",
        "$root/Pictures/Skryte/x.jpg",
        "$root/Pictures/.tajne/y.jpg",
        "$root/Pictures/.z.jpg",
    )

    @Test
    fun lockedAndExcludedFoldersNeverAppearInAnyList() {
        val visible = rules(noMedia = setOf("$root/Pictures/Skryte")).let { r -> library.filter { r.isVisible(it) } }
        assertEquals(
            listOf(
                "$root/DCIM/Camera/IMG_1.jpg",
                "$root/DCIM/Doklady2/ok.jpg",
                "$root/Pictures/Screenshots/ok.png",
                "$root/WhatsApp/Media/ok.jpg",
            ),
            visible,
        )
    }

    @Test
    fun lockedFolderStaysHiddenEvenWithShowHidden() {
        val r = rules(showHidden = true)
        val visible = library.filter { r.isVisible(it) }
        assertTrue(visible.none { it.contains("doklady/", ignoreCase = true) })
        assertTrue(visible.none { it.contains("/scr/", ignoreCase = true) })
        assertTrue(visible.contains("$root/Pictures/.tajne/y.jpg"))
        assertTrue(visible.contains("$root/Pictures/.z.jpg"))
    }

    @Test
    fun lockedFolderIncludesSubfoldersAndIgnoresCaseAndAliases() {
        val r = rules()
        assertTrue(r.isLocked("$locked/op.jpg"))
        assertTrue(r.isLocked("$locked/2025/leto/pas.jpg"))
        assertTrue(r.isLocked("$root/dcim/DOKLADY/op.jpg"))
        assertTrue(r.isLocked("/sdcard/DCIM/Doklady/op.jpg"))
        assertTrue(r.isLocked("$root//DCIM/Doklady//op.jpg"))
        assertFalse(r.isLocked("$root/DCIM/Doklady2/op.jpg"))
        assertFalse(r.isLocked("$root/DCIM/op.jpg"))
        assertFalse(r.allows("$locked/op.jpg"))
        assertFalse(r.allowsFolder("$locked/2025"))
    }

    @Test
    fun lockedFolderOfReturnsTheFolderThatHasThePin() {
        val r = rules(lockedFolders = listOf("$locked/"))
        assertEquals(locked, r.lockedFolderOf("$root/dcim/doklady/2025/pas.jpg"))
        assertNull(r.lockedFolderOf("$root/DCIM/Camera/IMG_1.jpg"))
    }

    @Test
    fun exactLockCheckFromSettingsIsUsedAndFailsClosed() {
        val other = "$root/Download/Banka"
        val fromSettings = rules(lockedFolders = emptyList(), isLockedExact = { it == other })
        assertFalse(fromSettings.isVisible("$other/vypis.jpg"))
        assertFalse(fromSettings.isVisible("$other/2026/vypis.jpg"))
        assertEquals(other, fromSettings.lockedFolderOf("$other/2026/vypis.jpg"))
        assertTrue(fromSettings.isVisible("$root/Download/ok.jpg"))

        val broken = rules(lockedFolders = emptyList(), isLockedExact = { throw IllegalStateException("prefs") })
        assertFalse(broken.isVisible("$root/DCIM/Camera/IMG_1.jpg"))
    }

    @Test
    fun excludedFolderMatchesWholePathSegmentsOnly() {
        val r = rules()
        assertFalse(r.isVisible("$excluded/a.png"))
        assertFalse(r.isVisible("$excluded/deep/er/a.png"))
        assertFalse(r.isVisible("$root/pictures/SCR/a.png"))
        assertTrue(r.isVisible("$root/Pictures/Screenshots/a.png"))
        assertTrue(r.isVisible("$root/Pictures/a.png"))
    }

    @Test
    fun includedSubfolderOfExcludedParentIsVisibleButExactExclusionWins() {
        val r = rules()
        assertTrue(r.isVisible("$includedInExcluded/ok.jpg"))
        assertTrue(r.isVisible("$includedInExcluded/Images/ok.jpg"))
        assertFalse(r.isVisible("$root/WhatsApp/Backups/zle.jpg"))
        assertFalse(r.isVisible("$root/WhatsApp/zle.jpg"))

        val both = rules(excludedFolders = listOf(includedInExcluded), includedFolders = listOf(includedInExcluded))
        assertFalse(both.isVisible("$includedInExcluded/ok.jpg"))
    }

    @Test
    fun lockWinsOverInclusion() {
        val r = rules(includedFolders = listOf(locked, "$root/DCIM"))
        assertFalse(r.isVisible("$locked/op.jpg"))
        assertFalse(r.isVisible("$locked/2025/op.jpg"))
    }

    @Test
    fun hiddenFoldersAndFilesFollowShowHidden() {
        val noMedia = setOf("$root/Pictures/Skryte", "$root/Music")
        val r = rules(noMedia = noMedia)
        assertFalse(r.isVisible("$root/Pictures/Skryte/x.jpg"))
        assertFalse(r.isVisible("$root/Pictures/Skryte/sub/x.jpg")) // .nomedia u predka
        assertFalse(r.isVisible("$root/Music/Obaly/x.jpg"))
        assertFalse(r.isVisible("$root/Pictures/.tajne/y.jpg"))
        assertFalse(r.isVisible("$root/Pictures/.tajne/sub/y.jpg"))
        assertFalse(r.isVisible("$root/Pictures/.z.jpg"))
        assertTrue(r.isVisible("$root/Pictures/Viditelne/x.jpg"))

        val shown = rules(showHidden = true, noMedia = noMedia)
        assertTrue(shown.isVisible("$root/Pictures/Skryte/x.jpg"))
        assertTrue(shown.isVisible("$root/Pictures/.tajne/y.jpg"))
        assertTrue(shown.isVisible("$root/Pictures/.z.jpg"))
    }

    @Test
    fun includedFolderOverridesNoMediaLikeMainScreen() {
        val r = rules(
            noMedia = setOf("$root/Telegram", "$root/Telegram/Images"),
            includedFolders = listOf("$root/Telegram/Images"),
        )
        assertTrue(r.isVisible("$root/Telegram/Images/a.jpg"))
        assertFalse(r.isVisible("$root/Telegram/Video/a.jpg"))
    }

    @Test
    fun scopeForIndexingIgnoresHiddenButNotLockedOrExcluded() {
        val r = rules(noMedia = setOf("$root/Pictures/Skryte"))
        assertTrue(r.allows("$root/Pictures/Skryte/x.jpg"))
        assertTrue(r.allows("$root/Pictures/.tajne/y.jpg"))
        assertFalse(r.allows("$locked/op.jpg"))
        assertFalse(r.allows("$excluded/a.png"))
    }

    @Test
    fun noMediaIsCheckedOncePerFolder() {
        val asked = HashMap<String, Int>()
        val r = VisibilityRules(emptyList(), emptyList(), emptyList(), showHidden = false, hasNoMedia = {
            asked[it] = (asked[it] ?: 0) + 1
            false
        })
        repeat(5) { r.isVisible("$root/DCIM/Camera/IMG_$it.jpg") }
        assertTrue(asked.values.all { it == 1 })
        assertTrue(asked.containsKey("$root/DCIM/Camera"))
    }

    @Test
    fun invalidPathsAreNeverVisible() {
        val r = rules()
        assertFalse(r.isVisible(""))
        assertFalse(r.isVisible("relativna/cesta.jpg"))
        assertFalse(r.isVisible("content://media/external/images/1"))
    }

    @Test
    fun normalizeHandlesAliasesSlashesAndCase() {
        assertEquals("$root/dcim/a", VisibilityRules.normalize("/sdcard/DCIM/a/"))
        assertEquals("$root/dcim/a", VisibilityRules.normalize("/storage/self/primary//DCIM/a"))
        assertEquals("/storage/1234-abcd/dcim", VisibilityRules.normalize("/storage/1234-ABCD/DCIM"))
        assertNull(VisibilityRules.normalize("DCIM"))
    }
}
