package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens to PNGs for visual review. Skipped unless FINDIT_SCREENSHOTS names an
 * output directory, so it never runs in CI:
 *   FINDIT_SCREENSHOTS=/path/to/out ./gradlew :app:testDebugUnitTest --tests '*ScreenshotCaptureTest'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi", sdk = [35])
class ScreenshotCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File? = System.getenv("FINDIT_SCREENSHOTS")?.let(::File)

    @Before
    fun requireOutputDir() {
        assumeTrue("FINDIT_SCREENSHOTS not set", outDir != null)
        outDir!!.mkdirs()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        // captureToImage() waits on a redraw callback Robolectric never delivers; draw the
        // decor view directly instead.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun scroll() {
        // Start above the tab bar so the gesture lands on the scrolling list.
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.75f, endY = height * 0.15f) }
        compose.waitForIdle()
    }

    private fun viewModel() = HillshadeViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun landing() {
        val vm = viewModel()
        compose.setContent { MyApplicationTheme(darkTheme = true) { FindItAppRoot(vm) } }
        shot("01-landing")
        scroll()
        shot("02-landing-scrolled")
    }

    @Test
    fun workspaceTabs() {
        val vm = viewModel()
        compose.setContent { MyApplicationTheme(darkTheme = true) { MainScreen(vm) } }
        shot("03-home-hub")
        scroll()
        shot("04-home-hub-scrolled")
        scroll()
        shot("04b-home-hub-scrolled-more")
        for (tab in listOf("field", "thermals", "ai", "library")) {
            runCatching {
                compose.onNodeWithTag("nav_$tab").performClick()
                shot("05-tab-$tab")
            }.onFailure { println("screenshot $tab failed: $it") }
        }
        runCatching {
            compose.onNodeWithTag("nav_home").performClick()
            compose.onNodeWithText("Tools").performClick()
            shot("06-secondary-tools")
        }.onFailure { println("screenshot tools failed: $it") }
    }
}
