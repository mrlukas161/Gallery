package org.fossify.gallery.family

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import org.fossify.commons.extensions.beVisibleIf
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

// Zoznam prepojených telefónov a zrušenie prepojenia.
internal class PairingPeersSection(private val activity: Activity, private val binding: ActivityPairingBinding) {

    fun load() {
        ensureBackgroundThread {
            val peers = FamilyStore(activity.applicationContext).peers()
            activity.runOnUiThread { if (!activity.isDestroyed) render(peers) }
        }
    }

    private fun render(peers: List<PeerEntity>) {
        val container = binding.pairingPeers
        container.removeAllViews()
        for (peer in peers) {
            val row = ItemFamilyPeerBinding.inflate(activity.layoutInflater, container, false)
            row.familyPeerName.text = peer.name
            val paired = DateUtils.formatDateTime(activity, peer.pairedAt, DateUtils.FORMAT_SHOW_DATE)
            val seen = if (peer.lastSeenAt > 0) {
                DateUtils.getRelativeTimeSpanString(peer.lastSeenAt).toString()
            } else {
                activity.getString(R.string.family_peer_never)
            }
            row.familyPeerSummary.text = activity.getString(R.string.family_peer_summary, paired, seen)
            row.root.setOnClickListener { confirmUnpair(peer) }
            container.addView(row.root)
            activity.updateTextColors(row.root)
        }
        binding.pairingNoPeers.beVisibleIf(peers.isEmpty())
    }

    private fun confirmUnpair(peer: PeerEntity) {
        activity.showFamilyMessageDialog(
            R.string.family_unpair_title,
            activity.getString(R.string.family_unpair_text, peer.name),
            R.string.family_unpair_button,
        ) {
            val context = activity.applicationContext
            ensureBackgroundThread {
                FamilyStore(context).unpair(peer.deviceId)
                activity.runOnUiThread {
                    activity.toast(activity.getString(R.string.family_unpaired, peer.name))
                    load()
                }
                // ak je druhý telefón práve dostupný, dozvie sa to hneď (inak pri najbližšom stretnutí)
                FamilyClient(context).notifyUnpairs()
            }
        }
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
