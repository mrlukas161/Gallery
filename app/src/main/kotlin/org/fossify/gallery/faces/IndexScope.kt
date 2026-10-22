package org.fossify.gallery.faces

import android.content.Context
import org.fossify.commons.extensions.getParentPath
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.isThisOrParentExcluded
import org.fossify.gallery.extensions.isThisOrParentIncluded

// Rozsah spracovania knižnice pre OCR, QR, polohu, podobné fotky a CLIP: NIE vylúčené priečinky
// a NIE priečinky zamknuté PIN-om/heslom. Inak by sa text, QR obsah, poloha a hľadanie podľa
// obsahu dostali do Dokumentov, na mapu a do vyhľadávania BEZ odomknutia priečinka.
// Pravidlá ako v hlavnej galérii (shouldFolderBeVisible): presne vylúčený priečinok -> nie;
// zahrnutý priečinok alebo jeho rodič -> áno; vylúčený rodič -> nie. Dočasné „zobraziť vylúčené"
// sa ignoruje — index je trvalý. Rozhodnutie sa cachuje per priečinok (bývajú v ňom stovky fotiek).
// Nie je thread-safe — jedna inštancia na jeden beh indexera.
class IndexScope(context: Context) {
    private val config = context.applicationContext.config
    private val excluded: MutableSet<String> = HashSet(config.excludedFolders)
    private val included: MutableSet<String> = HashSet(config.includedFolders)
    private val byFolder = HashMap<String, Boolean>()

    fun allows(path: String): Boolean {
        val folder = path.getParentPath()
        return byFolder.getOrPut(folder) { folderAllowed(folder) }
    }

    // Zmaže z indexu riadky fotiek, ktoré do rozsahu už nepatria (priečinok medzičasom zamknutý
    // alebo vylúčený), a vráti zvyšné spracované cesty. Tieto indexy sú prestavateľné — po
    // odomknutí sa fotky spracujú znova. (Tváre sa takto NIKDY nemažú.)
    fun keepAllowed(indexed: List<String>, delete: (List<String>) -> Unit): HashSet<String> {
        val kept = HashSet<String>(indexed.size)
        val blocked = ArrayList<String>()
        for (path in indexed) {
            if (allows(path)) kept.add(path) else blocked.add(path)
        }
        blocked.chunked(400).forEach { chunk ->
            try {
                delete(chunk)
            } catch (ignored: Throwable) {
            }
        }
        return kept
    }

    // pri chybe čítania nastavení radšej nespracovať (súkromie má prednosť)
    private fun folderAllowed(folder: String): Boolean = try {
        when {
            config.isFolderProtected(folder) -> false
            excluded.contains(folder) -> false
            folder.isThisOrParentIncluded(included) -> true
            folder.isThisOrParentExcluded(excluded) -> false
            else -> true
        }
    } catch (e: Throwable) {
        false
    }
}
