package org.fossify.gallery.clip

import android.content.Context

// CLIP sémantické hľadanie: text dopyt -> 512-dim vektor -> kosínus voči uloženým obrázkovým vektorom.
// Enkodér (text session) sa drží cachovaný (rýchle opakované hľadanie). Vektory sa načítajú raz a
// obnovia, keď pribudnú nové (podľa počtu). Ak modely nie sú stiahnuté, vráti prázdne (bez záťaže).
object ClipSearch {
    @Volatile
    private var enc: ClipEncoder? = null

    @Volatile
    private var cache: List<Pair<String, FloatArray>>? = null

    @Volatile
    private var cacheSize = -1

    fun available(context: Context): Boolean = ClipModels.textualPresent(context)

    private fun encoder(context: Context): ClipEncoder {
        return enc ?: synchronized(this) {
            enc ?: ClipEncoder(context.applicationContext).also { enc = it }
        }
    }

    private fun vectors(context: Context): List<Pair<String, FloatArray>> {
        val dao = ClipDatabase.getInstance(context).ClipDao()
        val cnt = try { dao.count() } catch (e: Throwable) { 0 }
        val c = cache
        if (c != null && cnt == cacheSize) return c
        val all = try { dao.getAll() } catch (e: Throwable) { emptyList() }
        val list = all.map { it.path to ClipEncoder.toFloats(it.embedding) }
        cache = list
        cacheSize = cnt
        return list
    }

    // vráti cesty fotiek najbližšie k dopytu (SK sa preloží do EN), nad prahom, top-N.
    fun search(context: Context, query: String, topN: Int = 60, minCos: Float = 0.20f): List<String> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        if (!ClipModels.textualPresent(context)) return emptyList()
        return try {
            val e = encoder(context)
            if (!e.textualReady()) return emptyList()
            val en = SkEnDict.translate(q)
            val qv = e.encodeText(en) ?: return emptyList()
            val vecs = vectors(context)
            if (vecs.isEmpty()) return emptyList()
            vecs.asSequence()
                .map { (path, v) -> path to ClipEncoder.cosine(qv, v) }
                .filter { it.second >= minCos }
                .sortedByDescending { it.second }
                .take(topN)
                .map { it.first }
                .toList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun invalidate() {
        cache = null
        cacheSize = -1
    }
}
