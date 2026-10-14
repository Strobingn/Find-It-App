package com.example.update

import java.io.File
import java.io.IOException
import java.nio.file.Files
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The APK download streams with progress, honours cancel, and never shares the 12 s check budget. */
class ApkDownloadTest {
    private val apkBytes = ByteArray(300_000) { (it % 251).toByte() }

    private fun client(code: Int = 200, body: ByteArray = apkBytes): GitHubReleaseClient {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(body.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }).build()
        return GitHubReleaseClient(http = http, downloadHttp = http)
    }

    private fun tempApk(): File = File(Files.createTempDirectory("apk").toFile(), "updates/findit-update.apk")

    @Test
    fun downloadStreamsWithProgress() {
        val dest = tempApk()
        val progress = mutableListOf<Pair<Long, Long>>()
        client().downloadAsset("https://example.invalid/a.apk", dest, onProgress = { d, t -> progress += d to t })
        assertArrayEquals(apkBytes, dest.readBytes())
        assertTrue(progress.size > 2)
        assertEquals(apkBytes.size.toLong() to apkBytes.size.toLong(), progress.last())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun cancelStopsDownloadAndLeavesNoPartialApk() {
        val dest = tempApk()
        var chunks = 0
        try {
            client().downloadAsset(
                "https://example.invalid/a.apk",
                dest,
                onProgress = { _, _ -> chunks++ },
                isActive = { chunks < 2 },
            )
            fail("expected cancel")
        } catch (e: GitHubReleaseClient.DownloadCancelledException) {
            assertEquals("Download cancelled. Tap to try again.", downloadErrorMessage(e))
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun httpErrorIsReportedNotHung() {
        val dest = tempApk()
        try {
            client(code = 404, body = ByteArray(0)).downloadAsset("https://example.invalid/a.apk", dest)
            fail("expected error")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
        assertFalse(dest.exists())
    }

    @Test
    fun downloadClientHasStallTimeoutButNoTotalCallTimeout() {
        val http = GitHubReleaseClient.downloadHttpClient()
        assertEquals(0, http.callTimeoutMillis)
        assertTrue(http.readTimeoutMillis in 1..60_000)
        assertTrue(http.connectTimeoutMillis in 1..30_000)
    }

    @Test
    fun progressLabelShowsPercent() {
        assertEquals(
            "Downloading x.apk… 50% (1.0 of 2.0 MB)",
            AppUpdateDownload.progressLabel("x.apk", 1_048_576, 2_097_152),
        )
    }
}
