package org.fossify.gallery.sync

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

// Zistenie LAN IPv4 adresy telefónu na zobrazenie URL servera.
object NetUtil {

    // Všetky lokálne IPv4 adresy telefónu, cez ktoré sa dá dostať k serveru (Wi-Fi, USB kábel = zdieľanie pripojenia
    // cez USB, hotspot…), zoradené podľa LinkKinds. Bez povolenia; prázdne = PC sa nemá ako pripojiť.
    fun addresses(context: Context): List<Pair<LinkKind, String>> {
        val found = ArrayList<Pair<LinkKind, String>>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif -> addIfUsable(nif, found) }
        } catch (ignored: Exception) {
        }
        if (found.isEmpty()) wifiManagerFallback(context)?.let { found.add(LinkKind.WIFI to it) }
        return LinkKinds.sorted(found)
    }

    private fun addIfUsable(nif: NetworkInterface, out: MutableList<Pair<LinkKind, String>>) {
        if (!nif.isUp || nif.isLoopback || nif.isVirtual) return
        val kind = LinkKinds.kindOf(nif.name) ?: return
        nif.inetAddresses.toList()
            .filter { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .forEach { out.add(kind to it) }
    }

    // adresa pre párovanie telefónov: Wi-Fi (druhý telefón je na nej), inak čokoľvek
    fun getWifiIpv4(context: Context): String? {
        val all = addresses(context)
        return (all.firstOrNull { it.first == LinkKind.WIFI } ?: all.firstOrNull())?.second
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
