package org.fossify.gallery.family

// Adresa servera druhého telefónu (IP alebo názov + port).
data class FamilyEndpoint(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"

    companion object {
        private const val MAX_PORT = 65535

        // „192.168.1.5:8090“ → endpoint; null = neplatný zápis
        fun parse(text: String?): FamilyEndpoint? {
            val t = text?.trim().orEmpty()
            val colon = t.lastIndexOf(':')
            if (colon <= 0) return null
            val port = t.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
            return FamilyEndpoint(t.substring(0, colon), port)
        }
    }
}
