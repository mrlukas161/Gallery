package org.fossify.gallery.clip

import android.content.Context
import org.fossify.gallery.helpers.IdleCloser

// CLIP sémantické hľadanie: text dopyt -> 512-dim vektor -> kosínus voči uloženým obrázkovým vektorom.
// Enkodéry (text session, multilingválny model) a vektory fotiek sa držia v pamäti len počas hľadania:
// IDLE_MS po poslednom hľadaní sa zavrú a cache vektorov sa uvoľní (IdleCloser), ďalšie hľadanie ich
// znova lenivo načíta. Zatvorenie nikdy nepreruší prebiehajúce hľadanie (počítadlo používania).
// Ak modely nie sú stiahnuté, vráti prázdne (bez záťaže).
object ClipSearch {
    private const val IDLE_MS = 2 * 60 * 1000L

    // Jedna „relácia“ hľadania: všetko, čo sa po nečinnosti uvoľní naraz. Drží len applicationContext.
    private class Session(private val app: Context) {
        private var enc: ClipEncoder? = null
        private var mlEnc: ClipMlEncoder? = null

        @Volatile
        var cache: List<Pair<String, FloatArray>>? = null

        @Volatile
        var cacheSize = -1

        @Synchronized
        fun encoder(): ClipEncoder = enc ?: ClipEncoder(app).also { enc = it }

        @Synchronized
        fun mlEncoder(): ClipMlEncoder = mlEnc ?: ClipMlEncoder(app).also { mlEnc = it }

        fun invalidate() {
            cache = null
            cacheSize = -1
        }

        // volá IdleCloser, keď sessiu nikto nepoužíva
        @Synchronized
        fun close() {
            enc?.close()
            mlEnc?.close()
            enc = null
            mlEnc = null
            invalidate()
        }
    }

    @Volatile
    private var closer: IdleCloser<Session>? = null

    private fun closer(context: Context): IdleCloser<Session> {
        closer?.let { return it }
        val app = context.applicationContext
        return synchronized(this) {
            closer ?: IdleCloser(IDLE_MS, open = { Session(app) }, close = { it.close() }).also { closer = it }
        }
    }

    fun available(context: Context): Boolean =
        ClipModels.textualPresent(context) || ClipMlModels.present(context)

    // ML (multilingválny) režim je zapnutý len ak si to používateľ zvolil A model je stiahnutý.
    private fun mlActive(context: Context): Boolean =
        context.getSharedPreferences("galeria_faces", Context.MODE_PRIVATE).getBoolean("clip_ml", false) &&
            ClipMlModels.present(context)

    // uvoľní multilingválny model z pamäte (~500 MB) — volať pri vypnutí režimu alebo po stiahnutí
    // nového modelu; zatvorí sa celé hľadanie (hneď, alebo keď dobehne prebiehajúce hľadanie)
    fun releaseMl() {
        release()
    }

    // onTrimMemory: enkodéry aj cache vektorov preč (bezpečné aj počas hľadania)
    fun release() {
        closer?.release()
    }

    private fun vectors(context: Context, s: Session): List<Pair<String, FloatArray>> {
        val dao = ClipDatabase.getInstance(context).ClipDao()
        val cnt = try { dao.count() } catch (e: Throwable) { 0 }
        val c = s.cache
        if (c != null && cnt == s.cacheSize) return c
        val all = try { dao.getAll() } catch (e: Throwable) { emptyList() }
        val list = all.map { it.path to ClipEncoder.toFloats(it.embedding) }
        s.cache = list
        s.cacheSize = cnt
        return list
    }

    // vráti cesty fotiek najbližšie k dopytu (SK sa preloží do EN), nad prahom, top-N.
    fun search(context: Context, query: String, topN: Int = 60, minCos: Float = 0.20f): List<String> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val ml = mlActive(context)
        if (!ml && !ClipModels.textualPresent(context)) return emptyList()
        return try {
            closer(context).use { s -> searchIn(context, s, q, ml, topN, minCos) } ?: emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun searchIn(context: Context, s: Session, q: String, ml: Boolean, topN: Int, minCos: Float): List<String> {
        // ML režim: dopyt priamo (bez SK->EN slovníka). Ak ML zlyhá, skús klasiku (ak je stiahnutá).
        var qv: FloatArray? = null
        if (ml) {
            val me = s.mlEncoder()
            if (me.ready()) qv = me.encodeText(q)
        }
        if (qv == null && ClipModels.textualPresent(context)) {
            val e = s.encoder()
            if (e.textualReady()) qv = e.encodeText(SkEnDict.translate(q))
        }
        val qVec = qv ?: return emptyList()
        val vecs = vectors(context, s)
        if (vecs.isEmpty()) return emptyList()
        return vecs.asSequence()
            .map { (path, v) -> path to ClipEncoder.cosine(qVec, v) }
            .filter { it.second >= minCos }
            .sortedByDescending { it.second }
            .take(topN)
            .map { it.first }
            .toList()
    }

    fun invalidate() {
        closer?.ifOpen { it.invalidate() }
    }
}
