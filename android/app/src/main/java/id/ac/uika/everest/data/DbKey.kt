package id.ac.uika.everest.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Kunci database SQLCipher: 32 byte acak, disimpan terenkripsi AES-GCM dengan
 * kunci Android Keystore (tidak pernah keluar dari hardware/TEE).
 */
object DbKey {
    private const val ALIAS = "everest_db_key"
    private const val KEYSTORE = "AndroidKeyStore"

    fun get(prefs: Prefs): ByteArray {
        prefs.dbKeyEnc?.let { stored ->
            runCatching { return decrypt(stored) }
            // Kunci Keystore hilang (mis. pengguna menghapus kunci layar): mulai DB baru
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.dbKeyEnc = encrypt(raw)
        return raw
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = c.iv + c.doFinal(plain)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): ByteArray {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, all, 0, 12))
        return c.doFinal(all, 12, all.size - 12)
    }
}
