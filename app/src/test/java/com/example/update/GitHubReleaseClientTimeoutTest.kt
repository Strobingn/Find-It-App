package com.example.update

import com.example.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Fake HTTP responses (real `/releases` JSON shape) — every check must resolve to a terminal state. */
@RunWith(RobolectricTestRunner::class)
class GitHubReleaseClientTimeoutTest {
    private val realPage: String =
        requireNotNull(javaClass.classLoader?.getResource("update/releases_page1.json")).readText()

    private fun fakeClient(
        token: String = "",
        seenAuth: MutableList<String?> = mutableListOf(),
        respond: (page: Int) -> Pair<Int, String>,
    ): GitHubReleaseClient {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            seenAuth += request.header("Authorization")
            val page = request.url.queryParameter("page")?.toInt() ?: 1
            val (code, body) = respond(page)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
        return GitHubReleaseClient(repo = "Strobingn/Find-It-App", token = token, http = http)
    }

    @Test
    fun realReleaseListShapeYieldsNewestFinditRelease() {
        val auth = mutableListOf<String?>()
        val client = fakeClient(seenAuth = auth) { page -> 200 to if (page == 1) realPage else "[]" }
        val result = AppUpdateChecker.check(installedVersionCode = 1_000_000, client = client)
        assertTrue(result is UpdateCheckResult.Failed)
        assertTrue((result as UpdateCheckResult.Failed).message.contains("findit2"))
        assertTrue(auth.all { it == null })
        assertEquals(1, auth.size) // short page ends pagination
    }

    @Test
    fun sameVersionIsUpToDate() {
        val page = """
            [{
              "tag_name":"findit2-v1000678",
              "name":"find it 2",
              "body":"app-id: ${BuildConfig.APPLICATION_ID}\nsha256: ${"ab".repeat(32)}\n",
              "assets":[{
                "id":1,
                "name":"findit2-1000678.apk",
                "url":"https://api.github.com/repos/Strobingn/Find-It-App/releases/assets/1",
                "browser_download_url":"https://example/findit2.apk"
              }]
            }]
        """.trimIndent()
        val client = fakeClient { 200 to page }
        assertEquals(UpdateCheckResult.UpToDate, AppUpdateChecker.check(1_000_678, client))
    }

    @Test
    fun rateLimitIsReported() {
        val client = fakeClient { 403 to """{"message":"API rate limit exceeded for 1.2.3.4."}""" }
        assertTrue(AppUpdateChecker.check(1, client) is UpdateCheckResult.RateLimited)
    }

    @Test
    fun malformedJsonAndServerErrorsFailClearly() {
        val bad = AppUpdateChecker.check(1, fakeClient { 200 to "<html>oops</html>" })
        assertTrue(bad is UpdateCheckResult.Failed)
        val err = AppUpdateChecker.check(1, fakeClient { 500 to "" })
        assertTrue(err is UpdateCheckResult.Failed)
    }

    @Test
    fun fullPagesNeverLoopForever() {
        val fullPage = "[" + List(30) { """{"tag_name":"v$it","assets":[]}""" }.joinToString(",") + "]"
        var calls = 0
        val result = AppUpdateChecker.check(1, fakeClient { calls++; 200 to fullPage })
        assertTrue(result is UpdateCheckResult.Failed)
        assertTrue(calls <= 5)
    }

    @Test
    fun networkExceptionBecomesFailure() {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw IOException("timeout") })
            .build()
        val result = AppUpdateChecker.check(1, GitHubReleaseClient(http = http))
        assertTrue(result is UpdateCheckResult.Failed)
        assertTrue((result as UpdateCheckResult.Failed).message.contains("timeout"))
    }

    @Test
    fun defaultClientHasTimeouts() {
        val http = GitHubReleaseClient.defaultHttpClient()
        assertEquals(TimeUnit.SECONDS.toMillis(5), http.connectTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(8), http.readTimeoutMillis.toLong())
        assertTrue(http.callTimeoutMillis in 1..AppUpdateChecker.CHECK_TIMEOUT_MS.toInt())
    }

    @Test
    fun emptyTokenSendsNoAuthorizationHeader() {
        val auth = mutableListOf<String?>()
        AppUpdateChecker.check(1, fakeClient(token = "  ".trim(), seenAuth = auth) { 200 to realPage })
        assertNull(auth.single())
    }
}
