package org.fossify.gallery.family

// Obsah párovacieho QR kódu (čistá logika, bez android.net.Uri):
//   galeriaplus://family/pair?v=1&id=<deviceId>&name=<názov>&t=<token>&ip=<adresa>&port=<port>
// Token je 256-bitový jednorazový (PairingTokens), Base64url bez paddingu. Trvalý kľúč páru v QR nie je.
class PairUri(
    val deviceId: String,
    val name: String,
    val token: ByteArray,
    val host: String,
    val port: Int,
) {
    fun toUri(): String = PREFIX + KvCodec.encode(
        linkedMapOf(
            "v" to VERSION,
            "id" to deviceId,
            "name" to name,
            "t" to FamilyBytes.b64(token),
            "ip" to host,
            "port" to port.toString(),
        ),
    )

    companion object {
        const val SCHEME = "galeriaplus"
        const val HOST = "family"
        const val PATH = "/pair"
        const val VERSION = "1"
        const val MAX_NAME = 40
        private const val PREFIX = "$SCHEME://$HOST$PATH?"
        private const val MAX_PORT = 65535
        private const val MAX_HOST = 253
        private val DEVICE_ID = Regex("[A-Za-z0-9-]{1,${FamilyWire.MAX_DEVICE_ID}}")
        private val HOST_CHARS = Regex("[A-Za-z0-9.:\\-]+")

        // null = nie je to platný párovací odkaz Galérie+ (iná appka, iná verzia, poškodený token…)
        fun parse(text: String): PairUri? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
            val query = KvCodec.decode(trimmed.substring(PREFIX.length).substringBefore('#'))
            return query?.takeIf { it["v"] == VERSION }?.let { fromFields(it) }
        }

        private fun fromFields(query: Map<String, String>): PairUri? {
            val id = query["id"]?.takeIf { DEVICE_ID.matches(it) }
            val token = query["t"]?.let { FamilyBytes.unb64(it) }?.takeIf { it.size == FamilyCrypto.TOKEN_BYTES }
            if (id == null || token == null) return null
            val host = query["ip"]?.takeIf { it.length <= MAX_HOST && HOST_CHARS.matches(it) }
            val port = query["port"]?.toIntOrNull()?.takeIf { it in 1..MAX_PORT }
            if (host == null || port == null) return null
            return PairUri(id, cleanName(query["name"].orEmpty()), token, host, port)
        }

        // názov zariadenia: bez riadiacich znakov, skrátený; prázdny = „?“
        fun cleanName(raw: String): String {
            val clean = raw.filter { !it.isISOControl() }.trim().take(MAX_NAME).trim()
            return clean.ifEmpty { "?" }
        }
    }
}
