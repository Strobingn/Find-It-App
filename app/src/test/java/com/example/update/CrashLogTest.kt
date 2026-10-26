package com.example.update

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashLogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun formatsAReadableReportWithTheStackTrace() {
        val report = CrashLog.format(IllegalStateException("no GoogleMap available"), "main", 0L)
        assertTrue(report.startsWith("Find It crash report"))
        assertTrue(report.contains("Thread: main"))
        assertTrue(report.contains("java.lang.IllegalStateException: no GoogleMap available"))
        assertTrue(report.contains("at com.example.update.CrashLogTest"))
        assertEquals("java.lang.IllegalStateException: no GoogleMap available", CrashLog.summary(report))
    }

    @Test
    fun installedHandlerSavesTheCrashAndStillCallsThePreviousHandler() {
        CrashLog.clear(context)
        assertNull(CrashLog.read(context))
        var previousCalled = false
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> previousCalled = true }
        try {
            CrashLog.install(context)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
            assertTrue(previousCalled)
            assertTrue(CrashLog.read(context)!!.contains("java.lang.RuntimeException: boom"))
            CrashLog.clear(context)
            assertNull(CrashLog.read(context))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
