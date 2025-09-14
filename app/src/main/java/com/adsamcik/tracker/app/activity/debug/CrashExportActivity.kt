package com.adsamcik.tracker.app.activity.debug

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.adsamcik.tracker.logger.CrashExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Activity for exporting crash data
 */
class CrashExportActivity : AppCompatActivity(), CoroutineScope {
    
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job
    
    private val directoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            exportCrashes(uri)
        } else {
            finish()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start directory picker immediately
        directoryPicker.launch(null)
    }
    
    @Composable
    private fun StatusDialog(
        message: String,
        onDismiss: () -> Unit
    ) {
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

    private fun showStatusDialog(message: String) {
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@CrashExportActivity))
            var showDialog by mutableStateOf(true)
            
            setContent {
                if (showDialog) {
                    StatusDialog(
                        message = message,
                        onDismiss = {
                            showDialog = false
                            finish()
                        }
                    )
                }
            }
        }
        
        setContentView(composeView)
    }

    private fun exportCrashes(uri: Uri) {
        launch {
            try {
                val exportedCount = CrashExporter.exportCrashData(this@CrashExportActivity, uri)
                
                showStatusDialog(if (exportedCount > 0) {
                    "Successfully exported $exportedCount crash reports."
                } else {
                    "No crashes to export."
                })
                    
            } catch (e: Exception) {
                showStatusDialog("Failed to export crashes: ${e.message}")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
