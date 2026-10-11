package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.GroundSurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The surface chip must always name the surface actually on screen. A dropped reload used to
 * leave e.g. "Auto lowest" selected over the previous (highest-return) surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GroundModeChipSyncTest {
    @Test
    fun droppedReloadDoesNotChangeSelectedSurface() {
        val vm = HillshadeViewModel(ApplicationProvider.getApplicationContext())
        val before = vm.activeGroundMode.value
        vm.setGroundSurfaceMode(GroundSurfaceMode.AUTO_LOWEST)
        assertEquals(before, vm.activeGroundMode.value)
        vm.setGroundSurfaceMode(GroundSurfaceMode.SURFACE_MODEL)
        assertEquals(before, vm.activeGroundMode.value)
    }
}
