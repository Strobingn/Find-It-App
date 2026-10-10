package com.example.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
        val actual = AppUpdateParse.hexSha256(apk.readBytes())
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
        if (!signaturesMatch(installed, archive)) {
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

    fun canRequestInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun signaturesMatch(installed: PackageInfo, archive: PackageInfo): Boolean {
        val left = signatureDigests(installed)
        val right = signatureDigests(archive)
        if (left.isEmpty() || right.isEmpty()) return false
        return left.any { want -> right.any { it.contentEquals(want) } }
    }

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
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
            val signing = info.signingInfo ?: return emptyList()
            val signers = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            return signers?.map { it.toByteArray() }.orEmpty()
        }
        @Suppress("DEPRECATION")
        return info.signatures?.map { it.toByteArray() }.orEmpty()
    }
}
