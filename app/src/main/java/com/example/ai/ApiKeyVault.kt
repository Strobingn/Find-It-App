package com.example.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores one user-entered API key encrypted with its own Android Keystore AES key
 * (same scheme as [GeminiCredentialVault], parameterised so new providers don't copy it).
 */
internal class ApiKeyVault(
    private val keyAlias: String,
    private val prefsName: String,
) {
    fun read(context: Context, sanitize: (String?) -> String): String {
        val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(PREF_ENCRYPTED_KEY, null)
        val iv = prefs.getString(PREF_IV, null)
        if (encrypted.isNullOrBlank() || iv.isNullOrBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyStoreKey(), GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            sanitize(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8))
        }.getOrElse {
            prefs.edit().remove(PREF_ENCRYPTED_KEY).remove(PREF_IV).apply()
            ""
        }
    }

    fun write(context: Context, value: String): Boolean = runCatching {
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyStoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_ENCRYPTED_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
        true
    }.getOrDefault(false)

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_ENCRYPTED_KEY)
            .remove(PREF_IV)
            .apply()
    }

    private fun keyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
        }
        return keyStore.getKey(keyAlias, null) as SecretKey
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREF_ENCRYPTED_KEY = "encrypted_api_key"
        const val PREF_IV = "encrypted_api_key_iv"
        const val GCM_TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
