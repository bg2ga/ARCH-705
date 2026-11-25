package uk.hamdigital.core

import android.content.Context
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted storage for saved radio passwords. The key stays in Android Keystore. */
internal class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(profileId: String): String {
        val encoded = prefs.getString(key(profileId), null) ?: return ""
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size < 13) return@runCatching ""
            val iv = packed.copyOfRange(0, 12)
            val payload = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        }.onFailure { Log.e(TAG, "password decrypt failed", it) }.getOrDefault("")
    }

    fun put(profileId: String, password: String) {
        if (profileId.isBlank()) return
        if (password.isEmpty()) { remove(profileId); return }
        runCatching {
            // AndroidKeyStore with GCM generates the IV itself and rejects caller-provided IVs on encrypt.
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val payload = cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8))
            val packed = iv + payload
            prefs.edit().putString(key(profileId), Base64.encodeToString(packed, Base64.NO_WRAP)).commit()
        }.onFailure { Log.e(TAG, "password encrypt failed", it) }
    }

    fun remove(profileId: String) { prefs.edit().remove(key(profileId)).apply() }

    fun clear() { prefs.edit().clear().apply() }

    private fun key(profileId: String) = "password_$profileId"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val PREFS = "arch705_radio_secrets"
        const val ALIAS = "arch705-profile-passwords-v1"
        const val TAG = "SecretStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
