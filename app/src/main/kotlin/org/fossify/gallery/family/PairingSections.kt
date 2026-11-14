package org.fossify.gallery.family

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.TextView
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityPairingBinding
import org.fossify.gallery.databinding.ItemFamilyPeerBinding
import org.fossify.gallery.sync.NetUtil
import java.util.concurrent.ExecutorService

// Časti obrazovky párovania (PairingActivity), rozdelené podľa toho, čo zobrazujú.

// QR kód tohto telefónu: identita, aktuálny jednorazový token, adresa a port servera, odpočet platnosti.
// Server sa spúšťa na `serverThread`, preto sa tam číta aj jeho port.
internal class PairingQrSection(
    private val activity: Activity,
    private val binding: ActivityPairingBinding,
    private val serverThread: ExecutorService,
) {
    private val ticker = Handler(Looper.getMainLooper())
    private var shownQr: String? = null

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    fun stop() = ticker.removeCallbacks(tick)

    // znova zostaví QR (nový token len vtedy, keď starý vypršal alebo sa použil)
    fun refresh() {
        if (serverThread.isShutdown) return
        serverThread.execute {
            val port = FamilyServer.port()
            val ip = NetUtil.getWifiIpv4(activity.applicationContext)
            val store = FamilyStore(activity.applicationContext)
            val name = store.selfName()
            val text = if (port != null && ip != null) {
                PairUri(store.selfId(), name, FamilyServer.pairingTokens.currentOrIssue(), ip, port).toUri()
            } else {
                null
            }
            activity.runOnUiThread {
                if (!activity.isDestroyed) {
                    binding.pairingNameSummary.text = name
                    render(text, if (ip == null) R.string.family_qr_no_wifi else R.string.family_qr_server_error)
                }
            }
        }
    }

    private fun render(text: String?, errorRes: Int) {
        binding.pairingQr.beVisibleIf(text != null)
        if (text == null) {
            shownQr = null
            binding.pairingQrStatus.setText(errorRes)
            return
        }
        if (text != shownQr) {
            shownQr = text
            val size = activity.resources.displayMetrics.widthPixels.coerceAtMost(QR_MAX_PX)
            binding.pairingQr.setImageBitmap(familyQrBitmap(text, size))
        }
        updateStatus()
    }

    private fun updateStatus() {
        if (shownQr == null) return
        val remaining = FamilyServer.pairingTokens.expiresAt() - System.currentTimeMillis()
        if (remaining <= 0) {
            binding.pairingQr.alpha = EXPIRED_ALPHA
            binding.pairingQrStatus.setText(R.string.family_qr_expired)
        } else {
            binding.pairingQr.alpha = 1f
            binding.pairingQrStatus.text = activity.getString(R.string.family_qr_valid, formatRemaining(remaining))
        }
    }

    private companion object {
        const val QR_MAX_PX = 800
        const val TICK_MS = 1000L
        const val EXPIRED_ALPHA = 0.25f
    }
}

// Zoznam prepojených telefónov so stavom (posledná synchronizácia, vo fronte, zlyhané fotky). Ťuk na telefón
// ukáže stav a voľby „Synchronizovať teraz“ / „Skúsiť znova“ a „Zrušiť prepojenie“. Počas snapshotu Ľudí po
// spárovaní je pod zoznamom priebeh.
internal class PairingPeersSection(private val activity: Activity, private val binding: ActivityPairingBinding) {
    private val ticker = Handler(Looper.getMainLooper())
    private var progressView: TextView? = null

    private class PeerStatus(
        val peer: PeerEntity,
        val needsRepair: Boolean,
        val lastSync: Long,
        val queue: OutboxRules.Summary,
    )

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            ticker.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    fun startProgress() {
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    fun stopProgress() = ticker.removeCallbacks(tick)

    fun load() {
        ensureBackgroundThread {
            val app = activity.applicationContext
            val store = FamilyStore(app)
            val statuses = store.peers().map {
                val id = it.deviceId
                PeerStatus(it, store.needsRepairing(id), store.lastSync(id), FamilySend.status(app, id))
            }
            activity.runOnUiThread { if (!activity.isDestroyed) render(statuses) }
        }
    }

    private fun render(statuses: List<PeerStatus>) {
        val container = binding.pairingPeers
        container.removeAllViews()
        for (status in statuses) {
            val peer = status.peer
            val row = ItemFamilyPeerBinding.inflate(activity.layoutInflater, container, false)
            row.familyPeerName.text = peer.name
            val paired = DateUtils.formatDateTime(activity, peer.pairedAt, DateUtils.FORMAT_SHOW_DATE)
            val summary = if (status.needsRepair) {
                activity.getString(R.string.d_family_peer_needs_pairing)
            } else {
                activity.getString(R.string.family_peer_summary, paired, relative(peer.lastSeenAt))
            }
            val queue = status.queue
            row.familyPeerSummary.text = if (queue.queued + queue.failed > 0) {
                summary + "\n" + activity.getString(R.string.f068_peer_queue, queue.queued, queue.failed)
            } else {
                summary
            }
            row.root.setOnClickListener { showStatus(status) }
            container.addView(row.root)
            activity.updateTextColors(row.root)
        }
        progressView = TextView(activity).also {
            val padding = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
            it.setPadding(padding, padding / 2, padding, padding / 2)
            container.addView(it)
            it.setTextColor(activity.getProperTextColor())
        }
        updateProgress()
        binding.pairingNoPeers.beVisibleIf(statuses.isEmpty())
    }

    private fun relative(time: Long): String = if (time > 0) {
        DateUtils.getRelativeTimeSpanString(time).toString()
    } else {
        activity.getString(R.string.family_peer_never)
    }

    private fun updateProgress() {
        val view = progressView ?: return
        val progress = FamilySnapshot.progress
        view.beVisibleIf(progress != null)
        if (progress != null) {
            view.text = activity.getString(R.string.f068_snapshot_progress, progress.first, progress.second)
        }
    }

    // stav prepojenia a voľby: synchronizovať teraz (pri zlyhaných fotkách „Skúsiť znova“) a zrušiť prepojenie
    private fun showStatus(status: PeerStatus) {
        val peer = status.peer
        val queue = status.queue
        val lastSync = relative(status.lastSync)
        val message = activity.getString(R.string.f068_peer_status, lastSync, queue.queued, queue.failed)
        val retry = queue.failed > 0
        activity.showFamilyChoiceDialog(
            peer.name,
            message,
            activity.getString(if (retry) R.string.f068_retry else R.string.f068_sync_now),
            { syncNow(peer, retry) },
            activity.getString(R.string.family_unpair_button),
        ) { confirmUnpair(peer.deviceId, peer.name) }
    }

    private fun syncNow(peer: PeerEntity, retryFailed: Boolean) {
        val app = activity.applicationContext
        activity.toast(R.string.f068_syncing)
        ensureBackgroundThread {
            val requeued = if (retryFailed) FamilySend.retryFailed(app, peer) else 0
            if (requeued > 0) {
                app.toast(app.resources.getQuantityString(R.plurals.f068_retry_queued, requeued, requeued))
            }
            val result = FamilySend.syncNow(app, peer.deviceId)
            val text = when (result?.status) {
                FamilyClient.Status.OK -> app.getString(R.string.f068_synced)
                FamilyClient.Status.NEEDS_PAIRING -> app.getString(R.string.d_family_send_needs_pairing, peer.name)
                FamilyClient.Status.INCOMPATIBLE -> app.getString(R.string.f068_sync_incompatible, peer.name)
                else -> app.getString(R.string.f068_sync_unreachable, peer.name)
            }
            app.toast(text)
            activity.runOnUiThread { if (!activity.isDestroyed) load() }
        }
    }

    fun confirmUnpair(deviceId: String, name: String) {
        activity.showFamilyMessageDialog(
            R.string.family_unpair_title,
            activity.getString(R.string.family_unpair_text, name),
            R.string.family_unpair_button,
        ) {
            val context = activity.applicationContext
            ensureBackgroundThread {
                FamilyStore(context).unpair(deviceId)
                activity.runOnUiThread {
                    activity.toast(activity.getString(R.string.family_unpaired, name))
                    load()
                }
                // ak je druhý telefón práve dostupný, dozvie sa to hneď (inak pri najbližšom stretnutí)
                FamilyClient(context).notifyUnpairs()
            }
        }
    }

    private companion object {
        const val PROGRESS_TICK_MS = 1000L
    }
}

// Názov tohto telefónu a ladiaca ručná adresa (len debug build).
internal class PairingOptionsSection(
    private val activity: Activity,
    private val binding: ActivityPairingBinding,
    private val onNameChanged: () -> Unit,
) {
    private val prefs = FamilyPrefs(activity)

    fun bind() {
        binding.pairingNameHolder.setOnClickListener { editName() }
        binding.pairingManualHolder.beVisibleIf(BuildConfig.DEBUG)
        binding.pairingManualHolder.setOnClickListener { editManual() }
    }

    fun updateManualSummary() {
        if (!BuildConfig.DEBUG) return
        binding.pairingManualSummary.text =
            prefs.manualEndpoint.ifEmpty { activity.getString(R.string.family_debug_manual_off) }
    }

    private fun editName() {
        val current = binding.pairingNameSummary.text.toString()
        activity.promptFamilyText(R.string.family_device_name, current, R.string.family_device_name_hint) { name ->
            if (name.isNotBlank()) {
                ensureBackgroundThread {
                    FamilyStore(activity.applicationContext).setSelfName(name)
                    activity.runOnUiThread { onNameChanged() }
                }
            }
        }
    }

    private fun editManual() {
        val hint = R.string.family_debug_manual_hint
        activity.promptFamilyText(R.string.family_debug_manual, prefs.manualEndpoint, hint) {
            prefs.manualEndpoint = if (FamilyEndpoint.parse(it) != null) it else ""
            updateManualSummary()
        }
    }
}
