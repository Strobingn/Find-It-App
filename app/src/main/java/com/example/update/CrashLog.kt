package com.example.update

import android.content.Context
import com.example.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the phone so it can be shared on the next launch, even without Sentry.
 * The handler wraps whatever was installed before (Sentry's, then the system's), so crash
 * reporting and the normal "app stopped" behavior are unchanged.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 32_000

    fun install(context: Context) {
        val file = file(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(format(error, thread.name, System.currentTimeMillis())) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved report, or null when the app hasn't crashed since it was last dismissed. */
    fun read(context: Context): String? = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()?.ifBlank { null }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    internal fun format(error: Throwable, threadName: String, timeMillis: Long): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val header = buildString {
            appendLine("Find It crash report")
            appendLine("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(timeMillis)))
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Thread: $threadName")
            appendLine()
        }
        return (header + trace).take(MAX_CHARS)
    }

    /** First line of the exception, for a short summary on screen. */
    fun summary(report: String): String =
        report.lineSequence().dropWhile { !it.contains("Exception") && !it.contains("Error") }.firstOrNull()?.trim()?.take(160)
            ?: "The app stopped unexpectedly."

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
