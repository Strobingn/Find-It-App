package com.example.gps

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.gps.ntrip.NtripConfig
import com.example.gps.ntrip.NtripPresets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Precise-GPS settings. The NTRIP password is AES-GCM encrypted with an AndroidKeyStore key. */
data class GpsSettings(
    val useExternalReceiver: Boolean = false,
    val receiverAddress: String = "",
    val receiverName: String = "",
    val ntripEnabled: Boolean = false,
    val presetId: String = NtripPresets.NYSNET.id,
    val host: String = NtripPresets.NYSNET.host,
    val port: Int = NtripPresets.NYSNET.port,
    val mountpoint: String = "",
    val username: String = "",
    val password: String = "",
) {
    fun ntripConfig(): NtripConfig = NtripConfig(host.trim(), port, mountpoint.trim(), username.trim(), password, sendGga = true)
}

class GpsSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("precise_gps", Context.MODE_PRIVATE)

    fun load(): GpsSettings = GpsSettings(
        useExternalReceiver = prefs.getBoolean("external", false),
        receiverAddress = prefs.getString("bt_address", "").orEmpty(),
        receiverName = prefs.getString("bt_name", "").orEmpty(),
        ntripEnabled = prefs.getBoolean("ntrip", false),
        presetId = prefs.getString("preset", NtripPresets.NYSNET.id).orEmpty(),
        host = prefs.getString("host", NtripPresets.NYSNET.host).orEmpty(),
        port = prefs.getInt("port", NtripPresets.NYSNET.port),
        mountpoint = prefs.getString("mount", "").orEmpty(),
        username = prefs.getString("user", "").orEmpty(),
        password = prefs.getString("pass", null)?.let(::decrypt).orEmpty(),
    )

    fun save(settings: GpsSettings) {
        prefs.edit()
            .putBoolean("external", settings.useExternalReceiver)
            .putString("bt_address", settings.receiverAddress)
            .putString("bt_name", settings.receiverName)
            .putBoolean("ntrip", settings.ntripEnabled)
            .putString("preset", settings.presetId)
            .putString("host", settings.host)
            .putInt("port", settings.port)
            .putString("mount", settings.mountpoint)
            .putString("user", settings.username)
            .putString("pass", if (settings.password.isEmpty()) null else encrypt(settings.password))
            .apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(stored: String): String? = runCatching {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val KEY_ALIAS = "precise_gps_ntrip"
    }
}
