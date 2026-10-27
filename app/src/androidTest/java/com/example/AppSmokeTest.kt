package com.example

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermals.ThermalWindViewModel
import com.example.ui.AppDestination
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the real app on a device/emulator and opens every screen: each bottom tab, then every
 * Home workspace row. If any screen crashes on open (as the Stands map did), the instrumentation
 * process dies and this test fails — so CI won't build an APK from it.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AppSmokeTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun grantPermissions() {
        // Granting up front keeps system permission dialogs from covering the app mid-test.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.forEach { runCatching { instrumentation.uiAutomation.grantRuntimePermission(pkg, it) } }
    }

    /** Real time for map SDKs, network and first frames to run — crashes surface here. */
    private fun settle(ms: Long = 5_000) = Thread.sleep(ms)

    private fun assertAppAlive() {
        rule.onNodeWithTag("main_scaffold").assertExists()
    }

    @Test
    fun everyScreenOpensWithoutCrashing() {
        // A stand location, so Thermals / Wind / Stands run their map + camera code paths.
        val thermal = ViewModelProvider(rule.activity)[ThermalWindViewModel::class.java]
        rule.runOnUiThread {
            thermal.setLatitude("42.75")
            thermal.setLongitude("-75.50")
        }

        // The app opens on a landing screen; the tab bar lives in the workspace behind it.
        rule.onNodeWithTag("open_workspace").performScrollTo().performClick()
        settle(2_000)
        assertAppAlive()

        AppDestination.primaryDestinations.forEach { dest ->
            rule.onNodeWithTag("nav_${dest.name.lowercase()}").performClick()
            settle()
            assertAppAlive()
        }

        AppDestination.secondaryDestinations.forEach { dest ->
            rule.onNodeWithTag("nav_home").performClick()
            settle(1_500)
            val tag = "home_card_${dest.name.lowercase()}"
            rule.onNodeWithTag("home_hub").performScrollToNode(hasTestTag(tag))
            rule.onNodeWithTag(tag).performClick()
            settle()
            assertAppAlive()
            rule.onNodeWithTag("nav_back_home").performClick()
            settle(1_000)
        }

        // Home → Offline AI opens the Gemma download page (DownloadManager-backed state).
        rule.onNodeWithTag("nav_home").performClick()
        settle(1_500)
        rule.onNodeWithTag("home_hub").performScrollToNode(hasTestTag("home_offline_ai"))
        rule.onNodeWithTag("home_offline_ai").performClick()
        settle(2_000)
        rule.onNodeWithTag("offline_model_gemma4_e2b").assertExists()
        rule.onNodeWithTag("offline_model_gemma4_e4b").assertExists()
        rule.onNodeWithTag("sheet_done").performClick()
        settle(1_000)
        assertAppAlive()

        // Back to Stands once more after everything else, as a user switching tabs would.
        rule.onNodeWithTag("nav_stands").performClick()
        settle()
        assertAppAlive()
    }
}
