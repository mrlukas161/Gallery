package org.fossify.gallery.clip

import android.content.Context

// CLIP sémantické hľadanie: text dopyt -> 512-dim vektor -> kosínus voči uloženým obrázkovým vektorom.
// Enkodér (text session) sa drží cachovaný (rýchle opakované hľadanie). Vektory sa načítajú raz a
// obnovia, keď pribudnú nové (podľa počtu). Ak modely nie sú stiahnuté, vráti prázdne (bez záťaže).
object ClipSearch {
    @Volatile
    private var enc: ClipEncoder? = null

    @Volatile
    private var mlEnc: ClipMlEncoder? = null

    @Volatile
    private var cache: List<Pair<String, FloatArray>>? = null

    @Volatile
    private var cacheSize = -1

    fun available(context: Context): Boolean =
        ClipModels.textualPresent(context) || ClipMlModels.present(context)

    private fun encoder(context: Context): ClipEncoder {
        return enc ?: synchronized(this) {
            enc ?: ClipEncoder(context.applicationContext).also { enc = it }
        }
    }

    private fun mlEncoder(context: Context): ClipMlEncoder {
        return mlEnc ?: synchronized(this) {
            mlEnc ?: ClipMlEncoder(context.applicationContext).also { mlEnc = it }
        }
    }

    // ML (multilingválny) režim je zapnutý len ak si to používateľ zvolil A model je stiahnutý.
    private fun mlActive(context: Context): Boolean =
        context.getSharedPreferences("galeria_faces", Context.MODE_PRIVATE).getBoolean("clip_ml", false) &&
            ClipMlModels.present(context)

    // uvoľní multilingválny model z pamäte (~500 MB) — volať pri vypnutí režimu
    fun releaseMl() {
        synchronized(this) {
            try {
                mlEnc?.close()
            } catch (ignored: Throwable) {
            }
            mlEnc = null
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
        val ml = mlActive(context)
        if (!ml && !ClipModels.textualPresent(context)) return emptyList()
        return try {
            // ML režim: dopyt priamo (bez SK->EN slovníka). Ak ML zlyhá, skús klasiku (ak je stiahnutá).
            var qv: FloatArray? = null
            if (ml) {
                val me = mlEncoder(context)
                if (me.ready()) qv = me.encodeText(q)
            }
            if (qv == null && ClipModels.textualPresent(context)) {
                val e = encoder(context)
                if (e.textualReady()) qv = e.encodeText(SkEnDict.translate(q))
            }
            val qVec = qv ?: return emptyList()
            val vecs = vectors(context)
            if (vecs.isEmpty()) return emptyList()
            vecs.asSequence()
                .map { (path, v) -> path to ClipEncoder.cosine(qVec, v) }
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
