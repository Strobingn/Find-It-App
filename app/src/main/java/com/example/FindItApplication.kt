package com.example

import android.app.Application
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User

/**
 * Process-wide init for Sentry crash + performance tracing.
 * AI agent monitoring uses manual [com.example.ai.SentryAiMonitor] spans (metadata only).
 */
class FindItApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initSentry()
    }

    private fun initSentry() {
        val dsn = BuildConfig.SENTRY_DSN.trim()
        if (dsn.isBlank()) {
            // No DSN in local/CI builds without SENTRY_DSN — keep app fully offline-capable.
            return
        }
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.isSendDefaultPii = false
            // AI spans need traces; keep rate high enough that gen_ai transactions are useful.
            // Lower in high-volume production if needed via build flavor later.
            options.tracesSampleRate = BuildConfig.SENTRY_TRACES_SAMPLE_RATE.coerceIn(0.0, 1.0)
            options.isEnableUserInteractionTracing = true
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.setDiagnosticLevel(SentryLevel.WARNING)
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.setTag("app.surface", "find-it-android")
            options.setTag("ai.instrumentation", "manual-gen_ai-metadata")
        }
        // Anonymous device-stable id only — no name/email/GPS.
        Sentry.setUser(
            User().apply {
                id = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID,
                )?.takeIf { it.isNotBlank() }
            },
        )
    }
}
