package com.adsamcik.tracker.app.activity.debug

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.adsamcik.tracker.logger.CrashExporter
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import kotlinx.coroutines.launch

/**
 * Activity for exporting crash data.
 * Follows north star: ComponentActivity + setContent pattern.
 */
class CrashExportActivity : ComponentActivity() {
    
    private val directoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            exportUri = uri
        } else {
            finish()
        }
    }
    
    private var exportUri by mutableStateOf<Uri?>(null)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CrashExportScreen(
                        exportUri = exportUri,
                        onLaunchPicker = { directoryPicker.launch(null) },
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashExportScreen(
    exportUri: Uri?,
    onLaunchPicker: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    
    // Launch picker on first composition
    LaunchedEffect(Unit) {
        onLaunchPicker()
    }
    
    // Export when URI becomes available
    LaunchedEffect(exportUri) {
        exportUri?.let { uri ->
            scope.launch {
                try {
                    val exportedCount = CrashExporter.exportCrashData(context, uri)
                    statusMessage = if (exportedCount > 0) {
                        "Successfully exported $exportedCount crash reports."
                    } else {
                        "No crashes to export."
                    }
                } catch (e: Exception) {
                    statusMessage = "Failed to export crashes: ${e.message}"
                }
            }
        }
    }
    
    // Show status dialog when export completes
    statusMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        )
    }
}
