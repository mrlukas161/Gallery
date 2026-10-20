package org.fossify.gallery.family

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Trvalé kľúče párov zabalené cez Android Keystore. Pre každý spárovaný telefón vznikne v Keystore
// nevyexportovateľný AES-256 kľúč (alias = PeerEntity.keyAlias), ktorým sa kľúč páru zašifruje.
// V súkromných preferenciách je len zabalený tvar (IV + šifra); bez Keystore toho istého telefónu je
// nepoužiteľný (ani zo zálohy). V family.db nie je kľúč vôbec.
class FamilyKeys(private val prefs: SharedPreferences) {

    // uloží kľúč páru; false = Keystore zlyhal (párovanie sa potom nedokončí)
    fun store(alias: String, pairKey: ByteArray): Boolean = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(WRAP_KEY_BITS)
                .build(),
        )
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, generator.generateKey())
        val wrapped = FamilyBytes.b64(cipher.iv) + SEPARATOR + FamilyBytes.b64(cipher.doFinal(pairKey))
        prefs.edit().putString(alias, wrapped).commit()
    } catch (ignored: GeneralSecurityException) {
        false
    } catch (ignored: ProviderException) {
        false
    }

    // null = kľúč neexistuje alebo ho Keystore nevie rozbaliť
    fun load(alias: String): ByteArray? {
        val wrapped = prefs.getString(alias, null) ?: return null
        val iv = FamilyBytes.unb64(wrapped.substringBefore(SEPARATOR)) ?: return null
        val data = FamilyBytes.unb64(wrapped.substringAfter(SEPARATOR, "")) ?: return null
        return try {
            val key = keyStore().getKey(alias, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(data)
        } catch (ignored: GeneralSecurityException) {
            null
        } catch (ignored: ProviderException) {
            null
        } catch (ignored: IOException) {
            null
        }
    }

    fun delete(alias: String) {
        prefs.edit().remove(alias).commit()
        try {
            keyStore().deleteEntry(alias)
        } catch (ignored: GeneralSecurityException) {
        } catch (ignored: IOException) {
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    companion object {
        const val PREFS = "galeria_family_keys"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val WRAP_KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val SEPARATOR = ':'

        fun aliasFor(deviceId: String): String = "galeriaf_peer_" + FamilyCrypto.publicTag(deviceId)
    }
}
