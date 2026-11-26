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

internal data class SecretRead(val password: String, val failed: Boolean)

/** Encrypted storage for saved radio passwords. The key stays in Android Keystore. */
internal class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(profileId: String): SecretRead {
        val encoded = prefs.getString(key(profileId), null) ?: return SecretRead("", false)
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size >= 13) { "encrypted password is truncated" }
            val iv = packed.copyOfRange(0, 12)
            val payload = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            SecretRead(String(cipher.doFinal(payload), StandardCharsets.UTF_8), false)
        } catch (error: Throwable) {
            Log.e(TAG, "password decrypt failed", error)
            SecretRead("", true)
        }
    }

    fun put(profileId: String, password: String): Boolean {
        if (profileId.isBlank()) return false
        if (password.isEmpty()) { remove(profileId); return true }
        return try {
            // AndroidKeyStore with GCM generates the IV itself and rejects caller-provided IVs on encrypt.
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val packed = cipher.iv + cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8))
            prefs.edit().putString(key(profileId), Base64.encodeToString(packed, Base64.NO_WRAP)).commit()
        } catch (error: Throwable) {
            Log.e(TAG, "password encrypt failed", error)
            false
        }
    }

    fun remove(profileId: String) { prefs.edit().remove(key(profileId)).apply() }

    fun clear() { prefs.edit().clear().apply() }

    fun snapshot(): Map<String, String?> = prefs.all
        .filterKeys { it.startsWith("password_") }
        .mapValues { (_, value) -> value as? String }

    fun restore(snapshot: Map<String, String?>): Boolean {
        val edit = prefs.edit().clear()
        snapshot.forEach { (key, value) -> if (value != null) edit.putString(key, value) }
        return edit.commit()
    }

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
