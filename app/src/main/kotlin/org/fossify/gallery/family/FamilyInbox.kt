package org.fossify.gallery.family

import android.content.Context
import org.fossify.commons.extensions.isImageFast
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Lacná kontrola pred spracovaním fotky v indexeroch (F4): čaká pre ňu balík výsledkov z prepojeného
// telefónu (fotka prišla inak než cez Galériu+, napr. WhatsApp alebo Quick Share)? Ak áno, balík sa
// aplikuje a indexer túto časť preskočí — procesor nerobí nič druhýkrát.
//
// Keď nečaká žiadny balík (bežný stav), je to jedno čítanie premennej v pamäti, bez DB a bez súboru.
// Keď balíky čakajú, počíta sa obsahový kľúč fotky (128 kB, potom z cache) a pre fotky zo zhruba
// rovnakého obdobia aj pHash (64 px) na záložnú zhodu. Volajú to indexery na svojom vlákne na pozadí.
object FamilyInbox {
    const val FACES = "faces"
    const val OCR = "ocr"
    const val GEO = "geo"
    const val PHASH = "phash"
    const val CLIP = "clip"
    const val QR = "qr"

    private class Waiting(val id: String, val fromDeviceId: String, val bundle: PhotoBundle, val receivedAt: Long) {
        val match = InboxMatcher.Waiting(
            id,
            bundle.contentKey,
            bundle.phash?.let { FuzzyMatch.Fingerprint(it, bundle.takenAtMs) },
            receivedAt,
        )
    }

    // null = ešte nenačítané z family.db
    @Volatile
    private var waiting: List<Waiting>? = null

    // cesty, ktoré sa so žiadnym čakajúcim balíkom nespárovali (podľa veľkosti a mtime súboru)
    private val checked = ConcurrentHashMap<String, Long>()

    // Vráti true, keď výsledok časti `part` pre fotku dodal balík z prepojeného telefónu (indexer ju preskočí).
    // Nikdy nevyhodí výnimku: pri akejkoľvek chybe vráti false a indexer pokračuje ako doteraz.
    @Suppress("TooGenericExceptionCaught")
    fun supplies(context: Context, path: String, part: String): Boolean = try {
        val list = waiting ?: load(context)
        list.isNotEmpty() && check(context, path, list).contains(part)
    } catch (e: Exception) {
        android.util.Log.w(TAG, "kontrola balíka zlyhala", e)
        false
    }

    // Pre indexer: zoznam fotiek na spracovanie bez tých, ktorých časť `part` práve dodal balík. Keď nečaká
    // žiadny balík, vráti ten istý zoznam bez akejkoľvek práce.
    fun withoutSupplied(context: Context, paths: List<String>, part: String): List<String> =
        withoutSupplied(context, paths, part) { it }

    fun <T> withoutSupplied(context: Context, items: List<T>, part: String, pathOf: (T) -> String): List<T> {
        if (!hasWaiting(context)) return items
        return items.filterNot { supplies(context, pathOf(it), part) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun hasWaiting(context: Context): Boolean = try {
        (waiting ?: load(context)).isNotEmpty()
    } catch (e: Exception) {
        android.util.Log.w(TAG, "čakajúce balíky sa nedajú načítať", e)
        false
    }

    // po zmene bundle_inbox (BundleReceiver)
    fun invalidate() {
        waiting = null
        checked.clear()
    }

    private fun load(context: Context): List<Waiting> = synchronized(this) {
        waiting?.let { return it }
        val dao = FamilyDatabase.getInstance(context.applicationContext).bundleInboxDao()
        val now = System.currentTimeMillis()
        val list = dao.getBundles(BundleInboxEntity.STATE_WAITING).mapNotNull { row ->
            if (BundleRules.isExpired(row.receivedAt, now)) {
                dao.finishBundle(row.id, BundleInboxEntity.STATE_EXPIRED, null, "")
                return@mapNotNull null
            }
            PhotoBundleCodec.decode(row.payloadJson)?.let { Waiting(row.id, row.fromDeviceId, it, row.receivedAt) }
        }
        waiting = list
        list
    }

    private fun check(context: Context, path: String, list: List<Waiting>): Set<String> = synchronized(this) {
        val file = File(path)
        if (!file.isFile) return emptySet()
        val stamp = file.length() * STAMP_PRIME + file.lastModified()
        if (checked[path] == stamp) return emptySet()
        val app = context.applicationContext
        val key = ContentKeyStore(FamilyDatabase.getInstance(app).contentKeyDao()).keyFor(path)
        val finder = LocalPhotoFinder(app)
        // pHash sa počíta, len keď sa presná zhoda nenájde a čaká balík z podobného obdobia
        val local by lazy { if (path.isImageFast()) finder.fingerprint(path) else null }
        // D5: logika párovania (presná zhoda, záloha, séria bez EXIF) je v InboxMatcher
        val choice = InboxMatcher.choose(
            key,
            { local },
            file.lastModified(),
            list.map { it.match },
        ) { finder.recentFingerprints(exclude = path) }
        val entry = choice?.let { c -> list.firstOrNull { it.id == c.id } }
        if (choice == null || entry == null) {
            checked[path] = stamp
            return emptySet()
        }
        val localPhash = if (choice.match == BundleMatch.FUZZY) local?.phash else null
        val wrote = BundleApplier(app).apply(
            path,
            entry.bundle,
            choice.match,
            localPhash,
            names = choice.names,
            fromDeviceId = entry.fromDeviceId,
            inboxId = entry.id,
        )
        FamilyDatabase.getInstance(app).bundleInboxDao()
            .finishBundle(entry.id, BundleInboxEntity.STATE_APPLIED, path, "")
        waiting = list - entry
        val parts = partsOf(wrote)
        // čakajúce priradenia z op-logu (F3); cez zámok synchronizácie, aby neboli dve aplikovania naraz
        FamilyAutoSync.retryPendingIfDue(app)
        parts
    }

    private fun partsOf(plan: TakePlan): Set<String> = buildSet {
        if (plan.faces) add(FACES)
        if (plan.ocr) add(OCR)
        if (plan.geo) add(GEO)
        if (plan.phash) add(PHASH)
        if (plan.clip) add(CLIP)
        if (plan.qr) add(QR)
    }

    private const val TAG = "FamilyInbox"
    private const val STAMP_PRIME = 31L
}
