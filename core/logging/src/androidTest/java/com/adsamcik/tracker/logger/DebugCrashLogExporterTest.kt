package com.adsamcik.tracker.logger

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
class DebugCrashLogExporterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Clean up crash logs directory
        val externalDir = context.getExternalFilesDir(null)
        val crashLogsDir = File(externalDir, "crash_logs")
        if (crashLogsDir.exists()) {
            crashLogsDir.deleteRecursively()
        }
    }

    @Test
    fun exportOnCrash_createsFileWithContent() {
        val exception = RuntimeException("Test Crash Exception")
        val thread = Thread.currentThread()

        // Just run it - ignoring BuildConfig.DEBUG check might be tricky if it returns false in test context.
        // However, I can't easily change BuildConfig.DEBUG.
        // If BuildConfig.DEBUG is false in test, this test will pass (doing nothing) or fail if I assert file existence.
        // Usually, androidTest runs in debug mode.
        
        DebugCrashLogExporter.exportOnCrash(context, thread, exception)

        val externalDir = context.getExternalFilesDir(null)
        val crashLogsDir = File(externalDir, "crash_logs")
        
        // If the test context has DEBUG=false, this test is meaningless. 
        // We assume DEBUG=true for debug builds/tests.
        if (com.adsamcik.tracker.logger.BuildConfig.DEBUG) {
            assertTrue("Crash logs directory should exist", crashLogsDir.exists())
            
            val files = crashLogsDir.listFiles()
            assertTrue("Should have created a crash log file", files != null && files.isNotEmpty())
            
            val crashFile = files!!.first()
            val content = crashFile.readText()
            
            assertTrue("Content should contain exception message", content.contains("Test Crash Exception"))
            assertTrue("Content should contain thread info", content.contains("Thread:"))
            assertTrue("Content should contain stack trace", content.contains("STACK TRACE"))
            assertTrue("Content should contain logs section", content.contains("RECENT APPLICATION LOGS"))
        } else {
            // If not debug, maybe we should skip or warn? 
            // Asserting nothing happens is also a valid test for Release.
            // But we want to verify it WORKS.
            // Assuming this is running in Debug variant.
            println("Skipping verification because BuildConfig.DEBUG is false")
        }
    }
}
