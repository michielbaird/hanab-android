package live.hanab.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Remembers the login so the app can sign in on start. The password is encrypted with an AES key
 * that lives in the Android Keystore (it never leaves the device's secure hardware/OS store).
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("hanab_credentials", Context.MODE_PRIVATE)

    val username: String get() = prefs.getString("username", "") ?: ""

    /** The saved password, or null if none is saved or it can no longer be decrypted. */
    fun password(): String? {
        val blob = prefs.getString("password", null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            clearPassword() // key lost (e.g. restored backup): fall back to manual login
            null
        }
    }

    fun save(username: String, password: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val enc = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(cipher.iv + enc, Base64.NO_WRAP)
            prefs.edit().putString("username", username).putString("password", blob).apply()
        } catch (e: Exception) {
            prefs.edit().putString("username", username).remove("password").apply()
        }
    }

    fun clearPassword() {
        prefs.edit().remove("password").apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "hanab_login_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
