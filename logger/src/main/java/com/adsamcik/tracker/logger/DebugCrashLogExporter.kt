package com.adsamcik.tracker.logger

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for exporting crash logs to an accessible external directory in debug builds.
 * 
 * This class provides synchronous log export on crash, writing crash data and recent
 * application logs to `getExternalFilesDir(null)/crash_logs/` for easy access during
 * development and debugging.
 * 
 * Only active when [BuildConfig.DEBUG] is true.
 */
object DebugCrashLogExporter {

    private const val TAG = "DebugCrashLogExporter"
    private const val CRASH_LOGS_DIR = "crash_logs"
    private const val MAX_RECENT_LOGS = 100
    private const val MAX_CRASH_FILES = 20

    /**
     * Export crash data and recent logs to external storage.
     * Called synchronously during crash handling - must complete before process dies.
     * 
     * @param context Application context
     * @param thread The thread where the crash occurred
     * @param exception The uncaught exception
     */
    fun exportOnCrash(context: Context, thread: Thread, exception: Throwable) {
        if (!BuildConfig.DEBUG) {
            return
        }

        try {
            val crashLogsDir = getCrashLogsDirectory(context) ?: run {
                Log.e(TAG, "Failed to get crash logs directory")
                return
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val crashFile = File(crashLogsDir, "crash_$timestamp.txt")

            FileOutputStream(crashFile).use { fos ->
                PrintWriter(fos).use { writer ->
                    writeCrashHeader(writer, timestamp)
                    writeExceptionInfo(writer, thread, exception)
                    writeDeviceInfo(writer, context)
                    writeStackTrace(writer, exception)
                    runBlocking { writeRecentLogs(writer, context) }
                    writer.flush()
                }
            }

            Log.i(TAG, "Crash log exported to: ${crashFile.absolutePath}")
            
            // Clean up old crash files to avoid filling storage
            cleanupOldCrashFiles(crashLogsDir)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to export crash log", e)
        }
    }

    private fun getCrashLogsDirectory(context: Context): File? {
        val externalDir = context.getExternalFilesDir(null) ?: return null
        val crashLogsDir = File(externalDir, CRASH_LOGS_DIR)
        
        if (!crashLogsDir.exists() && !crashLogsDir.mkdirs()) {
            Log.e(TAG, "Failed to create crash logs directory")
            return null
        }
        
        return crashLogsDir
    }

    private fun writeCrashHeader(writer: PrintWriter, timestamp: String) {
        writer.println("=" .repeat(80))
        writer.println("DEBUG CRASH LOG EXPORT")
        writer.println("=" .repeat(80))
        writer.println("Generated: $timestamp")
        writer.println()
    }

    private fun writeExceptionInfo(writer: PrintWriter, thread: Thread, exception: Throwable) {
        writer.println("-".repeat(40))
        writer.println("EXCEPTION INFO")
        writer.println("-".repeat(40))
        writer.println("Thread: ${thread.name}")
        writer.println("Exception: ${exception.javaClass.name}")
        writer.println("Message: ${exception.message ?: "No message"}")
        exception.cause?.let { cause ->
            writer.println("Cause: ${cause.javaClass.name}: ${cause.message}")
        }
        writer.println()
    }

    private fun writeDeviceInfo(writer: PrintWriter, context: Context) {
        writer.println("-".repeat(40))
        writer.println("DEVICE INFO")
        writer.println("-".repeat(40))
        writer.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        writer.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            writer.println("App Version: ${packageInfo.versionName} (${packageInfo.longVersionCode})")
        } catch (e: Exception) {
            writer.println("App Version: Unknown")
        }
        writer.println()
    }

    private fun writeStackTrace(writer: PrintWriter, exception: Throwable) {
        writer.println("-".repeat(40))
        writer.println("STACK TRACE")
        writer.println("-".repeat(40))
        
        val stringWriter = StringWriter()
        exception.printStackTrace(PrintWriter(stringWriter))
        writer.println(stringWriter.toString())
        writer.println()
    }

    private suspend fun writeRecentLogs(writer: PrintWriter, context: Context) {
        writer.println("-".repeat(40))
        writer.println("RECENT APPLICATION LOGS (last $MAX_RECENT_LOGS entries)")
        writer.println("-".repeat(40))

        try {
            val recentLogs = try {
                withContext(DefaultDispatchersProvider.io) {
                    withTimeout(1000) {
                        LogDatabase.database(context).genericLogDao().getLastOrderedDesc(MAX_RECENT_LOGS)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                writer.println("Timeout retrieving recent logs")
                null
            } catch (e: Exception) {
                writer.println("Failed to retrieve logs: ${e.message}")
                null
            }

            when {
                recentLogs == null -> Unit
                recentLogs.isEmpty() -> writer.println("No recent logs available")
                else -> {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    recentLogs.reversed().forEach { log ->
                        val time = dateFormat.format(Date(log.timeStamp))
                        writer.println("[$time] [${log.source}] ${log.message}")
                        if (log.data.isNotEmpty()) {
                            writer.println("  Data: ${log.data}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            writer.println("Failed to retrieve logs: ${e.message}")
        }
        writer.println()
    }

    private fun cleanupOldCrashFiles(crashLogsDir: File) {
        try {
            val crashFiles = crashLogsDir.listFiles { _, name ->
                name.startsWith("crash_") && name.endsWith(".txt")
            } ?: return

            if (crashFiles.size > MAX_CRASH_FILES) {
                val sortedFiles = crashFiles.sortedBy { it.lastModified() }
                val filesToDelete = sortedFiles.take(crashFiles.size - MAX_CRASH_FILES)
                
                filesToDelete.forEach { file ->
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete old crash file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old crash files", e)
        }
    }
}
