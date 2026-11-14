package org.fossify.gallery.safety

import android.app.Activity
import android.content.Context
import android.util.Log
import org.fossify.gallery.R
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

// Metadáta automatických záloh (N8): počet osôb, priradení, obľúbených a či je záloha zdravá
// (AutoBackupHealth). Ukladajú sa do súkromných preferencií a zároveň do ZIP-u zálohy
// (AutoBackupHealth.META_ENTRY), aby sa dali prečítať aj po preinštalovaní. Blokuje → len z pozadia.
object AutoBackupMeta {
    private const val TAG = "AutoBackupMeta"
    private const val PREFS = "galeria_auto_backup_meta"
    private const val KEY_META = "backup_meta"
    private const val MAX_TEXT_ENTRY = 8L * 1024 * 1024
    private const val BUFFER = 64 * 1024

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun metas(context: Context): Map<String, AutoBackupHealth.Meta> =
        prefs(context).getStringSet(KEY_META, null).orEmpty()
            .mapNotNull { AutoBackupHealth.decode(it) }
            .associateBy { it.stamp }

    @Synchronized
    fun save(context: Context, meta: AutoBackupHealth.Meta) {
        store(context, metas(context) + (meta.stamp to meta))
    }

    // po rotácii: metadáta len zmazaných záloh (zoznam súborov nemusí vidieť všetky zálohy)
    @Synchronized
    fun forget(context: Context, stamps: Set<String>) {
        if (stamps.isEmpty()) return
        store(context, metas(context).filterKeys { it !in stamps })
    }

    private fun store(context: Context, metas: Map<String, AutoBackupHealth.Meta>) {
        val set = metas.values.map { AutoBackupHealth.encode(it) }.toSet()
        prefs(context).edit().putStringSet(KEY_META, set).apply()
    }

    // Metadáta pre zoznam záloh: z preferencií, a čo tam chýba (napr. po preinštalovaní), zo ZIP-u.
    @Suppress("TooGenericExceptionCaught")
    fun metasFor(
        context: Context,
        backups: AutoBackupStore,
        items: List<AutoBackupStore.Item>,
    ): Map<String, AutoBackupHealth.Meta> {
        val result = metas(context).toMutableMap()
        items.filter { it.kind == AutoBackupNames.Kind.DB }.forEach { item ->
            val stamp = AutoBackupNames.stampOf(item.name) ?: return@forEach
            if (result.containsKey(stamp)) return@forEach
            try {
                readTexts(backups, item)[AutoBackupHealth.META_ENTRY]
                    ?.let { AutoBackupHealth.decode(it) }
                    ?.takeIf { it.stamp == stamp }
                    ?.let { result[stamp] = it }
            } catch (e: IOException) {
                Log.w(TAG, "metadáta zálohy ${item.name} sa nepodarilo prečítať", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "metadáta zálohy ${item.name} sa nepodarilo prečítať", e)
            }
        }
        return result
    }

    // Ponuka pri obnove po poškodení: ZIP zálohy spred prvého poškodenia zálohovanej DB, najnovšie prvé.
    fun offers(
        context: Context,
        events: List<DbSafetyState.Corruption>,
    ): List<Pair<AutoBackupHealth.Candidate, AutoBackupStore.Item>> {
        val backups = AutoBackupStore(context)
        val items = backups.list().filter { it.kind == AutoBackupNames.Kind.DB }
        val metas = metasFor(context, backups, items)
        val corruptedAt = events.filter { it.dbName in DbFiles.BACKED_UP && it.at > 0L }.minOfOrNull { it.at }
        val byName = items.associateBy { it.name }
        return AutoBackupHealth.candidates(items.map { it.name to it.createdAt }, metas, corruptedAt)
            .mapNotNull { c -> byName[c.name]?.let { c to it } }
    }

    // „14. 11. 2026 3:15 · osôb: 25, priradení: 1200“ (+ upozornenie pri podozrivej zálohe)
    fun label(activity: Activity, createdAt: Long, meta: AutoBackupHealth.Meta?): String {
        val date = DataSafetyUi.formatDate(createdAt)
        val base = if (meta != null && meta.persons >= 0 && meta.assignments >= 0) {
            activity.getString(R.string.fix068_backup_counts, date, meta.persons, meta.assignments)
        } else {
            date
        }
        return if (meta?.healthy == false) base + " " + activity.getString(R.string.fix068_backup_suspect) else base
    }

    // malé textové súbory zo začiatku ZIP-u; číta sa len po prvú databázu
    @Throws(IOException::class)
    fun readTexts(backups: AutoBackupStore, zip: AutoBackupStore.Item): Map<String, String> {
        val wanted = setOf(AutoBackupHealth.META_ENTRY, FavoritesBackup.ENTRY)
        val result = HashMap<String, String>()
        backups.open(zip).use { input ->
            val z = ZipInputStream(input)
            var entry = z.nextEntry
            while (entry != null && entry.name in wanted) {
                result[entry.name] = readLimited(z)
                entry = z.nextEntry
            }
        }
        return result
    }

    private fun readLimited(z: ZipInputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = z.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_TEXT_ENTRY) throw IOException("súbor v zálohe je príliš veľký")
            out.write(buffer, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
