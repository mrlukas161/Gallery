package org.fossify.gallery.family

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Hľadanie spárovaného telefónu na Wi-Fi bez zadávania IP (Android NSD / mDNS).
// Služba `_galeriaf._tcp`, názov „galeriaf-<značka>“ a TXT id=<značka>, kde značka = FamilyCrypto.publicTag
// (hash deviceId, samotné deviceId sa na sieti neohlasuje). Nájdená adresa sa aj tak overí kľúčom páru.
// V emulátore NSD nefunguje → v debug builde ručná IP:port (FamilyPrefs.manualEndpoint).
class FamilyDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registration: NsdManager.RegistrationListener? = null

    fun register(port: Int, selfTag: String) {
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = NAME_PREFIX + selfTag
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(ATTR_ID, selfTag)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
        } catch (ignored: IllegalArgumentException) {
        }
    }

    fun unregister() {
        val listener = registration ?: return
        registration = null
        try {
            nsd?.unregisterService(listener)
        } catch (ignored: IllegalArgumentException) {
        }
    }

    // Nájde telefón so značkou peerTag. Blokuje najviac timeoutMs, volať na pozadí. null = nenašiel sa.
    fun find(peerTag: String, timeoutMs: Long): FamilyEndpoint? {
        val manager = nsd ?: return null
        val done = CountDownLatch(1)
        val found = AtomicReference<FamilyEndpoint?>()
        val resolving = AtomicBoolean(false)

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = resolving.set(false)

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val tag = serviceInfo.attributes[ATTR_ID]?.toString(Charsets.UTF_8)
                val host = hostOf(serviceInfo)
                if (tag == peerTag && host != null) {
                    found.set(FamilyEndpoint(host, serviceInfo.port))
                    done.countDown()
                } else {
                    resolving.set(false)
                }
            }
        }
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // NSD rieši naraz len jeden resolve; meno už obsahuje značku, takže sa rieši len ten správny
                if (serviceInfo.serviceName.contains(peerTag) && resolving.compareAndSet(false, true)) {
                    resolve(manager, serviceInfo, resolveListener)
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = done.countDown()
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (ignored: IllegalArgumentException) {
            return null
        }
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try {
                manager.stopServiceDiscovery(discoveryListener)
            } catch (ignored: IllegalArgumentException) {
            }
        }
        return found.get()
    }

    // resolveService/host sú od API 34 zastarané, ale fungujú na všetkých verziách od minSdk 26
    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        try {
            manager.resolveService(info, listener)
        } catch (ignored: IllegalArgumentException) {
            listener.onResolveFailed(info, NsdManager.FAILURE_INTERNAL_ERROR)
        }
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? = info.host?.hostAddress

    companion object {
        const val SERVICE_TYPE = "_galeriaf._tcp"
        private const val NAME_PREFIX = "galeriaf-"
        private const val ATTR_ID = "id"
    }
}
