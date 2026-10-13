package com.example.update

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.BuildConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun rememberAppUpdateController(checkOnLaunch: Boolean = true): AppUpdateController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember {
        mutableStateOf(if (checkOnLaunch) "Checking for updates…" else "Tap to check for updates")
    }
    var available by remember { mutableStateOf<FinditRelease?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var installHelp by remember { mutableStateOf<String?>(null) }
    var installHelpUrl by remember { mutableStateOf<String?>(null) }

    val controller = remember {
        AppUpdateController(
            context = context.applicationContext,
            setStatus = { status = it },
            setAvailable = { available = it },
            setBusy = { busy = it },
            setShowNotes = { showNotes = it },
            setInstallHelp = { installHelp = it },
            setInstallHelpUrl = { installHelpUrl = it },
        )
    }
    controller.status = status
    controller.available = available
    controller.busy = busy
    controller.showNotes = showNotes
    controller.installHelp = installHelp
    controller.installHelpUrl = installHelpUrl

    LaunchedEffect(checkOnLaunch) {
        if (checkOnLaunch) controller.check(scope)
    }
    return controller
}

class AppUpdateController(
    private val context: Context,
    private val setStatus: (String) -> Unit,
    private val setAvailable: (FinditRelease?) -> Unit,
    private val setBusy: (Boolean) -> Unit,
    private val setShowNotes: (Boolean) -> Unit,
    private val setInstallHelp: (String?) -> Unit,
    private val setInstallHelpUrl: (String?) -> Unit = {},
) {
    var status: String = ""
    var available: FinditRelease? = null
    var busy: Boolean = false
    var showNotes: Boolean = false
    var installHelp: String? = null
    var installHelpUrl: String? = null

    fun check(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            setBusy(true)
            setStatus("Checking GitHub releases…")
            val result = try {
                withTimeoutOrNull(AppUpdateChecker.CHECK_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { AppUpdateChecker.check() }
                } ?: UpdateCheckResult.Failed("Update check timed out. Check your connection and try again.")
            } catch (error: CancellationException) {
                setBusy(false)
                setStatus("Update check cancelled. Tap to try again.")
                throw error
            } catch (error: Throwable) {
                UpdateCheckResult.Failed(error.message ?: "Update check failed")
            } finally {
                setBusy(false)
            }
            when (result) {
                is UpdateCheckResult.Available -> {
                    setAvailable(result.release)
                    setStatus(AppUpdateChecker.statusLabel(result))
                    setShowNotes(true)
                }
                else -> {
                    setAvailable(null)
                    setStatus(AppUpdateChecker.statusLabel(result))
                }
            }
        }
    }

    fun downloadAndInstall(scope: kotlinx.coroutines.CoroutineScope, release: FinditRelease) {
        scope.launch {
            setBusy(true)
            setStatus("Downloading ${release.apkName}…")
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val apk = AppUpdateChecker.cacheApk(context)
                    GitHubReleaseClient(
                        repo = BuildConfig.FINDIT_UPDATE_REPO.trim().ifBlank { "Strobingn/Find-It-App" },
                        token = BuildConfig.FINDIT_UPDATE_TOKEN.trim(),
                    ).downloadRelease(release, apk)
                    val verify = ApkInstaller.verifyDownloadedApk(context, apk, release.sha256)
                    verify to apk
                }
            }
            setBusy(false)
            outcome.onFailure { error ->
                val message = error.message.orEmpty()
                setStatus(
                    if (message.contains("expired or invalid", ignoreCase = true)) {
                        "Update token expired or invalid"
                    } else if (message.contains("rate limit", ignoreCase = true)) {
                        AppUpdateParse.RATE_LIMIT_MESSAGE
                    } else {
                        message.ifBlank { "Download failed" }
                    },
                )
            }.onSuccess { (verify, apk) ->
                if (!verify.ok) {
                    setStatus(verify.message)
                    if (verify.needsUninstall) {
                        val saved = ApkInstaller.persistOutsideApp(context, apk, release.apkName)
                        val url = release.browserDownloadUrl.ifBlank { null }
                        setInstallHelpUrl(url)
                        setInstallHelp(
                            buildString {
                                append(verify.message)
                                if (saved != null) {
                                    append(" The APK was also saved to ").append(saved)
                                    append(" so it remains after uninstall.")
                                }
                                if (url != null) {
                                    append(" You can also download ").append(url)
                                    append(" after uninstall.")
                                }
                            },
                        )
                    }
                    return@onSuccess
                }
                if (!ApkInstaller.canRequestInstalls(context)) {
                    setInstallHelp("Allow Find It to install unknown apps, then tap Install again.")
                    context.startActivity(ApkInstaller.unknownAppsSettingsIntent(context))
                    return@onSuccess
                }
                setStatus("Launching system installer…")
                context.startActivity(ApkInstaller.installIntent(context, apk))
            }
        }
    }

    fun dismissNotes() = setShowNotes(false)
    fun dismissHelp() {
        setInstallHelp(null)
        setInstallHelpUrl(null)
    }
    fun report(message: String) = setStatus(message)
}

@Composable
fun AppUpdateButtons(
    controller: AppUpdateController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val backupCreate = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = AppDataBackup.exportZip(
                databaseDir = File(context.applicationInfo.dataDir, "databases"),
                sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs"),
                historicIndex = File(context.filesDir, "historic-map-archive/index.json"),
                lidarDir = File(context.filesDir, "lidar"),
            )
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            controller.report("Backup saved")
        }.onFailure {
            controller.report("Backup failed: ${it.message}")
        }
    }
    val backupOpen = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read backup")
            val count = AppDataBackup.restoreZip(
                bytes = bytes,
                databaseDir = File(context.applicationInfo.dataDir, "databases"),
                sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs"),
                historicDir = File(context.filesDir, "historic-map-archive"),
                lidarDir = File(context.filesDir, "lidar"),
                beforeReplace = { com.example.data.local.AppDatabase.closeInstance() },
            )
            controller.report("Restored $count file(s). Restart Find It.")
        }.onFailure {
            controller.report("Restore failed: ${it.message}")
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Installed: " + AppUpdateChecker.installedBuildLabel(),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("tool_installed_build"),
        )
        TextButton(
            onClick = { controller.check(scope) },
            enabled = !controller.busy,
            modifier = Modifier.testTag("tool_check_for_updates"),
        ) { Text("Check for updates") }
        TextButton(
            onClick = { backupCreate.launch("findit-backup.zip") },
            modifier = Modifier.testTag("tool_app_backup_export"),
        ) { Text("Export app backup") }
        TextButton(
            onClick = { backupOpen.launch(arrayOf("application/zip", "*/*")) },
            modifier = Modifier.testTag("tool_app_backup_restore"),
        ) { Text("Restore app backup") }
        controller.available?.let { release ->
            TextButton(
                onClick = { controller.downloadAndInstall(scope, release) },
                enabled = !controller.busy,
                modifier = Modifier.testTag("tool_install_update"),
            ) { Text("Download and install ${release.name}") }
        }
    }

    if (controller.showNotes && controller.available != null) {
        val release = controller.available!!
        AlertDialog(
            onDismissRequest = { controller.dismissNotes() },
            title = { Text("Update ${release.name}") },
            text = {
                Text(
                    buildString {
                        append("Current: ").append(AppUpdateChecker.installedBuildLabel()).append('\n')
                        append("Available: ").append(AppUpdateChecker.buildLabel(release.name, release.versionCode)).append('\n')
                        append(release.notesForUi.ifBlank { "Newer Find It APK from GitHub Releases." })
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.dismissNotes()
                    controller.downloadAndInstall(scope, release)
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { controller.dismissNotes() }) { Text("Later") }
            },
        )
    }
    controller.installHelp?.let { help ->
        AlertDialog(
            onDismissRequest = { controller.dismissHelp() },
            title = { Text("Install help") },
            text = { Text(help) },
            confirmButton = {
                TextButton(onClick = { controller.dismissHelp() }) { Text("OK") }
            },
            dismissButton = {
                val url = controller.installHelpUrl
                if (!url.isNullOrBlank()) {
                    TextButton(onClick = {
                        context.startActivity(ApkInstaller.browserIntent(url))
                    }) { Text("Open APK download") }
                }
            },
        )
    }
}

@Suppress("unused")
fun shareBackupUri(context: Context, zip: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip).let { uri ->
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
