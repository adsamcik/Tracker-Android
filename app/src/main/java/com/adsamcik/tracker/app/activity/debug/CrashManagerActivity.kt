package com.adsamcik.tracker.app.activity.debug

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.logger.CrashExporter
import com.adsamcik.tracker.shared.utils.activity.ComposeDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity for managing crash data (export/clear) using Jetpack Compose
 */
class CrashManagerActivity : ComposeDetailActivity() {

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { exportCrashes(it) }
    }

    private var exportCrashes: (Uri) -> Unit = {}

    override fun onConfigure(configuration: Configuration) {
        configuration.title = "Crash Manager"
    }

    @Composable
    override fun Content() {
        var crashCount by remember { mutableStateOf(0) }
        var isLoading by remember { mutableStateOf(true) }
        var isExporting by remember { mutableStateOf(false) }
        var isClearing by remember { mutableStateOf(false) }
        var showClearDialog by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        // Set up export function
        exportCrashes = { uri ->
            scope.launch {
                isExporting = true
                try {
                    val exportedCount = withContext(Dispatchers.IO) {
                        CrashExporter.exportCrashData(this@CrashManagerActivity, uri)
                    }
                    Toast.makeText(
                        this@CrashManagerActivity,
                        "Exported $exportedCount crashes",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@CrashManagerActivity,
                        "Export failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    isExporting = false
                }
            }
        }

        LaunchedEffect(Unit) {
            crashCount = withContext(Dispatchers.IO) {
                CrashExporter.getCrashCount(this@CrashManagerActivity)
            }
            isLoading = false
        }

        if (isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Loading crash data...")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Crash Statistics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Total crashes: $crashCount",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (crashCount > 0) {
                            Text(
                                text = "These crashes are stored locally and can be exported for analysis.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // Export Button
                Button(
                    onClick = {
                        if (crashCount > 0) {
                            exportLauncher.launch(null)
                        } else {
                            Toast.makeText(
                                this@CrashManagerActivity,
                                "No crashes to export",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = crashCount > 0 && !isExporting && !isClearing
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Export"
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = if (isExporting) "Exporting..." else "Export Crashes")
                }

                // Clear Button
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = crashCount > 0 && !isExporting && !isClearing,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    if (isClearing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear"
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = if (isClearing) "Clearing..." else "Clear All Crashes")
                }

                // Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "ℹ️ Information",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• Crashes are automatically captured when the app unexpectedly terminates\n" +
                                    "• Export creates a detailed text file with stack traces and device information\n" +
                                    "• Clearing removes all crash data from local storage\n" +
                                    "• This information helps developers identify and fix issues",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Clear confirmation dialog
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning"
                    )
                },
                title = {
                    Text(text = "Clear All Crashes")
                },
                text = {
                    Text(text = "Are you sure you want to clear all crash data? This action cannot be undone.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            scope.launch {
                                isClearing = true
                                try {
                                    val clearedCount = withContext(Dispatchers.IO) {
                                        CrashExporter.clearCrashData(this@CrashManagerActivity)
                                    }
                                    crashCount = 0
                                    Toast.makeText(
                                        this@CrashManagerActivity,
                                        "Cleared $clearedCount crashes",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        this@CrashManagerActivity,
                                        "Clear failed: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    isClearing = false
                                }
                            }
                        }
                    ) {
                        Text(text = "Clear")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showClearDialog = false }
                    ) {
                        Text(text = "Cancel")
                    }
                }
            )
        }
    }
}
