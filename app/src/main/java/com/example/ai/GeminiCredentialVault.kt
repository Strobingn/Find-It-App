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

/** Stores the user-provided Gemini key encrypted by an Android Keystore key. */
internal object GeminiCredentialVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "findit_gemini_api_key"
    private const val PREFS_NAME = "gemini_credentials"
    private const val PREF_ENCRYPTED_KEY = "encrypted_api_key"
    private const val PREF_IV = "encrypted_api_key_iv"
    private const val LEGACY_PREF_KEY = "api_key"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun read(context: Context, sanitize: (String?) -> String): String {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(PREF_ENCRYPTED_KEY, null)
        val iv = prefs.getString(PREF_IV, null)
        if (!encrypted.isNullOrBlank() && !iv.isNullOrBlank()) {
            return runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    keyStoreKey(),
                    GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
                )
                sanitize(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8))
            }.getOrElse {
                prefs.edit().remove(PREF_ENCRYPTED_KEY).remove(PREF_IV).apply()
                ""
            }
        }

        // One-time migration for builds that stored the key as plaintext SharedPreferences.
        val legacy = sanitize(prefs.getString(LEGACY_PREF_KEY, null))
        if (legacy.isNotBlank() && write(appContext, legacy)) {
            prefs.edit().remove(LEGACY_PREF_KEY).apply()
            return legacy
        }
        return ""
    }

    fun write(context: Context, value: String): Boolean {
        val appContext = context.applicationContext
        return runCatching {
            val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyStoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_ENCRYPTED_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .remove(LEGACY_PREF_KEY)
                .apply()
            true
        }.getOrDefault(false)
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_ENCRYPTED_KEY)
            .remove(PREF_IV)
            .remove(LEGACY_PREF_KEY)
            .apply()
    }

    private fun keyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
        }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }
}
