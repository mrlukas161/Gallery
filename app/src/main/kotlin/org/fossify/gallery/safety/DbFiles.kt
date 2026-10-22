package org.fossify.gallery.safety

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Pomenovanie súborov pri ochrane databáz (A1, A3). Čistá logika bez Androidu (DbFilesTest).
//
// Poškodená DB sa nikdy nemaže: `people.db` → `people.db.corrupt-20261021-031500` a k nej aj
// `-wal`, `-shm` a `-journal` (`people.db.corrupt-20261021-031500-wal`), aby SQLite pri prípadnej
// ručnej záchrane našiel celý pár. Pri núdzovej obnove zo zálohy sa súčasná DB rovnako premenuje
// na `people.db.replaced-<čas>`.
object DbFiles {
    // DB osôb, ktoré sa zálohujú (A2 Auto Backup, A3 automatická záloha) — nenahraditeľné
    const val PEOPLE = "people.db"
    const val EXTRAS = "extras.db"
    const val FAMILY = "family.db"

    // faces.db je chránená pred zmazaním (A1), ale nezálohuje sa (dá sa nanovo spracovať)
    const val FACES = "faces.db"

    val BACKED_UP = listOf(PEOPLE, EXTRAS, FAMILY)
    val PROTECTED = listOf(PEOPLE, EXTRAS, FAMILY, FACES)

    // sprievodné súbory SQLite, ktoré sa premenúvajú spolu s hlavným súborom
    val SIDE_SUFFIXES = listOf("-wal", "-shm", "-journal")

    const val CORRUPT = "corrupt"
    const val REPLACED = "replaced"
    const val RESTORE_PENDING_SUFFIX = ".restore-pending"

    fun stamp(millis: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(millis))

    // `<cesta>.<druh>-<čas>`; suffix = "" pre hlavný súbor alebo "-wal" a pod.
    fun aside(path: String, kind: String, stamp: String, suffix: String = ""): String = "$path.$kind-$stamp$suffix"

    // názov súboru z cesty (bez priečinka)
    fun fileName(path: String): String = path.substringAfterLast('/')

    // A4: DB, ktorých verzia na disku je vyššia než verzia, ktorú pozná appka (null = súbor nie je)
    fun newer(onDisk: Map<String, Int?>, expected: Map<String, Int>): List<String> =
        expected.keys.filter { name -> (onDisk[name] ?: 0) > expected.getValue(name) }.sorted()

    // hlavička SQLite súboru („SQLite format 3\0“)
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun isSqliteHeader(head: ByteArray): Boolean =
        head.size >= SQLITE_MAGIC.size && SQLITE_MAGIC.indices.all { head[it] == SQLITE_MAGIC[it] }

    val SQLITE_HEADER_SIZE: Int get() = SQLITE_MAGIC.size
}
