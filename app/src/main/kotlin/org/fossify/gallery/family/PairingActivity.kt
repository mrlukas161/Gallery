package org.fossify.gallery.family

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.activities.SimpleActivity
import org.fossify.gallery.databinding.ActivityPairingBinding
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Nastavenia → „Prepojiť telefón“. Zobrazí QR s jednorazovým tokenom (kým je obrazovka otvorená,
// beží FamilyServer, aby sa druhý telefón mohol spárovať), naskenuje QR druhého telefónu
// (systémový skener otvorí odkaz galeriaplus://, záloha je fotka cez ACTION_IMAGE_CAPTURE + ZxingDecoder)
// a spravuje prepojené telefóny (zrušenie prepojenia).
class PairingActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPairingBinding::inflate)

    // server sa spúšťa a zastavuje v poradí, v akom prišli onResume/onPause
    private val serverThread: ExecutorService = Executors.newSingleThreadExecutor()
    private val qr by lazy { PairingQrSection(this, binding, serverThread) }
    private val peers by lazy { PairingPeersSection(this, binding) }
    private val options by lazy { PairingOptionsSection(this, binding) { qr.refresh() } }
    private val scanFile by lazy { File(cacheDir, SCAN_FILE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.pairingAppbar),
            padBottomSystem = listOf(binding.pairingNestedScrollview),
        )
        setupMaterialScrollListener(binding.pairingNestedScrollview, binding.pairingAppbar)
        options.bind()
        binding.pairingScanHolder.setOnClickListener {
            if (!startFamilyScan(scanFile, REQUEST_SCAN)) toast(R.string.family_scan_no_camera)
        }
        // ťuknutie na vypršaný kód vytvorí nový
        binding.pairingQr.setOnClickListener { qr.refresh() }
        if (savedInstanceState == null) handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.pairingAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.pairingHolder)
        binding.pairingThisPhoneLabel.setTextColor(getProperPrimaryColor())
        binding.pairingPeersLabel.setTextColor(getProperPrimaryColor())
        options.updateManualSummary()
        FamilyServer.onPaired = { name -> runOnUiThread { onPairedHere(name) } }
        serverThread.execute { FamilyServer.acquire(applicationContext, FamilyServer.OWNER_PAIRING) }
        qr.refresh()
        qr.start()
        peers.load()
    }

    override fun onPause() {
        super.onPause()
        qr.stop()
        FamilyServer.onPaired = null
        serverThread.execute { FamilyServer.release(FamilyServer.OWNER_PAIRING) }
    }

    override fun onDestroy() {
        super.onDestroy()
        serverThread.shutdown()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCAN || resultCode != Activity.RESULT_OK) return
        val file = scanFile
        ensureBackgroundThread {
            val text = decodeFamilyQr(file)
            file.delete()
            runOnUiThread {
                val uri = text?.let { PairUri.parse(it) }
                when {
                    isDestroyed -> Unit
                    uri == null -> toast(R.string.family_scan_not_found)
                    else -> confirmPair(uri)
                }
            }
        }
    }

    // odkaz galeriaplus://family/pair?… zo skenera HyperOS / systémového fotoaparátu
    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || data.scheme != PairUri.SCHEME) return
        intent.data = null
        val uri = PairUri.parse(data.toString())
        if (uri == null) toast(R.string.family_pair_rejected) else confirmPair(uri)
    }

    private fun confirmPair(uri: PairUri) {
        showFamilyMessageDialog(
            R.string.family_pair_confirm_title,
            getString(R.string.family_pair_confirm, uri.name),
            R.string.family_pair_confirm_button,
        ) { pairWith(uri) }
    }

    private fun pairWith(uri: PairUri) {
        toast(R.string.family_pairing)
        ensureBackgroundThread {
            val outcome = FamilyClient(applicationContext).pair(uri)
            runOnUiThread {
                val name = outcome.peerName ?: uri.name
                when (outcome.status) {
                    FamilyClient.Status.OK -> toast(getString(R.string.family_paired_with, name))
                    FamilyClient.Status.UNREACHABLE -> toast(R.string.family_pair_unreachable)
                    FamilyClient.Status.REJECTED -> toast(R.string.family_pair_rejected)
                    else -> toast(R.string.family_pair_error)
                }
                if (!isDestroyed) peers.load()
            }
        }
    }

    // druhý telefón sa práve spároval s týmto (token sa spotreboval → nový QR)
    private fun onPairedHere(name: String) {
        if (isDestroyed) return
        toast(getString(R.string.family_paired_with, name))
        qr.refresh()
        peers.load()
    }

    private companion object {
        const val REQUEST_SCAN = 7301
        const val SCAN_FILE = "family_scan.jpg"
    }
}
