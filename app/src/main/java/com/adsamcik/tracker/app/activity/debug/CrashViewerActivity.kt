package com.adsamcik.tracker.app.activity.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsamcik.tracker.logger.CrashData
import com.adsamcik.tracker.logger.LogDatabase
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import com.adsamcik.tracker.shared.utils.activity.ComposeDetailActivity
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Activity for viewing crash logs using Jetpack Compose
 */
internal class CrashViewerActivity : ComposeDetailActivity() {
    private val dispatchers = DefaultDispatchersProvider

    override fun onConfigure(configuration: Configuration) {
        configuration.title = "Crash Viewer"
    }

    @Composable
    override fun Content() {
        var crashes by remember { mutableStateOf<List<CrashDisplayItem>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            val databaseCrashes = withContext(dispatchers.io) {
                LogDatabase.database(this@CrashViewerActivity)
                    .crashDataDao()
                    .getLastOrderedDesc(50)
                    .map { CrashDisplayItem.DatabaseCrash(it) }
            }
            
            val fileCrashes = withContext(dispatchers.io) {
                getFileCrashes()
                    .map { CrashDisplayItem.FileCrash(it) }
            }
            
            crashes = (databaseCrashes + fileCrashes)
                .sortedByDescending { 
                    when (it) {
                        is CrashDisplayItem.DatabaseCrash -> it.crashData.timeStamp
                        is CrashDisplayItem.FileCrash -> it.file.lastModified()
                    }
                }
            isLoading = false
        }

        if (isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Loading crashes...",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else if (crashes.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No crashes found!",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = crashes,
                    key = { crash ->
                        when (crash) {
                            is CrashDisplayItem.DatabaseCrash -> "database-${crash.crashData.id}"
                            is CrashDisplayItem.FileCrash -> crash.file.absolutePath
                        }
                    },
                ) { crash ->
                    when (crash) {
                        is CrashDisplayItem.DatabaseCrash -> CrashItem(crash = crash.crashData)
                        is CrashDisplayItem.FileCrash -> FileCrashItem(file = crash.file)
                    }
                }
            }
        }
    }

    private fun getFileCrashes(): List<java.io.File> {
        val crashDir = java.io.File(filesDir, "crashes")
        return if (crashDir.exists()) {
            crashDir.listFiles { _, name ->
                name.endsWith(".txt") && (name.startsWith("crash_") || name.startsWith("minimal_crash_"))
            }?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }
}

/**
 * Sealed class to represent different types of crash data
 */
sealed class CrashDisplayItem {
    data class DatabaseCrash(val crashData: CrashData) : CrashDisplayItem()
    data class FileCrash(val file: java.io.File) : CrashDisplayItem()
}

@Composable
private fun CrashItem(crash: CrashData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Title
            Text(
                text = "Crash #${crash.id}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // Time
            Text(
                text = crash.timeStamp.formatAsDateTime(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            
            // Exception
            Text(
                text = crash.exceptionName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
            
            // Message
            Text(
                text = crash.exceptionMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp)
            )
            
            // Stack trace (truncated)
            Text(
                text = crash.stackTrace,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            
            // Device info
            Text(
                text = buildString {
                    append("Thread: ${crash.threadName}\n")
                    append("App Version: ${crash.appVersion}\n")
                    append("Android: ${crash.androidVersion}\n")
                    append("Device: ${crash.deviceManufacturer} ${crash.deviceModel}\n")
                    append("Memory: ${crash.availableMemory / 1024 / 1024}MB / ${crash.totalMemory / 1024 / 1024}MB\n")
                    append("Battery: ${crash.batteryLevel}% ${if (crash.isCharging) "(Charging)" else "(Not Charging)"}\n")
                    append("Network: ${crash.networkType}\n")
                    append("Background: ${if (crash.isInBackground) "Yes" else "No"}")
                    crash.cause?.let { append("\nCause: $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun FileCrashItem(file: java.io.File) {
    val locale = LocalLocale.current.platformLocale
    val modifiedAt = remember(file, locale) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).format(Date(file.lastModified()))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Pre-read file content (avoid try/catch wrapping composables)
            val filePreview: String? = try {
                val content = file.readText()
                content.take(500) + if (content.length > 500) "..." else ""
            } catch (e: Exception) {
                null
            }
            // Title
            Text(
                text = if (file.name.startsWith("minimal_")) "Minimal Crash" else "File Crash",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            
            // File info
            Text(
                text = "File: ${file.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp)
            )
            
            Text(
                text = "Modified: $modifiedAt",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 2.dp)
            )
            
            // File content (first few lines or error message)
            if (filePreview != null) {
                Text(
                    text = filePreview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Text(
                    text = "Failed to read file",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
