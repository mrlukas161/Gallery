package org.fossify.gallery.safety

import android.content.Context

// Záznamy o poškodených DB (A1) a čakajúcej núdzovej obnove (A3) v súkromných preferenciách.
// Súbor sa do zálohy Androidu NEDÁVA (xml/backup_rules, xml/data_extraction_rules): záznam by na
// inom telefóne ukazoval na súbor, ktorý tam nie je.
class DbSafetyState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Corruption(val dbName: String, val asideName: String, val at: Long)

    // volá sa z onCorruption (ľubovoľné vlákno) → commit, aby záznam prežil aj následný pád
    @Synchronized
    fun recordCorruption(dbName: String, asideName: String, at: Long) {
        val set = prefs.getStringSet(KEY_CORRUPT, null)?.toMutableSet() ?: HashSet()
        set.add(listOf(dbName, asideName, at.toString()).joinToString(SEP))
        prefs.edit().putStringSet(KEY_CORRUPT, set).commit()
    }

    fun corruptions(): List<Corruption> = prefs.getStringSet(KEY_CORRUPT, null).orEmpty().mapNotNull { e ->
        val parts = e.split(SEP)
        if (parts.size != PARTS) return@mapNotNull null
        Corruption(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L)
    }.sortedBy { it.at }

    @Synchronized
    fun clearCorruptions() {
        prefs.edit().remove(KEY_CORRUPT).commit()
    }

    // núdzová obnova surovej kópie: názvy DB, ktorých `<db>.restore-pending` sa pri ďalšom štarte nasadí
    fun pendingRestores(): Set<String> = prefs.getStringSet(KEY_PENDING_RESTORE, null).orEmpty().toSet()

    @Synchronized
    fun setPendingRestores(names: Set<String>) {
        prefs.edit().putStringSet(KEY_PENDING_RESTORE, names).commit()
    }

    // výsledok poslednej nasadenej obnovy (ukáže sa raz po štarte)
    var restoredNotice: String?
        get() = prefs.getString(KEY_RESTORED, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_RESTORED) else putString(KEY_RESTORED, value) }.commit()
        }

    companion object {
        const val PREFS = "galeria_db_safety"
        private const val KEY_CORRUPT = "corrupt_events"
        private const val KEY_PENDING_RESTORE = "pending_restore"
        private const val KEY_RESTORED = "restored_notice"
        private const val SEP = "|"
        private const val PARTS = 3
    }
}
