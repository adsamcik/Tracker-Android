package com.adsamcik.tracker.app.settings.data

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.app.settings.DataSettingsViewModel
import com.adsamcik.tracker.app.settings.DebugSettingsViewModel
import com.adsamcik.tracker.app.settings.components.DialogListPreference
import com.adsamcik.tracker.app.settings.components.ExportFormat
import com.adsamcik.tracker.app.settings.components.ExportFormatDialog
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.settings.components.launchExportActivity
import com.adsamcik.tracker.impexp.format.FormatRegistry
import com.adsamcik.tracker.impexp.importer.DataImport
import java.util.Locale
import kotlinx.coroutines.launch

private val defaultDispatchers = DefaultDispatchersProvider

@Composable
fun DataSettingsScreen() {
    val context = LocalContext.current
    val dataVm: DataSettingsViewModel = hiltViewModel()
    val debugVm: DebugSettingsViewModel = hiltViewModel()

    val uiState by dataVm.uiState.collectAsState()
    val showDeleteDataDialog by debugVm.showDeleteDataDialog.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    var showExportFormatDialog by remember { mutableStateOf(false) }
    val dataImport = remember { DataImport() }
    val importMimeTypes = remember { supportedImportMimeTypes(dataImport) }

    // File picker launcher for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            com.adsamcik.tracker.impexp.importer.DataImporter.import(context, uri)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        // Export section
        item {
            SectionHeader(stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_title))
        }

        item {
            SettingsItem(
                title = stringResource(R.string.settings_export_data_title),
                subtitle = stringResource(R.string.settings_export_data_summary),
                icon = Icons.Default.FileUpload,
                onClick = { showExportFormatDialog = true }
            )
        }

        item {
            SwitchSettingsItem(
                title = stringResource(R.string.settings_incremental_backups_title),
                subtitle = stringResource(R.string.settings_incremental_backups_summary),
                checked = uiState.incrementalBackupsEnabled,
                onCheckedChange = { dataVm.setIncrementalBackupsEnabled(it) }
            )
        }

        item {
            SettingsItem(
                title = stringResource(R.string.settings_reset_export_watermarks_title),
                subtitle = stringResource(R.string.settings_reset_export_watermarks_summary),
                icon = Icons.Default.Refresh,
                onClick = {
                    dataVm.resetExportWatermarks()
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_reset_export_watermarks_done),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }

        // Import section
        item {
            val importFormatNames = supportedImportFormatNames()
            val archiveNames = dataImport.supportedArchiveExtractorExtensions
                .joinToString { it.uppercase(Locale.getDefault()) }
            SettingsItem(
                title = stringResource(com.adsamcik.tracker.impexp.R.string.settings_import_title),
                subtitle = stringResource(
                    com.adsamcik.tracker.impexp.R.string.settings_import_summary,
                    importFormatNames,
                    archiveNames
                ),
                icon = Icons.Default.FileDownload,
                onClick = {
                    importLauncher.launch(importMimeTypes)
                }
            )
        }

        // Auto-cleanup section
        item {
            SectionHeader(stringResource(R.string.settings_data_management_section))
        }

        item {
            SwitchSettingsItem(
                title = stringResource(R.string.settings_auto_cleanup_old_data_title),
                subtitle = stringResource(R.string.settings_auto_cleanup_old_data_summary),
                checked = uiState.autoCleanupEnabled,
                onCheckedChange = { dataVm.setAutoCleanupEnabled(it) }
            )
        }

        item {
            SwitchSettingsItem(
                title = stringResource(R.string.settings_smart_goal_notifications_title),
                subtitle = stringResource(R.string.settings_smart_goal_notifications_summary),
                checked = uiState.smartGoalNotificationsEnabled,
                onCheckedChange = { dataVm.setSmartGoalNotificationsEnabled(it) }
            )
        }

        item {
            val retentionTitles = stringArrayResource(R.array.settings_data_retention_years_titles).toList()
            val retentionValues = stringArrayResource(R.array.settings_data_retention_years_values).toList()
                DialogListPreference(
                    title = stringResource(R.string.settings_data_retention_years_title),
                    currentValue = uiState.dataRetentionYears.toString(),
                    entries = retentionTitles,
                    entryValues = retentionValues,
                    onValueChange = { selectedIndex ->
                        val years = retentionValues.getOrNull(selectedIndex)?.toIntOrNull()
                            ?: return@DialogListPreference
                        dataVm.setDataRetentionYears(years)
                    }
                )
            }

        // Danger zone
        item {
            SectionHeader(stringResource(R.string.settings_data_danger_zone_section))
        }

        item {
            SettingsItem(
                title = stringResource(R.string.settings_remove_all_collected_data_title),
                subtitle = stringResource(R.string.settings_remove_all_collected_data_summary),
                icon = Icons.Default.DeleteForever,
                onClick = {
                    debugVm.showDeleteDataDialog()
                }
            )
        }
    }

    // Delete data confirmation dialog
    if (showDeleteDataDialog) {
        AlertDialog(
            onDismissRequest = { debugVm.hideDeleteDataDialog() },
            title = { Text(stringResource(R.string.settings_remove_all_collected_data_title)) },
            text = { Text(stringResource(com.adsamcik.tracker.shared.base.R.string.alert_confirm, stringResource(R.string.settings_remove_all_collected_data_title).replaceFirstChar { it.lowercase() })) },
            confirmButton = {
                Button(
                    onClick = {
                        debugVm.hideDeleteDataDialog()
                        coroutineScope.launch(defaultDispatchers.io) {
                            com.adsamcik.tracker.shared.base.database.AppDatabase.deleteAllCollectedData(context)
                        }
                        dataVm.resetExportWatermarks()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { debugVm.hideDeleteDataDialog() }) {
                    Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_cancel))
                }
            }
        )
    }

    // Export format dialog
    if (showExportFormatDialog) {
        ExportFormatDialog(
            onDismiss = { showExportFormatDialog = false },
            onFormatSelected = { format ->
                launchExportActivity(context, format)
            }
        )
    }
}

@Composable
private fun supportedImportFormatNames(): String {
    val names = mutableListOf<String>()
    for (descriptor in FormatRegistry.allImportFormats()) {
        names += stringResource(descriptor.displayNameRes)
    }
    return names.joinToString()
}

private fun supportedImportMimeTypes(dataImport: DataImport): Array<String> {
    val fileMimeTypes = FormatRegistry.allImportFormats().map { it.mimeType }
    val archiveMimeTypes = dataImport.supportedArchiveExtractorExtensions.flatMap { extension ->
        when (extension.lowercase(Locale.ROOT)) {
            "zip" -> listOf("application/zip", "application/x-zip-compressed")
            else -> emptyList()
        }
    }
    return (fileMimeTypes + archiveMimeTypes + "*/*")
        .distinct()
        .toTypedArray()
}
