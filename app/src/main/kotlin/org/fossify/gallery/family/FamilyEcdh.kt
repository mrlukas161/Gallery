package org.fossify.gallery.family

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

// ECDH na krivke P-256 (secp256r1, dostupná na všetkých API od minSdk), verejný kľúč v tvare X.509.
// Čistá logika bez Android tried.
object FamilyEcdh {
    private const val EC_CURVE = "secp256r1"
    private const val EC_FIELD_BITS = 256

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(EC_CURVE), FamilyCrypto.random)
        return generator.generateKeyPair()
    }

    fun encodePublicKey(key: PublicKey): ByteArray = key.encoded

    // null = nie je to platný verejný kľúč P-256
    fun decodePublicKey(bytes: ByteArray): PublicKey? {
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        } catch (ignored: GeneralSecurityException) {
            null
        } catch (ignored: IllegalArgumentException) {
            null
        }
        return (key as? ECPublicKey)?.takeIf { it.params.curve.field.fieldSize == EC_FIELD_BITS }
    }

    // Zdieľané tajomstvo ECDH; null pri neplatnom kľúči druhej strany.
    fun agree(privateKey: PrivateKey, peerPublic: PublicKey): ByteArray? = try {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublic, true)
        agreement.generateSecret()
    } catch (ignored: GeneralSecurityException) {
        null
    } catch (ignored: IllegalStateException) {
        null
    }
}
