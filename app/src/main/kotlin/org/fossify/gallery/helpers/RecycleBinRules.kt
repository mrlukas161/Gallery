package org.fossify.gallery.helpers

// Čo smie „Vysypať Kôš“ zmazať (revízia 11/2026, N1). Čistá logika bez Androidu (RecycleBinRulesTest).
//
// Kôš je kvôli knižnici commons v koreni filesDir (recycleBinPath = filesDir.absolutePath, používa ho
// ~20 miest appky aj commons, PcDeleter, CompareTrash, web /api/bin): kópia fotky
// /storage/emulated/0/DCIM/a.jpg leží v filesDir/storage/emulated/0/DCIM/a.jpg a v mediaDB je ako
// „recycle_bin/storage/emulated/0/DCIM/a.jpg“ s deleted_ts != 0. V tom istom koreni má appka aj vlastné,
// ťažko nahraditeľné dáta (CLIP modely, tessdata, výrezy z Picasy …), preto sa koreň NIKDY nemaže celý.
//
// Smie sa zmazať len:
//  1. presná kópia každej položky Koša z mediaDB (cesta bez „..“, aspoň 2 úrovne, nie v dátach appky);
//  2. celé podstromy koreňov úložísk (storage, sdcard, mnt) — appka tam nič vlastné nemá, takže sú to
//     len kópie z Koša vrátane sirôt bez záznamu v mediaDB;
//  3. dočasné súbory otáčania „.tmp_…“ priamo v koreni (Activity.rotateFile).
// Všetko ostatné v koreni (aj neznáme priečinky, ktoré pribudnú v budúcnosti) ostáva.
object RecycleBinRules {
    // korene úložísk, pod ktorými appka nemá vlastné dáta → celý podstrom patrí Košu
    val STORAGE_ROOTS = setOf("storage", "sdcard", "mnt")

    // vlastné dáta appky v filesDir (ClipModels, ClipMlModels, OcrEngine, PicasaImportService) — poistka
    val APP_DATA = setOf("clip", "clip_ml", "tessdata", "picasa_crops")

    const val TMP_PREFIX = ".tmp_"

    // relative = cesta voči koreňu Koša; recursive = celý podstrom (inak len jeden súbor)
    data class Target(val relative: String, val recursive: Boolean)

    // deletedDbPaths = full_path položiek Koša z mediaDB (s predponou dbPrefix = RECYCLE_BIN),
    // topLevel = názvy položiek priamo v koreni Koša (filesDir.list())
    fun plan(deletedDbPaths: Collection<String>, topLevel: Collection<String>, dbPrefix: String): List<Target> {
        val sweep = topLevel.filter { it in STORAGE_ROOTS }.distinct().sorted()
        val result = LinkedHashMap<String, Target>()
        sweep.forEach { result[it] = Target(it, true) }
        topLevel.filter { it.startsWith(TMP_PREFIX) && it.length > TMP_PREFIX.length && !it.contains('/') }
            .sorted().forEach { result[it] = Target(it, false) }
        deletedDbPaths.forEach { dbPath ->
            val rel = entryOf(dbPath, dbPrefix) ?: return@forEach
            val first = rel.substringBefore('/')
            // podstrom koreňa úložiska sa maže celý vyššie
            if (first in sweep) return@forEach
            if (!result.containsKey(rel)) result[rel] = Target(rel, first in STORAGE_ROOTS)
        }
        return result.values.toList()
    }

    // relatívna cesta kópie v Koši, null = položka sa nesmie mazať (podozrivá cesta alebo dáta appky)
    fun entryOf(dbPath: String, dbPrefix: String): String? {
        if (!dbPath.startsWith(dbPrefix)) return null
        val segments = dbPath.removePrefix(dbPrefix).split('/').filter { it.isNotEmpty() }
        if (segments.size < MIN_DEPTH) return null
        if (segments.any { it == "." || it == ".." || it.contains('\\') }) return null
        if (segments.first() in APP_DATA) return null
        return segments.joinToString("/")
    }

    // poistka pred samotným mazaním: cieľ musí ležať vnútri koreňa (kanonické cesty) a nesmie byť v dátach appky
    fun isInsideBin(rootCanonical: String, targetCanonical: String): Boolean {
        val root = rootCanonical.trimEnd('/')
        if (root.isEmpty() || !targetCanonical.startsWith("$root/")) return false
        val rel = targetCanonical.removePrefix("$root/")
        return rel.isNotEmpty() && rel.substringBefore('/') !in APP_DATA
    }

    private const val MIN_DEPTH = 2
}
