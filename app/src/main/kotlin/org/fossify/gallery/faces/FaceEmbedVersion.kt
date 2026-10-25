package org.fossify.gallery.faces

import android.content.Context

// Verzia odtlačkov ULOŽENÝCH vo faces.db (a vzorov, ktoré sa používajú). Bez zmeny schémy faces.db —
// stav je v SharedPreferences „galeria_faces", hotové tváre v malej DB faces_v2.db (FaceV2Staging).
//
// Priebeh: aktualizácia appky z v1 → stav PENDING (uložené sú v1 vektory, MODEL_ID = v1). Prepočet
// pri nabíjaní (FaceReembedV2) počíta v2 vektory bokom a na konci ich naraz zapíše do faces.db
// („prepnutie"): najprv sa tu zapíše verzia 2 a čas prepnutia (MODEL_ID odvtedy = v2), potom sa
// v jednej transakcii prepíšu odtlačky a v ďalšej sa odložia v1 vzory. Keď appka spadne uprostred,
// príznak „dokončiť" zostane a prepnutie sa pri ďalšom behu zopakuje (je idempotentné).
// Nová inštalácia (ešte bez faces.db a people.db) začína rovno vo v2.
@Suppress("TooManyFunctions") // malé prístupové funkcie k jednému stavu
object FaceEmbedVersion {
    private const val PREFS = "galeria_faces"
    private const val KEY_VERSION = "face_embed_version"
    private const val KEY_SWITCHED_AT = "face_embed_v2_switched_at"
    private const val KEY_FINALIZE = "face_embed_v2_finalize"
    private const val KEY_PICASA_REIMPORT = "face_embed_v2_picasa_reimport"

    private const val V1 = 1
    private const val V2 = 2

    // E1: hodnota faces.embed_version pre odtlačok z aktuálnej cesty (FaceEmbedder.PIPELINE_MODEL_ID)
    const val PIPELINE_VERSION = V2

    // stav v pamäti pre MODEL_ID (číta ho aj rodinné prepojenie bez Contextu); null = ešte nenačítané
    @Volatile
    private var storedV2: Boolean? = null

    // App.onCreate — pred akýmkoľvek prenosom alebo zálohou odtlačkov
    fun init(context: Context) {
        try {
            load(context)
        } catch (ignored: Throwable) {
        }
    }

    // E1: verzia odtlačkov uložených vo faces.db (1/2) — pre faces.embed_version; pred načítaním stavu v1
    fun storedVersion(): Int = if (storedV2 == true) V2 else V1

    // MODEL_ID uložených odtlačkov; pred načítaním stavu konzervatívne v1
    fun storedModelId(): String =
        if (storedV2 == true) EmbedVersionRules.MODEL_ID_V2 else EmbedVersionRules.MODEL_ID_V1

    // Treba ešte prepočet alebo dokončenie prepnutia? Kým áno: nové tváre sa ukladajú bez odtlačku
    // (v2 vektor čaká vo faces_v2.db), import z Picasy a „Prepracovať tváre" počkajú.
    fun isPending(context: Context): Boolean {
        val p = prefs(context)
        return load(context) != V2 || p.getBoolean(KEY_FINALIZE, false)
    }

    fun isFinalizing(context: Context): Boolean = prefs(context).getBoolean(KEY_FINALIZE, false)

    // čas prepnutia na v2 (null = ešte neprebehlo); vzory a importy z Picasy pred ním sú v1
    fun switchedAt(context: Context): Long? {
        if (load(context) != V2) return null
        return prefs(context).getLong(KEY_SWITCHED_AT, 0L)
    }

    fun needsPicasaReimport(context: Context): Boolean =
        !isPending(context) && prefs(context).getBoolean(KEY_PICASA_REIMPORT, false)

    fun clearPicasaReimport(context: Context) {
        prefs(context).edit().putBoolean(KEY_PICASA_REIMPORT, false).apply()
    }

    // 1. krok prepnutia — synchrónne (commit), aby po páde bolo jasné, že treba dokončiť
    internal fun markSwitchStarted(context: Context, at: Long) {
        prefs(context).edit()
            .putInt(KEY_VERSION, V2)
            .putLong(KEY_SWITCHED_AT, at)
            .putBoolean(KEY_FINALIZE, true)
            .commit()
        storedV2 = true
    }

    internal fun markSwitchFinished(context: Context, picasaParked: Boolean) {
        val edit = prefs(context).edit().putBoolean(KEY_FINALIZE, false)
        if (picasaParked) edit.putBoolean(KEY_PICASA_REIMPORT, true)
        edit.commit()
    }

    private fun load(context: Context): Int {
        val p = prefs(context)
        var v = p.getInt(KEY_VERSION, 0)
        if (v == 0) {
            val app = context.applicationContext
            val existing = app.getDatabasePath("faces.db").exists() || app.getDatabasePath("people.db").exists()
            v = if (existing) V1 else V2
            val edit = p.edit().putInt(KEY_VERSION, v)
            if (v == V2) edit.putLong(KEY_SWITCHED_AT, System.currentTimeMillis())
            edit.commit()
        }
        storedV2 = v == V2
        return v
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
