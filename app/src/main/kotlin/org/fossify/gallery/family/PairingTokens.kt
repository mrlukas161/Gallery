package org.fossify.gallery.family

// Jednorazový párovací token z QR kódu (čistá logika). Platí najviac jeden naraz, 5 minút, a po
// prvom úspešnom spárovaní sa zahodí — QR uložený v histórii skenera je potom bezcenný.
// Po veľa neúspešných pokusoch sa token zahodí tiež (ochrana pred hádaním na sieti).
class PairingTokens(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
) {
    private var token: ByteArray? = null
    private var expiresAt = 0L
    private var failures = 0

    // vráti platný token; nový vznikne len vtedy, keď žiadny platný nie je
    @Synchronized
    fun currentOrIssue(): ByteArray = current() ?: issue()

    @Synchronized
    fun issue(): ByteArray {
        val fresh = FamilyCrypto.randomBytes(FamilyCrypto.TOKEN_BYTES)
        token = fresh
        expiresAt = clock() + ttlMs
        failures = 0
        return fresh.copyOf()
    }

    // platný (nepoužitý, nevypršaný) token alebo null
    @Synchronized
    fun current(): ByteArray? {
        val t = token ?: return null
        if (clock() >= expiresAt) {
            invalidate()
            return null
        }
        return t.copyOf()
    }

    @Synchronized
    fun expiresAt(): Long = if (token == null) 0 else expiresAt

    @Synchronized
    fun invalidate() {
        token = null
        expiresAt = 0
        failures = 0
    }

    // Skúsi pokus s platným tokenom. Ak pokus uspeje (vráti nie-null), token sa spotrebuje, takže ho už
    // nikto druhýkrát nepoužije. Vypršaný alebo už použitý token → null bez volania pokusu.
    @Synchronized
    fun <T : Any> consume(attempt: (ByteArray) -> T?): T? {
        val t = current() ?: return null
        val result = attempt(t)
        if (result != null) {
            invalidate()
        } else if (++failures >= MAX_FAILURES) {
            invalidate()
        }
        return result
    }

    companion object {
        const val TTL_MS = 5 * 60 * 1000L
        const val MAX_FAILURES = 20
    }
}
