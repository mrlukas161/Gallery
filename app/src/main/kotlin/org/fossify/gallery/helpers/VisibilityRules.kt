package org.fossify.gallery.helpers

import java.util.Locale

// Jeden spoločný filter viditeľnosti fotiek — čistá logika bez Androidu (testuje ju VisibilityRulesTest).
// Android časť (Visibility) len načíta nastavenia a zistí .nomedia na disku.
//
// Pravidlo súkromia: zamknuté (PIN/heslo) a vylúčené priečinky sa bez odomknutia nesmú ukázať ani otvoriť
// nikde mimo hlavnej obrazovky (Ľudia, hľadanie, Spomienky, Špeciálne, Dokumenty, mapa, Obľúbené…).
// Skryté položky (priečinok s .nomedia, priečinok alebo súbor začínajúci bodkou) sa správajú ako v hlavnej
// galérii — ukážu sa len pri zapnutom „zobraziť skryté".
//
//  • ZAMKNUTÝ priečinok skryje aj všetky svoje podpriečinky (prísnejšie než hlavná obrazovka — súkromie má prednosť).
//  • VYLÚČENÝ priečinok platí ako v hlavnej galérii: presne vylúčený -> nie; zahrnutý priečinok alebo jeho
//    rodič -> áno; vylúčený rodič -> nie. Porovnáva sa po celých častiach cesty („…/Scr" neskryje „…/Screenshots").
//  • Veľkosť písmen sa ignoruje (úložisko v Androide ju nerozlišuje) a /sdcard = /storage/emulated/0.
//
// allows()  = rozsah spracovania (IndexScope): len zamknuté a vylúčené, skrytie sa nerieši (index je trvalý).
// isVisible() = zobrazenie: rozsah + skrytie.
//
// hasNoMedia(priečinok) dostane cestu v pôvodnom tvare; výsledok sa pamätá per priečinok.
// Nie je thread-safe — Android obal (Visibility) ho volá pod zámkom.
class VisibilityRules(
    lockedFolders: Collection<String>,
    excludedFolders: Collection<String>,
    includedFolders: Collection<String>,
    private val showHidden: Boolean,
    private val hasNoMedia: (String) -> Boolean = { false },
    // dodatočná presná kontrola zamknutia (nastavenia appky) — pre istotu, ak by zoznam zamknutých nebol úplný
    private val isLockedExact: (String) -> Boolean = { false },
) {
    // normalizovaná cesta -> pôvodný zápis (PIN sa pýta pre pôvodný zápis priečinka)
    private val locked = HashMap<String, String>()
    private val excluded = HashSet<String>()
    private val included = HashSet<String>()

    private val lockedByFolder = HashMap<String, String?>()
    private val scopeByFolder = HashMap<String, Boolean>()
    private val visibleByFolder = HashMap<String, Boolean>()
    private val noMediaByFolder = HashMap<String, Boolean>()

    init {
        lockedFolders.forEach { f -> normalize(f)?.let { locked.putIfAbsent(it, f.trimEnd('/')) } }
        excludedFolders.forEach { f -> normalize(f)?.let { excluded.add(it) } }
        includedFolders.forEach { f -> normalize(f)?.let { included.add(it) } }
    }

    // ---------- zamknutie ----------

    fun isLocked(path: String): Boolean = lockedFolderOf(path) != null

    // zamknutý priečinok (on sám alebo jeho predok), v ktorom fotka leží; null = nie je zamknutá
    fun lockedFolderOf(path: String): String? = lockedFolder(parentOf(path))

    // priečinok aj predkov porovnáva v normalizovanom tvare, presnú kontrolu nastavení robí v pôvodnom zápise
    fun lockedFolder(folder: String): String? {
        val norm = normalize(folder) ?: return null
        return lockedByFolder.getOrPut(norm) {
            var cur = norm
            var curOriginal = folder.trimEnd('/')
            var found: String? = null
            while (found == null && cur.isNotEmpty()) {
                found = locked[cur] ?: curOriginal.takeIf { isLockedExactSafe(it) }
                cur = parentOf(cur)
                curOriginal = parentOf(curOriginal)
            }
            found
        }
    }

    // pri chybe čítania nastavení radšej zamknuté (súkromie má prednosť)
    private fun isLockedExactSafe(folder: String): Boolean = try {
        folder.isNotEmpty() && isLockedExact(folder)
    } catch (ignored: RuntimeException) {
        true
    }

    // ---------- rozsah (zamknuté + vylúčené) ----------

    fun allows(path: String): Boolean = allowsFolder(parentOf(path))

    fun allowsFolder(folder: String): Boolean {
        val norm = normalize(folder) ?: return false
        return scopeByFolder.getOrPut(norm) {
            when {
                lockedFolder(folder) != null -> false
                excluded.contains(norm) -> false
                isThisOrParent(norm, included) -> true
                isThisOrParent(norm, excluded) -> false
                else -> true
            }
        }
    }

    // ---------- zobrazenie (rozsah + skryté) ----------

    fun isVisible(path: String): Boolean {
        if (normalize(path) == null) return false
        if (!showHidden && fileName(path).startsWith('.')) return false
        return isFolderVisible(parentOf(path))
    }

    fun isFolderVisible(folder: String): Boolean {
        val norm = normalize(folder) ?: return false
        return visibleByFolder.getOrPut(norm) {
            allowsFolder(folder) && (showHidden || !isHiddenFolder(folder.trimEnd('/'), norm))
        }
    }

    // skrytý priečinok ako v hlavnej galérii (shouldFolderBeVisible): bodka v názve, vlastné .nomedia
    // (okrem presne zahrnutého priečinka), bodka alebo .nomedia u predka (okrem zahrnutého podstromu)
    private fun isHiddenFolder(original: String, norm: String): Boolean {
        if (fileName(norm).startsWith('.')) return true
        if (included.contains(norm)) return false
        if (noMedia(original)) return true
        if (isThisOrParent(norm, included)) return false
        if (norm.contains("/.")) return true
        var cur = parentOf(original)
        while (cur.isNotEmpty()) {
            if (noMedia(cur)) return true
            cur = parentOf(cur)
        }
        return false
    }

    private fun noMedia(folder: String): Boolean {
        val norm = normalize(folder) ?: return false
        return noMediaByFolder.getOrPut(norm) {
            try {
                hasNoMedia(folder)
            } catch (ignored: RuntimeException) {
                false
            }
        }
    }

    companion object {
        private val STORAGE_ALIASES = listOf("/sdcard", "/mnt/sdcard", "/storage/self/primary")
        private const val PRIMARY_STORAGE = "/storage/emulated/0"
        private val MULTI_SLASH = Regex("/{2,}")

        // porovnávací tvar cesty: malé písmená, bez koncovej lomky a zdvojených lomiek, /sdcard = /storage/emulated/0;
        // null = nie je absolútna cesta (taká sa nikdy neukáže)
        fun normalize(path: String): String? {
            val trimmed = path.trim()
            if (!trimmed.startsWith("/")) return null
            var p = trimmed.replace(MULTI_SLASH, "/").trimEnd('/').lowercase(Locale.ROOT)
            for (alias in STORAGE_ALIASES) {
                if (p == alias || p.startsWith("$alias/")) {
                    p = PRIMARY_STORAGE + p.substring(alias.length)
                    break
                }
            }
            return p.ifEmpty { "/" }
        }

        fun parentOf(path: String): String {
            val p = path.trimEnd('/')
            val i = p.lastIndexOf('/')
            return if (i <= 0) "" else p.substring(0, i)
        }

        fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/')

        // priečinok je sám v množine, alebo je v nej niektorý jeho predok (po celých častiach cesty)
        fun isThisOrParent(norm: String, set: Set<String>): Boolean {
            if (set.isEmpty()) return false
            var cur = norm
            while (cur.isNotEmpty()) {
                if (set.contains(cur)) return true
                cur = parentOf(cur)
            }
            return false
        }
    }
}
