package com.example.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.MainActivity
import com.example.data.LazDownloadManager
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Downloads LiDAR tiles outside the UI's lifetime.
 *
 * Tiles are routinely hundreds of megabytes and occasionally gigabytes, so a transfer has to
 * survive the user leaving the Import tab or backgrounding the app entirely - which is exactly
 * what a composition-scoped coroutine could not do. Transfers run one at a time: several
 * concurrent multi-gigabyte streams over a mobile connection finish no sooner in aggregate and
 * make each individual tile take far longer to become usable.
 *
 * [LazDownloadManager] already writes to a resumable partial file, so a transfer interrupted by
 * process death continues from where it stopped rather than starting over.
 */
class LazDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloader = LazDownloadManager()
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_URL)?.let(LazDownloadQueue::requestCancel)
            ACTION_ENQUEUE -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                if (!url.isNullOrBlank()) LazDownloadQueue.enqueue(url, name.ifBlank { url.substringAfterLast('/') })
            }
        }

        if (!LazDownloadQueue.hasPendingWork()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(LazDownloadQueue.nextQueued()))
        ensureWorker()
        // START_REDELIVER_INTENT would replay enqueue intents the queue already holds; the
        // resumable partial files make a plain restart cheap enough without duplicating entries.
        return START_NOT_STICKY
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            try {
                while (true) {
                    val task = LazDownloadQueue.nextQueued() ?: break
                    runTask(task)
                }
            } finally {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun runTask(task: LazDownloadTask) {
        LazDownloadQueue.markRunning(task.url)
        notify(buildNotification(LazDownloadQueue.taskFor(task.url)))
        var lastNotifiedAt = 0L
        try {
            val file = downloader.download(
                sourceUrl = task.url,
                destinationDirectory = LazDownloadQueue.store(this).directory,
                progress = { downloaded, total ->
                    LazDownloadQueue.markProgress(task.url, downloaded, total)
                    // The transfer reports every few megabytes; throttle the notification so a
                    // fast connection does not spend its time posting updates.
                    val now = System.currentTimeMillis()
                    if (now - lastNotifiedAt >= NOTIFICATION_INTERVAL_MS) {
                        lastNotifiedAt = now
                        notify(buildNotification(LazDownloadQueue.taskFor(task.url)))
                    }
                },
                shouldContinue = { !LazDownloadQueue.isCancelled(task.url) },
            )
            // Recorded before the task is marked done, so anything reacting to completion already
            // sees the provenance and can reuse this file instead of fetching it again.
            LazDownloadQueue.store(this).recordSource(task.url, file)
            LazDownloadQueue.markCompleted(task.url, file)
        } catch (error: Throwable) {
            LazDownloadQueue.markFailed(task.url, error.localizedMessage ?: "Tile download failed")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Tile downloads",
                // Low: a progress bar the user can glance at, never an interruption.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress for LiDAR tile downloads running in the background" },
        )
    }

    private fun buildNotification(task: LazDownloadTask?): Notification {
        val remaining = LazDownloadQueue.tasks.value.count {
            it.state == LazDownloadState.QUEUED || it.state == LazDownloadState.RUNNING
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle(task?.displayName?.takeIf { it.isNotBlank() } ?: "Downloading LiDAR tile")
            .setContentText(progressText(task, remaining))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        val fraction = task?.fraction
        if (fraction != null) {
            builder.setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        task?.url?.let { url ->
            val cancel = PendingIntent.getService(
                this,
                url.hashCode(),
                Intent(this, LazDownloadService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_URL, url),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(null, "Cancel", cancel).build(),
            )
        }
        return builder.build()
    }

    private fun progressText(task: LazDownloadTask?, remaining: Int): String {
        val queued = if (remaining > 1) " · ${remaining - 1} more queued" else ""
        if (task == null) return "Preparing…$queued"
        val fraction = task.fraction
        return if (fraction != null) {
            "${(fraction * 100).toInt()}% of ${formatBytes(task.totalBytes)}$queued"
        } else {
            "${formatBytes(task.downloadedBytes)} downloaded$queued"
        }
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // Silently ignored when the user has not granted notification access; the transfer itself
        // is unaffected.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    companion object {
        private const val CHANNEL_ID = "laz_downloads"
        private const val NOTIFICATION_ID = 4711
        private const val PROGRESS_MAX = 1000
        private const val NOTIFICATION_INTERVAL_MS = 700L
        const val ACTION_ENQUEUE = "com.example.action.ENQUEUE_LAZ_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.action.CANCEL_LAZ_DOWNLOAD"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"

        /** Queues a tile and makes sure the service is running to service it. */
        fun enqueue(context: Context, url: String, displayName: String) {
            val intent = Intent(context, LazDownloadService::class.java)
                .setAction(ACTION_ENQUEUE)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_NAME, displayName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context, url: String) {
            LazDownloadQueue.requestCancel(url)
            runCatching {
                context.startService(
                    Intent(context, LazDownloadService::class.java)
                        .setAction(ACTION_CANCEL)
                        .putExtra(EXTRA_URL, url),
                )
            }
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes <= 0L -> "unknown size"
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes.toDouble() / (1L shl 20))
            else -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / 1024.0)
        }
    }
}
