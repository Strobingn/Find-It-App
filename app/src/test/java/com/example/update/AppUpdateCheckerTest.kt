package com.example.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppUpdateCheckerTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun parseFinditTagAndNotes() {
        assertEquals(1_000_042, AppUpdateParse.versionCodeFromTag("findit-v1000042"))
        assertTrue(AppUpdateParse.isFinditTag("findit-v1000001"))
        assertFalse(AppUpdateParse.isFinditTag("v2.0"))
        assertEquals(
            "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            AppUpdateParse.sha256FromNotes("app-id: com.aistudio.lidardetector.pkrxtz\nsha256: AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899\n"),
        )
        assertEquals(
            "com.aistudio.lidardetector.pkrxtz",
            AppUpdateParse.appIdFromNotes("app-id: com.aistudio.lidardetector.pkrxtz"),
        )
        assertTrue(
            AppUpdateParse.sha256Matches(
                "aa".repeat(32),
                "AA".repeat(32),
            ),
        )
    }

    @Test
    fun parseReleaseIgnoresNonFinditTags() {
        val json = """
            {"tag_name":"v9","name":"old","body":"app-id: x","assets":[{"id":1,"name":"app.apk","url":"https://example/a"}]}
        """.trimIndent()
        assertNull(GitHubReleaseClient.parseRelease(json))
    }

    @Test
    fun parseReleaseKeepsNewestFinditApk() {
        val json = """
            {
              "tag_name":"findit-v1000003",
              "name":"Find It 2.0.3",
              "body":"What's new\napp-id: com.aistudio.lidardetector.pkrxtz\nsha256: ${"ab".repeat(32)}\n",
              "assets":[
                {"id":9,"name":"notes.txt","url":"https://api.github.com/assets/9"},
                {"id":11,"name":"findit-1000003.apk","url":"https://api.github.com/assets/11"}
              ]
            }
        """.trimIndent()
        val release = requireNotNull(GitHubReleaseClient.parseRelease(json))
        assertEquals(1_000_003, release.versionCode)
        assertEquals("findit-1000003.apk", release.apkName)
        assertEquals("com.aistudio.lidardetector.pkrxtz", release.applicationId)
        assertEquals("ab".repeat(32), release.sha256)
        assertTrue(release.notesForUi.contains("What's new"))
        assertFalse(release.notesForUi.contains("sha256:"))
    }

    @Test
    fun checkPicksNewerVersionCodeOnly() {
        val release = FinditRelease(
            tag = "findit-v1000005",
            versionCode = 1_000_005,
            name = "newer",
            body = "notes",
            apkAssetId = 1,
            apkName = "a.apk",
            apkApiUrl = "https://api.github.com/assets/1",
            sha256 = "ab".repeat(32),
            applicationId = "com.aistudio.lidardetector.pkrxtz",
        )
        val newer = when (
            val result = if (release.versionCode > 3) UpdateCheckResult.Available(release)
            else UpdateCheckResult.UpToDate
        ) {
            is UpdateCheckResult.Available -> result.release.versionCode
            else -> 0
        }
        assertEquals(1_000_005, newer)
        assertEquals(UpdateCheckResult.UpToDate, if (release.versionCode > 2_000_000) UpdateCheckResult.Available(release) else UpdateCheckResult.UpToDate)
    }

    @Test
    fun publicModeDoesNotRequireAToken() {
        assertNull(AppUpdateParse.tokenErrorMessage(404, tokenPresent = false))
        assertNull(AppUpdateParse.tokenErrorMessage(200, tokenPresent = false))
    }

    @Test
    fun tokenErrorsAreDistinctFromNoUpdates() {
        val expired = AppUpdateParse.tokenErrorMessage(401, tokenPresent = true)
        assertTrue(expired is UpdateCheckResult.TokenInvalid)
        assertEquals("Update token expired or invalid", (expired as UpdateCheckResult.TokenInvalid).message)
        assertNull(AppUpdateParse.tokenErrorMessage(200, tokenPresent = true))
    }

    @Test
    fun unauthenticatedRateLimitIsNotATokenError() {
        val limited = AppUpdateParse.tokenErrorMessage(
            status = 403,
            tokenPresent = false,
            body = "API rate limit exceeded",
            remainingHeader = "0",
        )
        assertTrue(limited is UpdateCheckResult.RateLimited)
        assertTrue((limited as UpdateCheckResult.RateLimited).message.contains("60"))
    }

    @Test
    fun parseReleaseReadsPublicBrowserDownloadUrl() {
        val json = """
            {
              "tag_name":"findit-v1000008",
              "name":"public",
              "body":"app-id: com.aistudio.lidardetector.pkrxtz",
              "assets":[{
                "id":3,
                "name":"findit-1000008.apk",
                "url":"https://api.github.com/repos/Strobingn/Find-It-App/releases/assets/3",
                "browser_download_url":"https://github.com/Strobingn/Find-It-App/releases/download/findit-v1000008/findit-1000008.apk"
              }]
            }
        """.trimIndent()
        val release = requireNotNull(GitHubReleaseClient.parseRelease(json))
        assertTrue(release.browserDownloadUrl.contains("/releases/download/"))
    }

    @Test
    fun hexSha256IsStable() {
        val hex = AppUpdateParse.hexSha256("findit".toByteArray())
        assertEquals(64, hex.length)
        assertEquals(hex, AppUpdateParse.hexSha256("findit".toByteArray()))
        val file = folder.newFile("hash.bin")
        file.writeBytes("findit".toByteArray())
        assertEquals(hex, AppUpdateParse.hexSha256(file))
    }

    @Test
    fun backupRejectsZipSlipAndCredentialPrefs() {
        val db = folder.newFolder("db-slip")
        File(db, "find-it.db").writeText("room")
        val prefs = folder.newFolder("prefs-slip")
        File(prefs, "openai_credentials.xml").writeText("secret")
        File(prefs, "terrain_decoder.xml").writeText("<map/>")
        val historic = folder.newFile("index-slip.json")
        historic.writeText("{}")
        val lidar = folder.newFolder("lidar-slip")
        val zip = AppDataBackup.exportZip(db, prefs, historic, lidar)
        val asString = String(zip)
        assertFalse(asString.contains("openai_credentials"))
        assertTrue(asString.contains("terrain_decoder"))

        val escaped = AppDataBackup.safeChild(folder.newFolder("root"), "ok.txt")
        assertTrue(escaped.path.endsWith("ok.txt"))
        try {
            AppDataBackup.safeChild(folder.newFolder("root2"), "../escape.txt")
            throw AssertionError("zip-slip path should be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun appBackupRoundTripsDbPrefsAndIndexes() {
        val db = folder.newFolder("db")
        File(db, "find-it.db").writeText("room")
        val prefs = folder.newFolder("prefs")
        File(prefs, "terrain_decoder.xml").writeText("<map/>")
        val historic = folder.newFile("index.json")
        historic.writeText("""{"records":[]}""")
        val lidar = folder.newFolder("lidar")
        File(lidar, "library.json").writeText("""{"files":[]}""")
        File(lidar, ".sources.json").writeText("""{"https://example.test/huge.laz":"huge.laz"}""")
        File(lidar, "huge.laz").writeText("skip-me")
        File(lidar, "site.las").writeText("also-skip")
        val zip = AppDataBackup.exportZip(db, prefs, historic, lidar)
        val outDb = folder.newFolder("out-db")
        val outPrefs = folder.newFolder("out-prefs")
        val outHist = folder.newFolder("out-hist")
        val outLidar = folder.newFolder("out-lidar")
        val count = AppDataBackup.restoreZip(zip, outDb, outPrefs, outHist, outLidar)
        assertTrue(count >= 3)
        assertEquals("room", File(outDb, "find-it.db").readText())
        assertEquals("<map/>", File(outPrefs, "terrain_decoder.xml").readText())
        assertEquals("""{"records":[]}""", File(outHist, "index.json").readText())
        assertEquals("""{"files":[]}""", File(outLidar, "library.json").readText())
        assertFalse(File(outLidar, "huge.laz").exists())
        assertFalse(File(outLidar, "site.las").exists())
        val refs = File(outLidar, "imported-refs.txt").readText()
        assertTrue(refs.contains("huge.laz"))
        assertTrue(refs.contains("site.las"))
        assertTrue(File(outLidar, ".sources.json").readText().contains("https://example.test/huge.laz"))
        val missing = com.example.data.LazDatasetStore(outLidar).missingSourceDownloads()
        assertEquals(1, missing.size)
        assertEquals("https://example.test/huge.laz", missing.first().first)
        assertEquals(listOf("huge.laz", "site.las"), com.example.data.LazDatasetStore(outLidar).pendingReimportNames())
    }
}
