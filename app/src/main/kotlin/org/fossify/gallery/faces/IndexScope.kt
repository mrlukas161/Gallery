package org.fossify.gallery.faces

import android.content.Context
import org.fossify.gallery.helpers.Visibility

// Rozsah spracovania knižnice pre OCR, QR, polohu, podobné fotky a CLIP: NIE vylúčené priečinky
// a NIE priečinky zamknuté PIN-om/heslom (ani ich podpriečinky). Inak by sa text, QR obsah, poloha
// a hľadanie podľa obsahu dostali do Dokumentov, na mapu a do vyhľadávania BEZ odomknutia priečinka.
// Pravidlá sú v spoločnom filtri helpers/VisibilityRules (ako v hlavnej galérii: presne vylúčený
// priečinok -> nie; zahrnutý priečinok alebo jeho rodič -> áno; vylúčený rodič -> nie). Dočasné
// „zobraziť vylúčené" sa ignoruje — index je trvalý; skrytie (.nomedia) rieši až zobrazenie (Visibility).
// Rozhodnutie sa cachuje per priečinok (bývajú v ňom stovky fotiek).
class IndexScope(context: Context) {
    private val visibility = Visibility(context)

    // pri chybe čítania nastavení radšej nespracovať (súkromie má prednosť)
    fun allows(path: String): Boolean = visibility.allows(path)

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
}
