package com.adsamcik.tracker.app.settings.data

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.DataSettingsViewModel
import com.adsamcik.tracker.app.settings.DataDeletionResult
import com.adsamcik.tracker.app.settings.DebugSettingsViewModel
import com.adsamcik.tracker.app.settings.LegacyDatabaseDeleteResult
import com.adsamcik.tracker.app.settings.LegacyDatabaseExportResult
import com.adsamcik.tracker.app.settings.LegacyDatabaseUiInfo
import com.adsamcik.tracker.app.settings.MigrationBackupUiInfo
import com.adsamcik.tracker.app.settings.components.DialogListPreference
import com.adsamcik.tracker.app.settings.components.ExportFormat
import com.adsamcik.tracker.app.settings.components.ExportFormatDialog
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SettingsItemTone
import com.adsamcik.tracker.app.settings.components.SettingsRowDivider
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.settings.components.launchExportActivity
import com.adsamcik.tracker.impexp.format.FormatRegistry
import com.adsamcik.tracker.impexp.importer.DataImport
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportStatus
import java.text.DateFormat
import java.util.Locale

@Composable
fun DataSettingsScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val dataVm: DataSettingsViewModel = hiltViewModel()
    val debugVm: DebugSettingsViewModel = hiltViewModel()

    val uiState by dataVm.uiState.collectAsStateWithLifecycle()
    val showDeleteDataDialog by debugVm.showDeleteDataDialog.collectAsStateWithLifecycle()

    var showExportFormatDialog by remember { mutableStateOf(false) }
    var showMigrationBackupWarning by remember { mutableStateOf(false) }
    var showLegacyDatabaseDetails by remember { mutableStateOf(false) }
    var showLegacyDatabaseDeleteConfirmation by remember { mutableStateOf(false) }
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
    val migrationBackupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.sqlite3")
    ) { uri ->
        if (uri != null) {
            dataVm.exportMigrationBackup(uri) { result ->
                val message = when (result) {
                    com.adsamcik.tracker.app.settings.MigrationBackupExportResult.Success ->
                        R.string.settings_migration_backup_export_success
                    com.adsamcik.tracker.app.settings.MigrationBackupExportResult.Failure ->
                        R.string.settings_migration_backup_export_failure
                    com.adsamcik.tracker.app.settings.MigrationBackupExportResult.CleanupRequired ->
                        R.string.settings_migration_backup_export_cleanup_required
                }
                Toast.makeText(context, resources.getString(message), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val legacyDatabaseExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.sqlite3")
    ) { uri ->
        if (uri != null) {
            dataVm.exportLegacyDatabase(uri) { result ->
                val message = when (result) {
                    LegacyDatabaseExportResult.Success -> R.string.legacy_database_export_success
                    LegacyDatabaseExportResult.Failure -> R.string.legacy_database_export_failure
                    LegacyDatabaseExportResult.CleanupRequired ->
                        R.string.legacy_database_export_cleanup_required
                }
                Toast.makeText(context, resources.getString(message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        item {
            val importFormatNames = supportedImportFormatNames()
            val archiveNames = dataImport.supportedArchiveExtractorExtensions
                .joinToString { it.uppercase(Locale.getDefault()) }
            val resetWatermarksDoneMessage = stringResource(R.string.settings_reset_export_watermarks_done)
            SettingsGroupCard(
                title = stringResource(com.adsamcik.tracker.impexp.R.string.settings_export_title),
                icon = Icons.Default.FileUpload,
            ) {
                SettingsItem(
                    title = stringResource(R.string.settings_export_data_title),
                    subtitle = stringResource(R.string.settings_export_data_summary),
                    icon = Icons.Default.FileUpload,
                    onClick = { showExportFormatDialog = true },
                )
                if (uiState.migrationBackup != null) {
                    SettingsRowDivider()
                    MigrationBackupExportSetting(
                        backup = checkNotNull(uiState.migrationBackup),
                        onClick = { showMigrationBackupWarning = true },
                    )
                }
                if (uiState.legacyDatabase != null) {
                    SettingsRowDivider()
                    LegacyDatabaseSetting(
                        database = checkNotNull(uiState.legacyDatabase),
                        onClick = { showLegacyDatabaseDetails = true },
                    )
                }
                SettingsRowDivider()
                SwitchSettingsItem(
                    title = stringResource(R.string.settings_incremental_backups_title),
                    subtitle = stringResource(R.string.settings_incremental_backups_summary),
                    icon = Icons.Default.Storage,
                    checked = uiState.incrementalBackupsEnabled,
                    onCheckedChange = { dataVm.setIncrementalBackupsEnabled(it) },
                )
                SettingsRowDivider()
                SettingsItem(
                    title = stringResource(R.string.settings_reset_export_watermarks_title),
                    subtitle = stringResource(R.string.settings_reset_export_watermarks_summary),
                    icon = Icons.Default.Refresh,
                    onClick = {
                        dataVm.resetExportWatermarks()
                        Toast.makeText(context, resetWatermarksDoneMessage, Toast.LENGTH_SHORT).show()
                    },
                )
                SettingsRowDivider()
                SettingsItem(
                    title = stringResource(com.adsamcik.tracker.impexp.R.string.settings_import_title),
                    subtitle = stringResource(
                        com.adsamcik.tracker.impexp.R.string.settings_import_summary,
                        importFormatNames,
                        archiveNames,
                    ),
                    icon = Icons.Default.FileDownload,
                    onClick = { importLauncher.launch(importMimeTypes) },
                )
            }
        }

        item {
            val autoCleanupSummary = if (uiState.dataRetentionYears == 0) {
                stringResource(R.string.settings_auto_cleanup_old_data_summary_forever)
            } else {
                pluralStringResource(
                    R.plurals.settings_auto_cleanup_old_data_summary_years,
                    uiState.dataRetentionYears,
                    uiState.dataRetentionYears,
                )
            }
            val retentionTitles = stringArrayResource(R.array.settings_data_retention_years_titles).toList()
            val retentionValues = stringArrayResource(R.array.settings_data_retention_years_values).toList()
            SettingsGroupCard(
                title = stringResource(R.string.settings_data_management_section),
                icon = Icons.Default.Storage,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                SwitchSettingsItem(
                    title = stringResource(R.string.settings_auto_cleanup_old_data_title),
                    subtitle = autoCleanupSummary,
                    icon = Icons.Default.AutoDelete,
                    checked = uiState.autoCleanupEnabled,
                    onCheckedChange = { dataVm.setAutoCleanupEnabled(it) },
                )
                SettingsRowDivider()
                SwitchSettingsItem(
                    title = stringResource(R.string.settings_smart_goal_notifications_title),
                    subtitle = stringResource(R.string.settings_smart_goal_notifications_summary),
                    icon = Icons.Default.Notifications,
                    checked = uiState.smartGoalNotificationsEnabled,
                    onCheckedChange = { dataVm.setSmartGoalNotificationsEnabled(it) },
                )
                SettingsRowDivider()
                DialogListPreference(
                    title = stringResource(R.string.settings_data_retention_years_title),
                    currentValue = uiState.dataRetentionYears.toString(),
                    entries = retentionTitles,
                    entryValues = retentionValues,
                    icon = Icons.Default.History,
                    onValueChange = { selectedIndex ->
                        val years = retentionValues.getOrNull(selectedIndex)?.toIntOrNull()
                            ?: return@DialogListPreference
                        dataVm.setDataRetentionYears(years)
                    },
                )
            }
        }

        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_data_danger_zone_section),
                icon = Icons.Default.DeleteForever,
                tone = SettingsItemTone.Danger,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                SettingsItem(
                    title = stringResource(R.string.settings_remove_all_collected_data_title),
                    subtitle = stringResource(R.string.settings_remove_all_collected_data_summary),
                    icon = Icons.Default.DeleteForever,
                    tone = SettingsItemTone.Danger,
                    onClick = { debugVm.showDeleteDataDialog() },
                )
            }
        }

    }

    // Delete data confirmation dialog
    if (showDeleteDataDialog) {
        val deleteDataSuccessMessage = stringResource(R.string.settings_delete_data_success)
        val deleteDataFailureMessage = stringResource(R.string.settings_delete_data_failure)
        CollectedDataDeletionDialog(
            onDismiss = { debugVm.hideDeleteDataDialog() },
            onConfirm = {
                debugVm.hideDeleteDataDialog()
                dataVm.deleteAllCollectedData { result ->
                    val message = when (result) {
                        DataDeletionResult.Success -> deleteDataSuccessMessage
                        DataDeletionResult.Failure -> deleteDataFailureMessage
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    if (showMigrationBackupWarning) {
        MigrationBackupWarningDialog(
            backup = checkNotNull(uiState.migrationBackup),
            onDismiss = { showMigrationBackupWarning = false },
            onExport = { fileName ->
                showMigrationBackupWarning = false
                migrationBackupExportLauncher.launch(fileName)
            },
        )
    }

    if (showLegacyDatabaseDetails && uiState.legacyDatabase != null) {
        LegacyDatabaseDetailsDialog(
            database = checkNotNull(uiState.legacyDatabase),
            onDismiss = { showLegacyDatabaseDetails = false },
            onExport = {
                val version = checkNotNull(uiState.legacyDatabase).sourceVersion
                legacyDatabaseExportLauncher.launch("tracker-legacy-v$version.db")
            },
            onDelete = {
                showLegacyDatabaseDetails = false
                showLegacyDatabaseDeleteConfirmation = true
            },
        )
    }

    if (showLegacyDatabaseDeleteConfirmation && uiState.legacyDatabase != null) {
        LegacyDatabaseDeleteDialog(
            database = checkNotNull(uiState.legacyDatabase),
            onDismiss = { showLegacyDatabaseDeleteConfirmation = false },
            onConfirm = {
                showLegacyDatabaseDeleteConfirmation = false
                dataVm.deleteLegacyDatabase { result ->
                    val message = when (result) {
                        LegacyDatabaseDeleteResult.Success -> R.string.legacy_database_delete_success
                        LegacyDatabaseDeleteResult.Failure -> R.string.legacy_database_delete_failure
                    }
                    Toast.makeText(context, resources.getString(message), Toast.LENGTH_SHORT).show()
                }
            },
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
private fun LegacyDatabaseSetting(
    database: LegacyDatabaseUiInfo,
    onClick: () -> Unit,
) {
    val size = android.text.format.Formatter.formatFileSize(LocalContext.current, database.sizeBytes)
    SettingsItem(
        title = stringResource(R.string.legacy_database_vault_title),
        subtitle = stringResource(
            R.string.legacy_database_vault_summary,
            database.sourceVersion,
            size,
            legacyImportStatusLabel(database.importStatus),
        ),
        icon = Icons.Default.Storage,
        onClick = onClick,
    )
}

@Composable
private fun LegacyDatabaseDetailsDialog(
    database: LegacyDatabaseUiInfo,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val size = android.text.format.Formatter.formatFileSize(LocalContext.current, database.sizeBytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.legacy_database_vault_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    stringResource(
                        R.string.legacy_database_vault_details,
                        database.sourceVersion,
                        size,
                        legacyImportStatusLabel(database.importStatus),
                        database.importedRows,
                        database.skippedRows,
                    ),
                )
                database.completedAtMs?.let { completedAtMs ->
                    Text(
                        stringResource(
                            R.string.legacy_database_completed_at,
                            DateFormat.getDateTimeInstance().format(java.util.Date(completedAtMs)),
                        ),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (database.canDelete) {
                    TextButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            stringResource(R.string.legacy_database_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onExport) {
                Text(stringResource(R.string.legacy_database_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_cancel))
            }
        },
    )
}

@Composable
private fun LegacyDatabaseDeleteDialog(
    database: LegacyDatabaseUiInfo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val size = android.text.format.Formatter.formatFileSize(LocalContext.current, database.sizeBytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.legacy_database_delete_confirm_title)) },
        text = { Text(stringResource(R.string.legacy_database_delete_confirm_message, size)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_cancel))
            }
        },
    )
}

@Composable
private fun legacyImportStatusLabel(status: LegacyImportStatus): String = stringResource(
    when (status) {
        LegacyImportStatus.NOT_STARTED -> R.string.legacy_database_status_not_started
        LegacyImportStatus.RUNNING -> R.string.legacy_database_status_running
        LegacyImportStatus.COMPLETE -> R.string.legacy_database_status_complete
        LegacyImportStatus.FAILED -> R.string.legacy_database_status_failed
    },
)

@Composable
internal fun CollectedDataDeletionDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = stringResource(R.string.settings_remove_all_collected_data_title)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                stringResource(
                    com.adsamcik.tracker.shared.base.R.string.alert_confirm,
                    title.replaceFirstChar { it.lowercase() },
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_cancel))
            }
        },
    )
}

@Composable
internal fun MigrationBackupExportAvailability(
    backup: MigrationBackupUiInfo?,
    onClick: () -> Unit,
) {
    if (backup != null) {
        MigrationBackupExportSetting(
            backup = backup,
            onClick = onClick,
        )
    }
}

@Composable
internal fun MigrationBackupExportSetting(
    backup: MigrationBackupUiInfo,
    onClick: () -> Unit,
) {
    SettingsItem(
        title = stringResource(R.string.settings_migration_backup_export_title),
        subtitle = stringResource(
            R.string.settings_migration_backup_export_summary,
            backup.sourceVersion,
            backup.targetVersion,
        ),
        icon = Icons.Default.FileUpload,
        onClick = onClick,
    )
}

@Composable
internal fun MigrationBackupWarningDialog(
    backup: MigrationBackupUiInfo,
    onDismiss: () -> Unit,
    onExport: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_migration_backup_warning_title)) },
        text = { Text(stringResource(R.string.settings_migration_backup_warning_message)) },
        confirmButton = {
            Button(onClick = { onExport(backup.fileName) }) {
                Text(stringResource(R.string.settings_migration_backup_export_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_cancel))
            }
        },
    )
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
