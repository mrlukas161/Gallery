package org.fossify.gallery.family

import android.content.Context
import android.provider.MediaStore
import org.fossify.gallery.faces.IndexScope
import org.fossify.gallery.faces.PhashDatabase
import org.fossify.gallery.faces.PhashIndexer
import java.io.File

// Hľadanie fotky z balíka v knižnici prijímateľa (spec 2.1): najprv obsahový kľúč, potom záloha FuzzyMatch.
// Lacné: obsahový kľúč sa počíta len pre fotky s rovnakou veľkosťou súboru (MediaStore SIZE), záloha
// skúša len nedávno pridané fotky. Nič mimo IndexScope. Volá sa len z pozadia.
class LocalPhotoFinder(context: Context) {
    private val app = context.applicationContext
    private val keys = ContentKeyStore(FamilyDatabase.getInstance(app).contentKeyDao())
    private val scope = IndexScope(app)

    class Found(val path: String, val match: BundleMatch, val localPhash: Long? = null)

    fun find(bundle: PhotoBundle, nowMs: Long = System.currentTimeMillis()): Found? =
        exact(bundle)?.let { Found(it, BundleMatch.EXACT) } ?: fuzzy(bundle, nowMs)

    // fotka s rovnakým obsahovým kľúčom (aj presunutá alebo poslaná cez Quick Share bez zmeny)
    fun exact(bundle: PhotoBundle): String? {
        val known = keys.pathsFor(bundle.contentKey)
        val bySize = pathsWithSize(bundle.size)
        return (known + bySize).distinct().firstOrNull { path ->
            scope.allows(path) && File(path).isFile && keys.keyFor(path) == bundle.contentKey
        }
    }

    // záloha: nedávno pridané fotky (napr. z WhatsAppu) s podobným pHash a dátumom; nejednoznačné = nič
    private fun fuzzy(bundle: PhotoBundle, nowMs: Long): Found? {
        val target = FuzzyMatch.Fingerprint(bundle.phash ?: return null, bundle.takenAtMs)
        if (!bundle.mime.startsWith("image/")) return null
        val hashes = HashMap<String, Long>()
        val candidates = recentImages(nowMs - BundleRules.FUZZY_WINDOW_MS).mapNotNull { path ->
            val fp = fingerprint(path) ?: return@mapNotNull null
            hashes[path] = fp.phash
            path to fp
        }.toMap()
        val best = FuzzyMatch.findBest(target, candidates) ?: return null
        return Found(best, BundleMatch.FUZZY, hashes[best])
    }

    // pHash prijímateľovej kópie (z indexu, inak vypočítaný — lacné 64 px dekódovanie) a dátum z EXIF
    fun fingerprint(path: String): FuzzyMatch.Fingerprint? {
        if (!scope.allows(path)) return null
        val stored = guarded { PhashDatabase.getInstance(app).PhashDao().getByPath(path) }?.hashValue
        val hash = stored?.takeIf { it != 0L } ?: guarded { PhashIndexer.computeHash(path) }
        return hash?.takeIf { it != 0L }?.let { FuzzyMatch.Fingerprint(it, guarded { exifTakenAt(path) }) }
    }

    private fun pathsWithSize(size: Long): List<String> = query(
        "${MediaStore.MediaColumns.SIZE} = ?",
        arrayOf(size.toString()),
        null,
        MAX_SAME_SIZE,
        MediaStore.Files.getContentUri("external"),
    )

    private fun recentImages(sinceMs: Long): List<String> = query(
        "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
        arrayOf((sinceMs / MS_PER_SECOND).toString()),
        "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        MAX_FUZZY_CANDIDATES,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    )

    private fun query(
        selection: String,
        args: Array<String>,
        order: String?,
        limit: Int,
        uri: android.net.Uri,
    ): List<String> = guarded {
        @Suppress("DEPRECATION")
        val data = MediaStore.MediaColumns.DATA
        val out = ArrayList<String>()
        app.contentResolver.query(uri, arrayOf(data), selection, args, order)?.use { c ->
            while (out.size < limit && c.moveToNext()) c.getString(0)?.let { out.add(it) }
        }
        out
    }.orEmpty()

    @Suppress("TooGenericExceptionCaught")
    private fun <T> guarded(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        android.util.Log.w(TAG, "hľadanie fotky zlyhalo", e)
        null
    }

    private companion object {
        const val TAG = "LocalPhotoFinder"
        const val MS_PER_SECOND = 1000L
        const val MAX_SAME_SIZE = 50
        const val MAX_FUZZY_CANDIDATES = 300
    }
}
