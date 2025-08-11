package com.adsamcik.tracker.app.activity.debug

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.adsamcik.tracker.logger.CrashExporter
import com.adsamcik.tracker.shared.utils.dialog.alertDialog
import com.afollestad.materialdialogs.MaterialDialog
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
    
    private fun exportCrashes(uri: Uri) {
        launch {
            try {
                val exportedCount = CrashExporter.exportCrashData(this@CrashExportActivity, uri)
                
                MaterialDialog(this@CrashExportActivity)
                    .message(text = if (exportedCount > 0) {
                        "Successfully exported $exportedCount crash reports."
                    } else {
                        "No crashes to export."
                    })
                    .positiveButton(text = "OK") {
                        finish()
                    }
                    .show()
                    
            } catch (e: Exception) {
                MaterialDialog(this@CrashExportActivity)
                    .message(text = "Failed to export crashes: ${e.message}")
                    .positiveButton(text = "OK") {
                        finish()
                    }
                    .show()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
