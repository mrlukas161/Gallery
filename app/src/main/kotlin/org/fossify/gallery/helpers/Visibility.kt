package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.helpers.NOMEDIA
import org.fossify.commons.helpers.PROTECTED_FOLDER_TYPE
import org.fossify.commons.helpers.PROTECTION_NONE
import org.fossify.gallery.extensions.config
import java.io.File

// Spoločný filter viditeľnosti fotiek pre všetky obrazovky mimo bežných priečinkov (Ľudia, hľadanie,
// Spomienky, Špeciálne, Dokumenty, mapa, Obľúbené, prehliadač otvorený bez PIN-u…). Tenký Android obal
// nad VisibilityRules: načíta zamknuté, vylúčené a zahrnuté priečinky z nastavení a .nomedia zistí na disku.
//
// Filtrovať sa má pri ZOBRAZENÍ — indexy (OCR, poloha, tváre…) môžu obsahovať fotky z priečinka zamknutého
// až po spracovaní. Jedna inštancia = jedno načítanie obrazovky (nastavenia sa čítajú pri vytvorení).
// Volať z vlákna na pozadí — prvé zistenie .nomedia pre priečinok siaha na disk.
class Visibility(context: Context) {
    private val rules: VisibilityRules? = try {
        build(context.applicationContext)
    } catch (ignored: RuntimeException) {
        null // nastavenia sa nedajú prečítať — radšej neukázať nič (súkromie má prednosť)
    }

    @Synchronized
    fun isVisible(path: String): Boolean = rules?.isVisible(path) ?: false

    // rozsah spracovania (bez ohľadu na skrytie) — IndexScope
    @Synchronized
    fun allows(path: String): Boolean = rules?.allows(path) ?: false

    @Synchronized
    fun isLocked(path: String): Boolean = rules?.isLocked(path) ?: true

    // zamknutý priečinok (alebo jeho zamknutý predok), pre ktorý treba PIN; null = fotka nie je zamknutá
    @Synchronized
    fun lockedFolderOf(path: String): String? = rules?.lockedFolderOf(path)

    fun filterPaths(paths: Iterable<String>): List<String> = paths.filter { isVisible(it) }

    companion object {
        private fun build(context: Context): VisibilityRules {
            val config = context.config
            return VisibilityRules(
                lockedFolders = lockedFolders(context),
                excludedFolders = config.excludedFolders,
                includedFolders = config.includedFolders,
                showHidden = config.showHiddenMedia || config.temporarilyShowHidden,
                hasNoMedia = { folder -> File(folder, NOMEDIA).exists() },
                isLockedExact = { folder -> config.isFolderProtected(folder) },
            )
        }

        // všetky priečinky chránené PIN-om/heslom/odtlačkom (commons ich ukladá ako protected_folder_type_<cesta>)
        fun lockedFolders(context: Context): Set<String> {
            val out = HashSet<String>()
            context.getSharedPrefs().all.forEach { (key, value) ->
                if (key.startsWith(PROTECTED_FOLDER_TYPE) && value is Int && value != PROTECTION_NONE) {
                    out.add(key.removePrefix(PROTECTED_FOLDER_TYPE))
                }
            }
            return out
        }
    }
}
