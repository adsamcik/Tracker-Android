package com.adsamcik.tracker.app.settings.components

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.impexp.format.FormatRegistry

/**
 * Dialog for selecting export format with clear descriptions and GPX as recommended default.
 * Implements Apple-style progressive disclosure: single entry point with format picker.
 */
@Composable
fun ExportFormatDialog(
    onDismiss: () -> Unit,
    onFormatSelected: (ExportFormat) -> Unit
) {
    var pendingFormat by remember { mutableStateOf<ExportFormat?>(null) }

    pendingFormat?.let { format ->
        ExportSensitivityDialog(
            format = format,
            onDismiss = { pendingFormat = null },
            onConfirm = {
                pendingFormat = null
                onFormatSelected(format)
                onDismiss()
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_format_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                ExportFormat.supportedExportFormats().forEachIndexed { index, format ->
                    if (index > 0) {
                        HorizontalDivider()
                    }
                    ExportFormatOption(
                        name = stringResource(format.displayNameRes),
                        description = stringResource(format.descriptionRes),
                        icon = format.icon,
                        onClick = {
                            pendingFormat = format
                        }
                    )
                }
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
private fun ExportSensitivityDialog(
    format: ExportFormat,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val message = stringResource(format.sensitivityMessageRes)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_sensitivity_dialog_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.export_sensitivity_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
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
enum class ExportFormat(
    val formatId: String,
    @StringRes val displayNameRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val sensitivityMessageRes: Int,
) {
    GPX(
        formatId = "gpx",
        displayNameRes = R.string.export_format_gpx_name,
        descriptionRes = R.string.export_format_gpx_desc,
        sensitivityMessageRes = R.string.export_sensitivity_gpx_message,
    ),
    KML(
        formatId = "kml",
        displayNameRes = R.string.export_format_kml_name,
        descriptionRes = R.string.export_format_kml_desc,
        sensitivityMessageRes = R.string.export_sensitivity_kml_message,
    ),
    JSON(
        formatId = "json",
        displayNameRes = R.string.export_format_json_name,
        descriptionRes = R.string.export_format_json_desc,
        sensitivityMessageRes = R.string.export_sensitivity_json_message,
    ),
    DATABASE(
        formatId = "db",
        displayNameRes = R.string.export_format_db_name,
        descriptionRes = R.string.export_format_db_desc,
        sensitivityMessageRes = R.string.export_sensitivity_db_message,
    );

    companion object {
        fun fromFormatId(formatId: String): ExportFormat? =
            values().firstOrNull { it.formatId == formatId }

        fun supportedExportFormats(): List<ExportFormat> =
            FormatRegistry.allExportFormats().mapNotNull { fromFormatId(it.id) }
    }
}

private val ExportFormat.icon: ImageVector
    get() = when (this) {
        ExportFormat.GPX -> Icons.Default.Route
        ExportFormat.KML -> Icons.Default.Map
        ExportFormat.JSON -> Icons.Default.Storage
        ExportFormat.DATABASE -> Icons.Default.Storage
    }

/**
 * Launch export activity for the selected format.
 */
fun launchExportActivity(context: Context, format: ExportFormat) {
    val exporterClass = requireNotNull(FormatRegistry.exporterFor(format.formatId)) {
        "No exporter registered for ${format.formatId}"
    }.javaClass
    
    context.startActivity(
        Intent(
            context,
            com.adsamcik.tracker.impexp.exporter.activity.ImportExportComposeActivity::class.java
        ).apply {
            putExtra(com.adsamcik.tracker.impexp.exporter.activity.ImportExportComposeActivity.EXPORTER_KEY, exporterClass)
        }
    )
}
