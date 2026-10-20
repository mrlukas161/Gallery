package org.fossify.gallery.family

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

// Napojenie balíkov k fotkám (F4) na prenos (F2). Registruje sa raz v App.onCreate vedľa OpLogHandler.
//  - buildBundle (klient, pri odosielaní fronty): kúsok súboru priamo zo súboru alebo balík výsledkov,
//  - onBundle (server): BundleReceiver,
//  - onSyncFinished: notifikácia o odoslaných fotkách a upratanie odoslaných položiek fronty.
class BundleHandler(context: Context) : FamilyHandler {
    private val app = context.applicationContext

    // builder drží cache (spracované cesty, priradenia) — nový pre každé odoslanie fronty, nie navždy
    private var builder: BundleBuilder? = null
    private var builderAt = 0L

    override fun buildBundle(peer: PeerEntity, item: OutboxEntity): ByteArray? {
        if (item.kind !in KINDS) return null
        val ref = BundleTransfer.decode(item.refKey) ?: return null
        val path = FamilyDatabase.getInstance(app).metaDao().get(BundleTransfer.pathKey(ref.transferId)) ?: return null
        val file = File(path)
        val builder = builder()
        // súbor sa medzičasom zmenil alebo zmizol, alebo priečinok medzitým zamkli → neposielať (spec 2.5)
        val unchanged = file.isFile && file.length() == ref.size && file.lastModified() == ref.mtime
        if (!builder.isAllowed(path) || (item.kind != BundleTransfer.KIND_RESULTS && !unchanged)) return null
        return when (item.kind) {
            BundleTransfer.KIND_CHUNK -> readChunk(file, ref)
            else -> builder.build(path)?.let { PhotoBundleCodec.encode(it).utf8() }
        }
    }

    override fun onBundle(peer: PeerEntity, kind: String, refKey: String, data: ByteArray): Boolean =
        kind in KINDS && BundleReceiver(app).receive(peer, kind, refKey, data)

    override fun onSyncFinished(peer: PeerEntity) {
        FamilySend.onSynced(app, peer)
    }

    @Synchronized
    private fun builder(): BundleBuilder {
        val now = System.currentTimeMillis()
        val current = builder?.takeIf { now - builderAt < BUILDER_TTL_MS }
        return current ?: BundleBuilder(app).also {
            builder = it
            builderAt = now
        }
    }

    // najviac CHUNK_SIZE bajtov z daného miesta súboru (nikdy celý súbor)
    private fun readChunk(file: File, ref: BundleTransfer.Ref): ByteArray? = try {
        RandomAccessFile(file, "r").use { raf ->
            val out = ByteArray(ref.length)
            raf.seek(ref.offset)
            raf.readFully(out)
            out
        }
    } catch (ignored: IOException) {
        null
    }

    companion object {
        val KINDS = setOf(BundleTransfer.KIND_CHUNK, BundleTransfer.KIND_PHOTO, BundleTransfer.KIND_RESULTS)
        private const val BUILDER_TTL_MS = 2 * 60 * 1000L
    }
}
