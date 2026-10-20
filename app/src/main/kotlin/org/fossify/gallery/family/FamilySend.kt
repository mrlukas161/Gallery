package org.fossify.gallery.family

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.fossify.gallery.R
import java.io.File
import java.util.UUID

// „Poslať do prepojeného telefónu“ (F4): pridanie fotiek do fronty (outbox, prežije reštart appky),
// pokus o odoslanie hneď (FamilyClient.syncAll na pozadí) a notifikácia po odoslaní. Ak druhý telefón
// nie je dostupný, položky ostanú vo fronte a odídu pri najbližšej synchronizácii (spúšťače F5).
// Nič tu nebeží trvalo: synchronizácia je jednorazová a skončí sa sama.
object FamilySend {
    private const val CHANNEL_ID = "family_link"
    private const val NOTIF_BASE_ID = 49300
    private const val NOTIF_ID_RANGE = 100
    private const val PAIRED_TTL_MS = 30_000L
    private const val OWNER_SEND = "send"

    @Volatile
    private var paired = false

    @Volatile
    private var pairedCheckedAt = 0L

    // Pre viditeľnosť akcie v menu (hlavné vlákno, bez DB): posledný známy stav, obnoví sa na pozadí.
    fun isPaired(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - pairedCheckedAt > PAIRED_TTL_MS) {
            pairedCheckedAt = now
            val app = context.applicationContext
            Thread {
                paired = try {
                    FamilyStore(app).peers().isNotEmpty()
                } catch (ignored: RuntimeException) {
                    false
                }
            }.start()
        }
        return paired
    }

    // Pridá fotky do fronty pre `peer`. withFile = false: len balík výsledkov (fotku už má, napr. z WhatsAppu).
    // Vráti počet pridaných fotiek; preskočí zamknuté/vylúčené/skryté, kôš, chýbajúce a príliš veľké súbory.
    // Volať len na pozadí.
    fun enqueue(context: Context, paths: List<String>, peer: PeerEntity, withFile: Boolean): Int {
        val app = context.applicationContext
        val db = FamilyDatabase.getInstance(app)
        val builder = BundleBuilder(app)
        val keys = ContentKeyStore(db.contentKeyDao())
        val recorder = OpRecorder(app)
        var queued = 0
        for (path in paths.distinct()) {
            val file = File(path)
            val size = file.length()
            val ok = builder.isAllowed(path) && file.isFile && size in 1..BundleTransfer.MAX_FILE_SIZE
            // obsahový kľúč sa počíta lenivo práve tu (spec časť 3: pri prvom poslaní)
            if (!ok || keys.keyFor(path) == null) continue
            // osoby na fotke dostanú rodinné UUID hneď: PersonCreate odíde v tej istej synchronizácii pred balíkom
            try {
                builder.assignedPersons(path).forEach { recorder.uuidFor(it) }
            } catch (ignored: RuntimeException) {
                // bez UUID len nepríde priradenie; tváre a ostatné výsledky odídu aj tak
            }
            insert(db, peer.deviceId, file, withFile)
            queued++
        }
        return queued
    }

    private fun insert(db: FamilyDatabase, peerId: String, file: File, withFile: Boolean) {
        val transferId = UUID.randomUUID().toString()
        val size = file.length()
        val mtime = file.lastModified()
        val count = if (withFile) BundleTransfer.chunkCount(size) else 0
        val now = System.currentTimeMillis()
        val chunk = BundleTransfer.CHUNK_SIZE
        db.runInTransaction(
            Runnable {
                db.metaDao().put(MetaEntity(BundleTransfer.pathKey(transferId), file.absolutePath))
                val outbox = db.outboxDao()
                for (i in 0 until count) {
                    val ref = BundleTransfer.Ref(transferId, i, count, chunk, size, mtime)
                    val encoded = BundleTransfer.encode(ref)
                    outbox.insertOutbox(OutboxEntity(0, peerId, BundleTransfer.KIND_CHUNK, encoded, now))
                }
                val kind = if (withFile) BundleTransfer.KIND_PHOTO else BundleTransfer.KIND_RESULTS
                val ref = BundleTransfer.Ref(transferId, -1, count, chunk, size, mtime)
                outbox.insertOutbox(OutboxEntity(0, peerId, kind, BundleTransfer.encode(ref), now))
            },
        )
    }

    // Jednorazová synchronizácia hneď po pridaní do fronty (blokuje, volať na pozadí). Ide za sebou s ostatnými
    // synchronizáciami (zámok FamilyAutoSync — inak by sa tá istá položka fronty mohla poslať dvakrát naraz)
    // a počas nej server prijíma spojenia (druhý telefón môže práve synchronizovať s nami).
    // Vráti výsledok pre daný telefón (null = už nie je prepojený).
    fun syncNow(context: Context, peerId: String): FamilyClient.SyncResult? = FamilyAutoSync.exclusive {
        val app = context.applicationContext
        FamilyServer.acquire(app, OWNER_SEND)
        try {
            FamilyClient(app).syncAll().firstOrNull { it.deviceId == peerId }
        } finally {
            FamilyServer.release(OWNER_SEND)
        }
    }

    // Po celej synchronizácii (FamilyHandler.onSyncFinished, obe strany): notifikácia o odoslaných fotkách
    // a upratanie odoslaných položiek fronty (aj kúskov súborov) a ciest k nim.
    fun onSynced(context: Context, peer: PeerEntity) {
        val app = context.applicationContext
        val db = FamilyDatabase.getInstance(app)
        val sent = db.outboxDao().getOutbox(peer.deviceId, OutboxEntity.STATE_SENT)
            .filter { it.kind in BundleHandler.KINDS }
        if (sent.isEmpty()) return
        val finals = sent.filter { it.kind != BundleTransfer.KIND_CHUNK }
        db.runInTransaction(
            Runnable {
                sent.forEach { db.outboxDao().deleteOutbox(it.id) }
                finals.mapNotNull { BundleTransfer.decode(it.refKey) }
                    .forEach { db.metaDao().delete(BundleTransfer.pathKey(it.transferId)) }
            },
        )
        if (finals.isNotEmpty()) notifySent(app, peer, finals.size)
    }

    private fun notifySent(context: Context, peer: PeerEntity, count: Int) {
        try {
            ensureChannel(context)
            val text = context.resources.getQuantityString(R.plurals.family_send_done, count, count, peer.name)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(context.getString(R.string.family_send_done_title))
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            val id = NOTIF_BASE_ID + Math.floorMod(peer.deviceId.hashCode(), NOTIF_ID_RANGE)
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (ignored: SecurityException) {
            // bez povolenia na notifikácie (Android 13+) — fotky odišli, len to neoznámime
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.family_send_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
}
