package com.adsamcik.tracker.app.settings.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R

/**
 * Dialog for selecting export format with clear descriptions and GPX as recommended default.
 * Implements Apple-style progressive disclosure: single entry point with format picker.
 */
@Composable
fun ExportFormatDialog(
    onDismiss: () -> Unit,
    onFormatSelected: (ExportFormat) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_format_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                ExportFormatOption(
                    name = stringResource(R.string.export_format_gpx_name),
                    description = stringResource(R.string.export_format_gpx_desc),
                    icon = Icons.Default.Route,
                    onClick = {
                        onFormatSelected(ExportFormat.GPX)
                        onDismiss()
                    }
                )
                
                HorizontalDivider()
                
                ExportFormatOption(
                    name = stringResource(R.string.export_format_kml_name),
                    description = stringResource(R.string.export_format_kml_desc),
                    icon = Icons.Default.Map,
                    onClick = {
                        onFormatSelected(ExportFormat.KML)
                        onDismiss()
                    }
                )
                
                HorizontalDivider()
                
                ExportFormatOption(
                    name = stringResource(R.string.export_format_db_name),
                    description = stringResource(R.string.export_format_db_desc),
                    icon = Icons.Default.Storage,
                    onClick = {
                        onFormatSelected(ExportFormat.DATABASE)
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun ExportFormatOption(
    name: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Export format options with associated exporter classes.
 */
enum class ExportFormat {
    GPX,
    KML,
    DATABASE
}

/**
 * Launch export activity for the selected format.
 */
fun launchExportActivity(context: Context, format: ExportFormat) {
    val exporterClass = when (format) {
        ExportFormat.GPX -> com.adsamcik.tracker.impexp.exporter.GpxExporter::class.java
        ExportFormat.KML -> com.adsamcik.tracker.impexp.exporter.KmlExporter::class.java
        ExportFormat.DATABASE -> com.adsamcik.tracker.impexp.exporter.DatabaseExporter::class.java
    }
    
    context.startActivity(
        Intent(
            context,
            com.adsamcik.tracker.impexp.exporter.activity.ImportExportComposeActivity::class.java
        ).apply {
            putExtra(com.adsamcik.tracker.impexp.exporter.activity.ImportExportComposeActivity.EXPORTER_KEY, exporterClass)
        }
    )
}
