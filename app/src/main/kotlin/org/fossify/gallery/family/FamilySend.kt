package org.fossify.gallery.family

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
@Suppress("TooManyFunctions")
object FamilySend {
    private const val CHANNEL_ID = "family_link"
    private const val NOTIF_BASE_ID = 49300
    private const val NOTIF_ID_RANGE = 100
    private const val NOTIF_FAILED_BASE_ID = 49400
    private const val STATE_PENDING = OutboxEntity.STATE_PENDING
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
                builder.assignedPersons(path).forEach { recorder.publishedUuid(it) }
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
    // a upratanie HOTOVÝCH prenosov (záverečná položka prešla) aj s kúskami súborov a cestou k súboru.
    // Kúsky nedokončeného prenosu ostávajú, aby sa dali poslať znova (OutboxRules).
    fun onSynced(context: Context, peer: PeerEntity) {
        val app = context.applicationContext
        val db = FamilyDatabase.getInstance(app)
        val items = db.outboxDao().getOutboxForPeer(peer.deviceId).filter { it.kind in BundleHandler.KINDS }
        val rows = items.map { OutboxRules.row(it) }
        val finished = OutboxRules.finishedTransfers(rows)
        if (finished.isEmpty()) return
        db.runInTransaction(
            Runnable {
                rows.filter { it.transferId in finished }.forEach { db.outboxDao().deleteOutbox(it.id) }
                finished.forEach { db.metaDao().delete(BundleTransfer.pathKey(it)) }
            },
        )
        notifySent(app, peer, finished.size)
    }

    // Položka fronty definitívne zlyhala: celý prenos (kúsky aj balík) sa označí ako zlyhaný a ostane vo fronte
    // (s cestou k súboru) pre „Skúsiť znova“. Vráti true, keď prenos zlyhal práve teraz.
    fun failTransfer(db: FamilyDatabase, peerId: String, transferId: String): Boolean {
        val rows = db.outboxDao().getOutboxForPeer(peerId).map { OutboxRules.row(it) }
        val ids = OutboxRules.toFail(rows, transferId)
        db.runInTransaction(Runnable { ids.forEach { db.outboxDao().updateOutbox(it, OutboxEntity.STATE_FAILED, 0) } })
        return ids.isNotEmpty()
    }

    // záverečný balík neprešiel (prijímateľ nemá všetky kúsky súboru) → odoslané kúsky pôjdu znova
    fun resendChunks(db: FamilyDatabase, peerId: String, transferId: String) {
        val rows = db.outboxDao().getOutboxForPeer(peerId).map { OutboxRules.row(it) }
        OutboxRules.chunksToResend(rows, transferId).forEach { db.outboxDao().updateOutbox(it, STATE_PENDING, 0) }
    }

    // stav fronty pre obrazovku prepojenia (vo fronte / zlyhané fotky). Len z pozadia.
    fun status(context: Context, peerId: String): OutboxRules.Summary {
        val db = FamilyDatabase.getInstance(context.applicationContext)
        return OutboxRules.summary(db.outboxDao().getOutboxForPeer(peerId).map { OutboxRules.row(it) })
    }

    // „Skúsiť znova“: zlyhané fotky sa zaradia nanovo (aktuálna veľkosť a čas súboru, ako pri prvom poslaní).
    // Fotka, ktorá už neexistuje alebo je teraz v zamknutom priečinku, z fronty vypadne. Vráti počet zaradených.
    fun retryFailed(context: Context, peer: PeerEntity): Int {
        val app = context.applicationContext
        val db = FamilyDatabase.getInstance(app)
        val items = db.outboxDao().getOutboxForPeer(peer.deviceId).filter { it.kind in BundleHandler.KINDS }
        val rows = items.map { OutboxRules.row(it) }
        val failed = OutboxRules.failedTransfers(rows)
        if (failed.isEmpty()) return 0
        val again = failed.mapNotNull { transferId ->
            val path = db.metaDao().get(BundleTransfer.pathKey(transferId))
            val withFile = items.any {
                it.kind == BundleTransfer.KIND_PHOTO && OutboxRules.row(it).transferId == transferId
            }
            path?.let { Triple(transferId, it, withFile) }
        }
        db.runInTransaction(
            Runnable {
                rows.filter { it.transferId in failed }.forEach { db.outboxDao().deleteOutbox(it.id) }
                failed.forEach { db.metaDao().delete(BundleTransfer.pathKey(it)) }
            },
        )
        return again.groupBy { it.third }.entries.sumOf { (withFile, list) ->
            enqueue(app, list.map { it.second }, peer, withFile)
        }
    }

    // prepojenie zrušené: fronta pre ten telefón sa zahodí (aj cesty k súborom); fotky na disku ostávajú
    fun dropQueue(db: FamilyDatabase, peerId: String) {
        val items = db.outboxDao().getOutboxForPeer(peerId)
        db.runInTransaction(
            Runnable {
                items.forEach { db.outboxDao().deleteOutbox(it.id) }
                items.mapNotNull { BundleTransfer.decode(it.refKey)?.transferId }.distinct()
                    .forEach { db.metaDao().delete(BundleTransfer.pathKey(it)) }
            },
        )
    }

    // N fotiek sa nepodarilo poslať (po opakovaných pokusoch alebo sa súbor zmenil) — nezmiznú potichu
    fun notifyFailed(context: Context, peer: PeerEntity, count: Int) {
        if (count <= 0) return
        try {
            ensureChannel(context)
            val text = context.resources.getQuantityString(R.plurals.f068_send_failed, count, count, peer.name)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.f068_send_failed_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pairingIntent(context))
                .setAutoCancel(true)
                .build()
            val id = NOTIF_FAILED_BASE_ID + Math.floorMod(peer.deviceId.hashCode(), NOTIF_ID_RANGE)
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (ignored: SecurityException) {
            // bez povolenia na notifikácie (Android 13+) — stav je vidieť na obrazovke prepojenia
        }
    }

    // ťuk na notifikáciu otvorí Nastavenia → Prepojiť telefón (stav fronty, „Skúsiť znova“)
    private fun pairingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            NOTIF_FAILED_BASE_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
