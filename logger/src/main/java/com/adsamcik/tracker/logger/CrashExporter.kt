package com.adsamcik.tracker.logger

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Utility class for exporting crash data
 */
object CrashExporter {

    /**
     * Export crash data to a specified URI
     * @param context Application context
     * @param uri Directory URI where crash data will be exported
     * @return Number of crashes exported
     */
    suspend fun exportCrashData(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val crashes = LogDatabase.database(context).crashDataDao().getAllOrderedDesc()
        val crashFiles = getCrashFiles(context)
        
        val totalCrashes = crashes.size + crashFiles.size
        
        if (totalCrashes == 0) {
            return@withContext 0
        }
        
        val directory = DocumentFile.fromTreeUri(context, uri)
            ?: throw IOException("Invalid directory URI")
        
        val timestamp = System.currentTimeMillis().formatAsDateTime().replace(":", "-")
        val fileName = "crash_report_$timestamp.txt"
        
        val file = directory.createFile("text/plain", fileName)
            ?: throw IOException("Failed to create crash report file")
        
        context.contentResolver.openOutputStream(file.uri)?.use { outputStream ->
            val writer = outputStream.writer()
            writer.write("Crash Report - Generated on ${System.currentTimeMillis().formatAsDateTime()}\n")
            writer.write("=" .repeat(80) + "\n\n")
            
            // Export database crashes
            crashes.forEachIndexed { index, crash ->
                writer.write("DATABASE CRASH #${index + 1}\n")
                writer.write("-".repeat(40) + "\n")
                writeCrashData(writer, crash)
                writer.write("\n\n")
            }
            
            // Export file-based crashes
            crashFiles.forEachIndexed { index, file ->
                writer.write("FILE CRASH #${index + 1}\n")
                writer.write("-".repeat(40) + "\n")
                writer.write("Source File: ${file.name}\n")
                writer.write(file.readText())
                writer.write("\n\n")
            }
            
            writer.flush()
        }
        
        totalCrashes
    }
    
    private fun writeCrashData(writer: java.io.Writer, crash: CrashData) {
        writer.write("Time: ${crash.timeStamp.formatAsDateTime()}\n")
        writer.write("Exception: ${crash.exceptionName}\n")
        writer.write("Message: ${PiiRedactor.redact(crash.exceptionMessage)}\n")
        writer.write("Thread: ${crash.threadName}\n")
        writer.write("App Version: ${crash.appVersion}\n")
        writer.write("Android Version: ${crash.androidVersion}\n")
        writer.write("Device: ${crash.deviceManufacturer} ${crash.deviceModel}\n")
        writer.write("Memory: ${crash.availableMemory / 1024 / 1024}MB / ${crash.totalMemory / 1024 / 1024}MB\n")
        writer.write("Battery: ${crash.batteryLevel}% ${if (crash.isCharging) "(Charging)" else "(Not Charging)"}\n")
        writer.write("Network: ${crash.networkType}\n")
        writer.write("Background: ${if (crash.isInBackground) "Yes" else "No"}\n")
        crash.cause?.let { writer.write("Cause: ${PiiRedactor.redact(it)}\n") }
        writer.write("\nStack Trace:\n")
        writer.write(PiiRedactor.redact(crash.stackTrace))
    }
    
    /**
     * Clear all crash data from the database and files
     * @param context Application context
     * @return Number of crashes that were cleared
     */
    suspend fun clearCrashData(context: Context): Int = withContext(Dispatchers.IO) {
        val crashDao = LogDatabase.database(context).crashDataDao()
        val databaseCount = crashDao.getCrashCount()
        crashDao.clearAll()
        
        val crashFiles = getCrashFiles(context)
        val fileCount = crashFiles.size
        
        // Delete crash files
        crashFiles.forEach { file ->
            try {
                file.delete()
            } catch (e: Exception) {
                // Log but don't fail the operation
            }
        }
        
        databaseCount + fileCount
    }
    
    /**
     * Get crash count including both database and file-based crashes
     * @param context Application context
     * @return Number of crashes in total
     */
    suspend fun getCrashCount(context: Context): Int = withContext(Dispatchers.IO) {
        val databaseCount = LogDatabase.database(context).crashDataDao().getCrashCount()
        val fileCount = getCrashFiles(context).size
        databaseCount + fileCount
    }
    
    /**
     * Get all crash files from the crashes directory
     */
    private fun getCrashFiles(context: Context): List<File> {
        val crashDir = File(context.filesDir, "crashes")
        return if (crashDir.exists()) {
            crashDir.listFiles { _, name ->
                name.endsWith(".txt") && (name.startsWith("crash_") || name.startsWith("minimal_crash_"))
            }?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }
}
