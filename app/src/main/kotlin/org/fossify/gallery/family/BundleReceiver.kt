package org.fossify.gallery.family

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.fossify.gallery.sync.saveMediaScoped
import java.io.File

// Príjem balíkov z prepojeného telefónu (F4, FamilyHandler.onBundle; volá sa na vlákne FamilyServer).
//  - KIND_CHUNK: kúsok súboru sa zapíše rovno na disk (InboundParts), nikdy celý súbor do RAM.
//  - KIND_PHOTO: keď sú všetky kúsky, overí sa obsahový kľúč, súbor sa uloží do MediaStore rovnako ako
//    Server pre PC (Pictures/Galéria, Movies/Galéria; pri zhode mena MediaStore pridá „(1)“) a výsledky sa
//    zapíšu k novej fotke. Ak tu fotka s rovnakým obsahom už je, druhá kópia nevznikne.
//  - KIND_RESULTS: balík bez súboru. Fotka sa hľadá podľa obsahového kľúča a zálohy (FuzzyMatch); ak tu
//    ešte nie je, balík čaká vo family.db (bundle_inbox) a aplikuje ho spracovanie knižnice (FamilyInbox).
// Opakované doručenie (stratená odpoveď) sa nespracuje druhýkrát: bundle_inbox drží značku podľa id.
class BundleReceiver(context: Context) {
    private val app = context.applicationContext
    private val inbox = FamilyDatabase.getInstance(app).bundleInboxDao()
    private val parts = InboundParts(File(app.noBackupFilesDir, DIR_NAME))

    fun receive(peer: PeerEntity, kind: String, refKey: String, data: ByteArray): Boolean {
        val ref = BundleTransfer.decode(refKey) ?: return false
        return synchronized(LOCK) {
            parts.cleanupStale()
            when (kind) {
                BundleTransfer.KIND_CHUNK -> chunk(peer, ref, data)
                BundleTransfer.KIND_PHOTO -> photo(peer, ref, data)
                BundleTransfer.KIND_RESULTS -> results(peer, ref, data)
                else -> false
            }
        }
    }

    private fun chunk(peer: PeerEntity, ref: BundleTransfer.Ref, data: ByteArray): Boolean = when {
        ref.index < 0 || data.size != ref.length -> false
        inbox.getBundle(inboxId(peer, ref)) != null -> true // fotka už je uložená
        else -> parts.write(peer, ref, data)
    }

    private fun photo(peer: PeerEntity, ref: BundleTransfer.Ref, data: ByteArray): Boolean {
        val id = inboxId(peer, ref)
        inbox.getBundle(id)?.let { return it.state == BundleInboxEntity.STATE_APPLIED }
        val bundle = PhotoBundleCodec.decode(String(data, Charsets.UTF_8))
            ?.takeIf { it.size == ref.size && ref.count > 0 }
        val file = bundle?.let { parts.complete(peer, ref, it.contentKey) } ?: return false
        // rovnaká fotka tu už je (napr. poslaná druhýkrát) → bez druhej kópie, len výsledky
        val target = LocalPhotoFinder(app).exact(bundle)?.let { Pair<String, Long?>(it, null) }
            ?: save(bundle, file)
            ?: return false
        parts.discard(peer, ref)
        BundleApplier(app).apply(
            target.first,
            bundle,
            BundleMatch.EXACT,
            mediaStoreId = target.second,
            fromDeviceId = peer.deviceId,
            inboxId = id,
        )
        finish(peer, id, bundle, target.first)
        return true
    }

    private fun results(peer: PeerEntity, ref: BundleTransfer.Ref, data: ByteArray): Boolean {
        val id = inboxId(peer, ref)
        if (inbox.getBundle(id) != null) return true
        val json = String(data, Charsets.UTF_8)
        val bundle = PhotoBundleCodec.decode(json) ?: return false
        val found = LocalPhotoFinder(app).find(bundle)
        if (found == null) {
            // fotka sem ešte neprišla (WhatsApp, Quick Share…): počká, kým ju nájde spracovanie knižnice
            inbox.insertBundle(
                BundleInboxEntity(
                    id = id,
                    fromDeviceId = peer.deviceId,
                    contentKey = bundle.contentKey,
                    payloadJson = json,
                    receivedAt = System.currentTimeMillis(),
                    state = BundleInboxEntity.STATE_WAITING,
                ),
            )
            FamilyInbox.invalidate()
        } else {
            BundleApplier(app).apply(
                found.path,
                bundle,
                found.match,
                found.localPhash,
                names = found.names,
                fromDeviceId = peer.deviceId,
                inboxId = id,
            )
            finish(peer, id, bundle, found.path)
        }
        return true
    }

    private fun finish(peer: PeerEntity, id: String, bundle: PhotoBundle, path: String) {
        inbox.insertBundle(
            BundleInboxEntity(
                id = id,
                fromDeviceId = peer.deviceId,
                contentKey = bundle.contentKey,
                payloadJson = "",
                receivedAt = System.currentTimeMillis(),
                state = BundleInboxEntity.STATE_APPLIED,
                appliedPath = path,
            ),
        )
        FamilyInbox.invalidate()
        // čakajúce priradenia tvárí z op-logu (F3) teraz nájdu fotku (rovnako ako OpLogHandler na vlákne servera)
        FamilySync(app).retryPending()
    }

    // Uloží súbor do MediaStore (ako Server pre PC, saveMediaScoped). Vráti (cesta, MediaStore id) alebo null.
    private fun save(bundle: PhotoBundle, file: File): Pair<String, Long?>? {
        val mime = bundle.mime.takeIf { it.startsWith("image/") || it.startsWith("video/") } ?: return null
        val saved = saveMediaScoped(app, BundleTransfer.displayName(bundle.name), mime, SAVE_SUBDIR) { out ->
            file.inputStream().use { it.copyTo(out, COPY_BUFFER) }
        } ?: return null
        @Suppress("DEPRECATION")
        val data = MediaStore.MediaColumns.DATA
        val projection = arrayOf(data, MediaStore.MediaColumns._ID)
        return app.contentResolver.query(Uri.parse(saved.second), projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { it to c.getLong(1) } else null
        }
    }

    private fun inboxId(peer: PeerEntity, ref: BundleTransfer.Ref) = peer.deviceId + ":" + ref.transferId

    private companion object {
        const val DIR_NAME = "family_in"
        const val SAVE_SUBDIR = "Galéria" // rovnako ako Server pre PC (MediaServer.SAVE_SUBDIR)
        const val COPY_BUFFER = 256 * 1024
        val LOCK = Any()
    }
}
