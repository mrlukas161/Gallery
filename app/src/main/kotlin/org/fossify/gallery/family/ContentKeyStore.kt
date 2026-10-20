package org.fossify.gallery.family

import java.io.File
import java.io.IOException

// Lenivá cache obsahových kľúčov nad tabuľkou content_keys (family.db).
// Kľúč sa prepočíta len vtedy, keď sa zmení veľkosť alebo mtime súboru; inak sa vráti uložený.
// Volá sa len z pozadia (číta súbor a DB). Nepočíta sa pri štarte appky, ale pri prvom poslaní,
// synchronizácii alebo pri nabíjaní (spec, časť 3).
class ContentKeyStore(
    private val dao: ContentKeyDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Vráti kľúč pre súbor na ceste `path`, alebo null, keď súbor neexistuje alebo sa nedá prečítať.
    fun keyFor(path: String): String? {
        val file = File(path)
        if (!file.isFile) return null
        val size = file.length()
        val mtime = file.lastModified()
        val cached = dao.get(path)
        if (cached != null && cached.size == size && cached.mtime == mtime) return cached.contentKey

        val key = try {
            ContentKey.compute(file)
        } catch (ignored: IOException) {
            return null
        }
        dao.upsert(ContentKeyEntity(path, size, mtime, key, clock()))
        return key
    }

    // Uložený kľúč bez čítania súboru (null = ešte nepočítaný alebo zastaraný podľa size/mtime).
    fun cachedKeyFor(path: String, size: Long, mtime: Long): String? =
        dao.get(path)?.takeIf { it.size == size && it.mtime == mtime }?.contentKey

    // Všetky známe cesty s daným kľúčom (rovnaká fotka môže byť v knižnici viackrát).
    fun pathsFor(contentKey: String): List<String> = dao.findByContentKey(contentKey).map { it.path }

    fun forget(path: String) = dao.delete(path)
}
