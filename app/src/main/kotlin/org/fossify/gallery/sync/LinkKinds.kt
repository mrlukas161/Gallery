package org.fossify.gallery.sync

// Cez čo sa PC dostane k Serveru pre PC: podľa mena sieťového rozhrania telefónu (čistá logika, LinkKindsTest).
//  • USB – zdieľanie pripojenia cez USB (USB tethering: rndis0 / usb0 / ncm0) – kábel, funguje s Wi-Fi aj bez nej;
//    má prednosť (priame spojenie, rýchlejšie a stabilnejšie ako Wi-Fi; pri zapnutej Wi-Fi ide PC na internet cez ňu),
//  • WIFI – telefón je na Wi-Fi (wlan0),
//  • HOTSPOT – telefón robí Wi-Fi hotspot a PC je pripojené naň (ap0, swlan0, wlan1…),
//  • ETHERNET – USB sieťový adaptér, OTHER – iné rozhranie s lokálnou adresou.
// Mobilné dáta (rmnet, ccmni), VPN (tun) a pomocné rozhrania sa nikdy neponúkajú.
enum class LinkKind { USB, WIFI, HOTSPOT, ETHERNET, OTHER }

object LinkKinds {
    private val SKIP = listOf("rmnet", "ccmni", "tun", "dummy", "lo", "v4-", "ipsec", "p2p", "aware", "bt-pan", "clat")

    fun kindOf(ifaceName: String): LinkKind? {
        val n = ifaceName.lowercase()
        return when {
            SKIP.any { n.startsWith(it) } -> null
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> LinkKind.USB
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n == "wlan1" -> LinkKind.HOTSPOT
            n.startsWith("wlan") -> LinkKind.WIFI
            n.startsWith("eth") -> LinkKind.ETHERNET
            else -> LinkKind.OTHER
        }
    }

    // poradie ponuky: USB kábel, Wi-Fi, hotspot, ethernet, ostatné; v rámci druhu podľa poradia nájdenia
    fun <T> sorted(found: List<Pair<LinkKind, T>>): List<Pair<LinkKind, T>> = found.sortedBy { it.first.ordinal }

    // adresa servera pre prehliadač v PC
    fun url(ip: String, port: Int): String = "http://$ip:$port"
}
