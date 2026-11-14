package org.fossify.gallery.safety

import java.util.Locale

// Obľúbené v automatickej zálohe (revízia 11/2026, N13). Čistá logika bez Androidu (AutoBackupHealthTest).
//
// Tabuľka favorites je v gallery.db (prestavateľná, fallbackToDestructiveMigration) — schéma sa nemení,
// do ZIP-u automatickej zálohy sa pridá textový súbor ENTRY s jednou cestou na riadok (rovnaký formát
// ako ručný export obľúbených v Nastaveniach). Obnova len dopĺňa chýbajúce obľúbené k fotkám, ktoré
// na telefóne existujú; nič neodoberá. Fotky zo zamknutých priečinkov sa do zálohy nedávajú (súbor leží
// v Documents a súkromie má prednosť).
object FavoritesBackup {
    const val ENTRY = "oblubene.txt"

    fun encode(paths: Collection<String>): String = clean(paths).joinToString("") { it + "\n" }

    fun decode(text: String): List<String> = clean(text.split('\n'))

    // cesty zo zálohy, ktoré v súčasných obľúbených chýbajú (porovnanie bez ohľadu na veľkosť písmen
    // ako COLLATE NOCASE v FavoritesDao) a ktorých fotka existuje
    fun missing(backup: Collection<String>, current: Collection<String>, exists: (String) -> Boolean): List<String> {
        val have = current.mapTo(HashSet()) { key(it) }
        return clean(backup).filter { key(it) !in have && exists(it) }
    }

    private fun clean(paths: Collection<String>): List<String> {
        val seen = HashSet<String>()
        return paths.map { it.removeSuffix("\r") }
            .filter { it.startsWith("/") && !it.contains('\n') && seen.add(key(it)) }
    }

    private fun key(path: String) = path.lowercase(Locale.ROOT)
}
