package org.fossify.gallery.family

// Ktoré adresy patria do domácej (lokálnej) siete. Čistá logika bez Androidu (LocalNetTest).
//
// Rodinné prepojenie funguje len v lokálnej sieti: párovací odkaz smie ukazovať len na lokálnu IP adresu
// (nie na názov ani verejnú adresu na internete) a server rodinného prepojenia prijíma len spojenia
// z lokálnych adries. Lokálne sú IPv4 10/8, 172.16/12, 192.168/16, 169.254/16 a IPv6 fe80::/10, fc00::/7;
// loopback (127/8, ::1) len tam, kde to výslovne povolíme (ladenie cez `adb forward`).
object LocalNet {
    private const val OCTETS = 4
    private const val MAX_OCTET = 255
    private const val B10 = 10
    private const val B172 = 172
    private const val B192 = 192
    private const val B168 = 168
    private const val B169 = 169
    private const val B254 = 254
    private const val B127 = 127
    private const val PRIVATE_172_FROM = 16
    private const val PRIVATE_172_TO = 31
    private const val MAPPED_PREFIX = "::ffff:"
    private const val HEX = 16
    private const val MAX_GROUP = 4
    private const val MAX_DIGITS = 3
    private const val LINK_LOCAL_MASK = 0xffc0
    private const val LINK_LOCAL = 0xfe80
    private const val UNIQUE_LOCAL_MASK = 0xfe00
    private const val UNIQUE_LOCAL = 0xfc00

    // host = IP literál (IPv4, IPv6 aj v [zátvorkách] a so zónou %wlan0); názov (DNS) nie je lokálny nikdy
    fun isLocal(host: String, allowLoopback: Boolean = false): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%').lowercase()
        if (h.isEmpty()) return false
        if (h.startsWith(MAPPED_PREFIX) && h.contains('.')) return isLocal(h.removePrefix(MAPPED_PREFIX), allowLoopback)
        ipv4(h)?.let { return isLocalV4(it, allowLoopback) }
        return isLocalV6(h, allowLoopback)
    }

    fun ipv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != OCTETS) return null
        val out = IntArray(OCTETS)
        parts.forEachIndexed { i, p ->
            if (p.isEmpty() || p.length > MAX_DIGITS || p.any { !it.isDigit() }) return null
            val v = p.toInt()
            if (v > MAX_OCTET) return null
            out[i] = v
        }
        return out
    }

    private fun isLocalV4(a: IntArray, allowLoopback: Boolean): Boolean = when {
        a[0] == B10 -> true
        a[0] == B172 && a[1] in PRIVATE_172_FROM..PRIVATE_172_TO -> true
        a[0] == B192 && a[1] == B168 -> true
        a[0] == B169 && a[1] == B254 -> true
        a[0] == B127 -> allowLoopback
        else -> false
    }

    private fun isLocalV6(h: String, allowLoopback: Boolean): Boolean {
        val hexOnly = h.all { it.isDigit() || it in 'a'..'f' || it == ':' }
        if (!h.contains(':') || !hexOnly) return false
        if (h == "::1") return allowLoopback
        val first = h.substringBefore(':')
        if (first.isEmpty() || first.length > MAX_GROUP) return false
        val v = first.toInt(HEX)
        // fe80::/10 (link-local) a fc00::/7 (unikátne lokálne)
        return (v and LINK_LOCAL_MASK) == LINK_LOCAL || (v and UNIQUE_LOCAL_MASK) == UNIQUE_LOCAL
    }
}
