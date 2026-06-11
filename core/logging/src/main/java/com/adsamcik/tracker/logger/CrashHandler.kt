package com.adsamcik.tracker.logger

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Handles application crashes and stores them safely
 * Uses file-based storage as fallback to ensure crash data is preserved
 */
class CrashHandler(private val application: Application) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    // Uses a raw Executor instead of coroutines intentionally:
    // storeCrashToDatabase() runs inside uncaughtException() where the coroutine
    // infrastructure may be in a broken or partially-torn-down state. A plain
    // single-thread executor with Future.get(timeout) is the safest way to do
    // bounded-time I/O during a crash. The same executor is reused for the
    // non-crash helpers (migrateCrashesToDatabase, cleanupOldCrashes) for simplicity.
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "CrashHandler"
        private const val CRASH_LOG_SOURCE = "crash"
        private const val CRASH_DIR_NAME = "crashes"
        private const val MAX_CRASH_FILES = 50
        private const val CRASH_TIMEOUT_MS = 2000L

        /**
         * Redacts potential PII (coordinates) from crash messages.
         * Delegates to [PiiRedactor] for centralized redaction.
         */
        internal fun redactPii(message: String): String = PiiRedactor.redact(message)
    }

    private val crashDir: File by lazy {
        File(application.filesDir, CRASH_DIR_NAME).also {
             if (!it.exists()) {
                 it.mkdirs()
             }
        }
    }

    // Remove init block as logic is moved to lazy property


    fun initialize() {
        Thread.setDefaultUncaughtExceptionHandler(this)

        // Clean up old crash files periodically
        cleanupOldCrashes()

        // Try to move file-based crashes to database when app starts normally
        migrateCrashesToDatabase()
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            val sanitizedException = PiiRedactor.redactThrowable(exception)
            Log.e(
                TAG,
                "Uncaught exception in thread ${redactPii(thread.name)}: " +
                        "${exception.javaClass.simpleName}: ${sanitizedException.message.orEmpty()}",
                sanitizedException
            )

            // Export logs to external directory in debug mode for easy access
            if (BuildConfig.DEBUG) {
                DebugCrashLogExporter.exportOnCrash(application, thread, exception)
            }

            // Store crash data with fallback strategy
            val crashData = createCrashDataSafely(thread, exception)

            // Try database first, fallback to file if it fails
            val stored = storeCrashSafely(crashData)

            if (stored) {
                Log.i(TAG, "Crash data stored successfully")
            } else {
                Log.e(TAG, "Failed to store crash data")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in crash handler", PiiRedactor.redactThrowable(e))
            // Even if our crash handler fails, we should try to store minimal info
            storeMinimalCrashInfo(thread, exception)
        } finally {
            // Always call the default handler
            defaultHandler?.uncaughtException(thread, exception)
        }
    }

    private fun createCrashDataSafely(thread: Thread, exception: Throwable): CrashData? {
        return try {
            val context = application.applicationContext

            CrashData(
                exceptionName = exception.javaClass.simpleName,
                exceptionMessage = PiiRedactor.redact(exception.message ?: "No message"),
                stackTrace = PiiRedactor.redact(getStackTraceStringSafely(exception)),
                cause = getCauseSafely(exception)?.let { PiiRedactor.redact(it) },
                threadName = thread.name,
                appVersion = getAppVersionSafely(context),
                androidVersion = Build.VERSION.RELEASE,
                deviceModel = Build.MODEL,
                deviceManufacturer = Build.MANUFACTURER,
                availableMemory = getAvailableMemorySafely(context),
                totalMemory = getTotalMemorySafely(context),
                batteryLevel = getBatteryLevelSafely(context),
                isCharging = isChargingSafely(context),
                networkType = getNetworkTypeSafely(context),
                isInBackground = isAppInBackgroundSafely()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create crash data", PiiRedactor.redactThrowable(e))
            null
        }
    }

    private fun storeCrashSafely(crashData: CrashData?): Boolean {
        if (crashData == null) return false

        // Try database first
        val databaseSuccess = storeCrashToDatabase(crashData)
        if (databaseSuccess) {
            return true
        }

        // Fallback to file storage
        return storeCrashToFile(crashData)
    }

    private fun storeCrashToDatabase(crashData: CrashData): Boolean {
        return try {
            // Use a separate thread with timeout for database operations
            val future = executor.submit<Boolean> {
                try {
                    runBlocking {
                        // Crash handling originates from uncaughtException(), so this path cannot be suspend.
                        // Block briefly here to persist the crash before the process may terminate.
                        val crashDao = LogDatabase.database(application).crashDataDao()
                        crashDao.insert(crashData)

                        // Also log to regular log
                        val logDao = LogDatabase.database(application).genericLogDao()
                        logDao.insert(
                            LogData(
                                message = "Application crashed: ${redactPii(crashData.exceptionMessage)}",
                                source = CRASH_LOG_SOURCE
                            )
                        )
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Database storage failed", PiiRedactor.redactThrowable(e))
                    false
                }
            }

            future.get(CRASH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Database storage timed out or failed", PiiRedactor.redactThrowable(e))
            false
        }
    }

    private fun storeCrashToFile(crashData: CrashData): Boolean {
        return try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
            val crashFile = File(crashDir, "crash_$timestamp.txt")

            FileOutputStream(crashFile).use { fos ->
                PrintWriter(fos).use { writer ->
                    writer.println("CRASH REPORT")
                    writer.println("============")
                    writer.println("Time: ${Date(crashData.timeStamp)}")
                    writer.println("Exception: ${crashData.exceptionName}")
                    writer.println("Message: ${redactPii(crashData.exceptionMessage)}")
                    writer.println("Thread: ${crashData.threadName}")
                    writer.println("App Version: ${crashData.appVersion}")
                    writer.println("Android Version: ${crashData.androidVersion}")
                    writer.println("Device: ${crashData.deviceManufacturer} ${crashData.deviceModel}")
                    writer.println("Memory: ${crashData.availableMemory / 1024 / 1024}MB / ${crashData.totalMemory / 1024 / 1024}MB")
                    writer.println("Battery: ${crashData.batteryLevel}%")
                    writer.println("Network: ${crashData.networkType}")
                    writer.println("Background: ${crashData.isInBackground}")
                    crashData.cause?.let { writer.println("Cause: ${redactPii(it)}") }
                    writer.println()
                    writer.println("STACK TRACE:")
                    writer.println(PiiRedactor.redact(crashData.stackTrace))
                    writer.flush()
                }
            }

            Log.i(TAG, "Crash stored to file: ${crashFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "File storage failed", PiiRedactor.redactThrowable(e))
            false
        }
    }

    private fun storeMinimalCrashInfo(thread: Thread, exception: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
            val crashFile = File(crashDir, "minimal_crash_$timestamp.txt")

            FileOutputStream(crashFile).use { fos ->
                PrintWriter(fos).use { writer ->
                    writer.println("MINIMAL CRASH REPORT")
                    writer.println("====================")
                    writer.println("Time: ${Date()}")
                    writer.println("Thread: ${thread.name}")
                    writer.println("Exception: ${exception.javaClass.simpleName}")
                    writer.println("Message: ${redactPii(exception.message ?: "")}")
                    writer.println("Stack Trace:")
                    writer.println(PiiRedactor.redact(getStackTraceStringSafely(exception)))
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Even minimal crash storage failed", PiiRedactor.redactThrowable(e))
        }
    }

    private fun migrateCrashesToDatabase() {
        executor.execute {
            try {
                val crashFiles = crashDir.listFiles { _, name ->
                    name.startsWith("crash_") && name.endsWith(".txt")
                } ?: return@execute

                val crashDao = LogDatabase.database(application).crashDataDao()

                for (file in crashFiles) {
                    try {
                        // Parse crash file and convert to CrashData
                        val crashData = parseCrashFile(file)
                        if (crashData != null) {
                            runBlocking {
                                // Migration reuses the crash-path DAO writes, which are not invoked from a suspend caller.
                                crashDao.insert(crashData)
                            }
                            file.delete() // Remove file after successful migration
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to migrate crash file: ${redactPii(file.name)}", PiiRedactor.redactThrowable(e))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to migrate crashes to database", PiiRedactor.redactThrowable(e))
            }
        }
    }

    private fun parseCrashFile(file: File): CrashData? {
        // Simple parsing - in production you might want more robust parsing
        return try {
            val lines = file.readLines()
            var exceptionName = "Unknown"
            var exceptionMessage = "Unknown"
            var threadName = "Unknown"
            var appVersion = "Unknown"
            var stackTrace = ""

            var inStackTrace = false
            val stackTraceLines = mutableListOf<String>()

            for (line in lines) {
                when {
                    line.startsWith("Exception: ") -> exceptionName = line.substring(11)
                    line.startsWith("Message: ") -> exceptionMessage = line.substring(9)
                    line.startsWith("Thread: ") -> threadName = line.substring(8)
                    line.startsWith("App Version: ") -> appVersion = line.substring(13)
                    line.startsWith("STACK TRACE:") -> inStackTrace = true
                    inStackTrace -> stackTraceLines.add(line)
                }
            }

            stackTrace = stackTraceLines.joinToString("\n")

            CrashData(
                exceptionName = exceptionName,
                exceptionMessage = redactPii(exceptionMessage),
                stackTrace = redactPii(stackTrace),
                threadName = redactPii(threadName),
                appVersion = appVersion,
                androidVersion = Build.VERSION.RELEASE,
                deviceModel = Build.MODEL,
                deviceManufacturer = Build.MANUFACTURER,
                availableMemory = 0L,
                totalMemory = 0L,
                batteryLevel = -1f,
                isCharging = false,
                networkType = "Unknown",
                isInBackground = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse crash file", PiiRedactor.redactThrowable(e))
            null
        }
    }

    private fun cleanupOldCrashes() {
        executor.execute {
            try {
                val crashFiles = crashDir.listFiles() ?: return@execute

                if (crashFiles.size > MAX_CRASH_FILES) {
                    // Sort by last modified and delete oldest
                    val sortedFiles = crashFiles.sortedBy { it.lastModified() }
                    val filesToDelete = sortedFiles.take(crashFiles.size - MAX_CRASH_FILES)

                    for (file in filesToDelete) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup old crashes", PiiRedactor.redactThrowable(e))
            }
        }
    }

    // Safe utility methods that won't throw exceptions
    private fun getStackTraceStringSafely(throwable: Throwable): String {
        return try {
            PiiRedactor.redactThrowableToString(throwable)
        } catch (e: Exception) {
            "Failed to get stack trace: ${redactPii(e.message.orEmpty())}"
        }
    }

    private fun getCauseSafely(throwable: Throwable): String? {
        return try {
            throwable.cause?.let { "${it.javaClass.simpleName}: ${redactPii(it.message.orEmpty())}" }
        } catch (e: Exception) {
            "Failed to get cause"
        }
    }

    private fun getAppVersionSafely(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun getAvailableMemorySafely(context: Context): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.availMem
        } catch (e: Exception) {
            -1L
        }
    }

    private fun getTotalMemorySafely(context: Context): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                memoryInfo.totalMem
            } else {
                Runtime.getRuntime().totalMemory()
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private fun getBatteryLevelSafely(context: Context): Float {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level != -1 && scale != -1) {
                level / scale.toFloat() * 100
            } else {
                -1f
            }
        } catch (e: Exception) {
            -1f
        }
    }

    private fun isChargingSafely(context: Context): Boolean {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            false
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun getNetworkTypeSafely(context: Context): String {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun isAppInBackgroundSafely(): Boolean {
        return try {
            !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.STARTED
            )
        } catch (e: Exception) {
            false
        }
    }
}
