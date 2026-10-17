package com.example.update

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import java.io.File

object ApkInstaller {
    data class VerifyResult(
        val ok: Boolean,
        val message: String,
        val needsUninstall: Boolean = false,
    )

    fun verifyDownloadedApk(
        context: Context,
        apk: File,
        expectedSha256: String?,
        expectedApplicationId: String = BuildConfig.APPLICATION_ID,
    ): VerifyResult {
        if (!apk.isFile || apk.length() <= 0L) {
            return VerifyResult(false, "Downloaded APK is missing")
        }
        val actual = AppUpdateParse.hexSha256(apk)
        if (expectedSha256 != null && !AppUpdateParse.sha256Matches(expectedSha256, actual)) {
            return VerifyResult(false, "APK sha256 mismatch (expected ${expectedSha256.take(12)}…)")
        }
        val archive = archiveInfo(context.packageManager, apk) ?:
            return VerifyResult(false, "Could not read the downloaded APK package")
        if (archive.packageName != expectedApplicationId) {
            return VerifyResult(false, "APK package ${archive.packageName} does not match $expectedApplicationId")
        }
        val installed = installedInfo(context) ?:
            return VerifyResult(false, "Could not read the installed package")
        // Some Android 9-12 / OEM builds return no signers for a downloaded archive even when it is
        // valid. Only a provable mismatch blocks; unreadable signers fall through to the system
        // installer, which enforces the same-key rule itself.
        if (signatureVerdict(signatureDigests(installed), signatureDigests(archive)) == SignatureVerdict.MISMATCH) {
            return VerifyResult(
                ok = false,
                needsUninstall = true,
                message = "This update is signed with a different key. Export a backup from Tools → App backup, uninstall Find It, install the new APK, then restore.",
            )
        }
        return VerifyResult(true, "APK verified")
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun unknownAppsSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Copy the APK to public Downloads so it survives uninstall during a signing-key switch.
     * Returns a user-visible location, or null if the copy failed.
     */
    fun persistOutsideApp(context: Context, apk: File, displayName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { apk.inputStream().copyTo(it) }
                ?: return@runCatching null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/$displayName"
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dest = File(dir, displayName)
            apk.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }.getOrNull()

    fun browserIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun canRequestInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    enum class SignatureVerdict { MATCH, MISMATCH, UNKNOWN }

    /** UNKNOWN when either side could not be read (platform bug), never treated as a mismatch. */
    fun signatureVerdict(installed: List<ByteArray>, archive: List<ByteArray>): SignatureVerdict {
        if (installed.isEmpty() || archive.isEmpty()) return SignatureVerdict.UNKNOWN
        return if (installed.any { want -> archive.any { it.contentEquals(want) } }) {
            SignatureVerdict.MATCH
        } else {
            SignatureVerdict.MISMATCH
        }
    }

    fun signaturesMatch(installed: PackageInfo, archive: PackageInfo): Boolean =
        signatureVerdict(signatureDigests(installed), signatureDigests(archive)) == SignatureVerdict.MATCH

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES,
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }
    }

    private fun installedInfo(context: Context): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
    }

    private fun signatureDigests(info: PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            val signers = when {
                signing == null -> null
                signing.hasMultipleSigners() -> signing.apkContentsSigners
                else -> signing.signingCertificateHistory
            }
            val digests = signers?.map { it.toByteArray() }.orEmpty()
            if (digests.isNotEmpty()) return digests
            // Fall back to the legacy array (filled by GET_SIGNATURES on archives).
        }
        @Suppress("DEPRECATION")
        return info.signatures?.map { it.toByteArray() }.orEmpty()
    }
}
