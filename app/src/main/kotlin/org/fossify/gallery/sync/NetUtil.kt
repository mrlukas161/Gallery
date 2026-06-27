package org.fossify.gallery.sync

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

// Zistenie LAN IPv4 adresy telefónu na zobrazenie URL servera.
object NetUtil {

    // Preferované: prejde sieťové rozhrania a vráti prvú non-loopback site-local IPv4 (žiadne povolenie).
    fun getWifiIpv4(context: Context): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return wifiManagerFallback(context)
            for (nif in ifaces.toList()) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val name = nif.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("dummy")) continue
                for (addr in nif.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
        }
        return wifiManagerFallback(context)
    }

    @Suppress("DEPRECATION")
    private fun wifiManagerFallback(context: Context): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val ip = wm.connectionInfo?.ipAddress ?: return null
            if (ip == 0) return null
            "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        } catch (e: Exception) {
            null
        }
    }
}
