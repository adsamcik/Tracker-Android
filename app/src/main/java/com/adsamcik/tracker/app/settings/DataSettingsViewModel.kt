package com.adsamcik.tracker.app.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.R
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupException
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

data class MigrationBackupUiInfo(
    val fileName: String,
    val sourceVersion: Int,
    val targetVersion: Int,
    val createdAtMs: Long,
)

enum class MigrationBackupExportResult {
    Success,
    Failure,
    CleanupRequired,
}

enum class DataDeletionResult {
    Success,
    Failure,
}

data class DataSettingsUiState(
    val autoCleanupEnabled: Boolean = false,
    val dataRetentionYears: Int = RetentionConfigState.DEFAULT_RETENTION_YEARS,
    val incrementalBackupsEnabled: Boolean = true,
    val smartGoalNotificationsEnabled: Boolean = true,
    val migrationBackup: MigrationBackupUiInfo? = null,
)

@HiltViewModel
class DataSettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val retentionConfigStore: RetentionConfigStore,
    private val exportPlanStore: ExportPlanStore,
    private val preferences: Preferences,
    private val backupRepository: DatabaseMigrationBackupRepository,
    private val dispatchers: DispatchersProvider,
    private val deletionService: CollectedDataDeletionService,
) : ViewModel() {
    private val smartGoalNotificationsKey: String
        get() = appContext.getString(R.string.settings_smart_goal_notifications_key)

    val uiState: StateFlow<DataSettingsUiState> = combine(
        retentionConfigStore.config
            .catch {
                Log.e("DataSettingsViewModel", "Failed to load data settings", it)
                emit(RetentionConfigState())
            },
        exportPlanStore.plans
            .catch {
                Log.e("DataSettingsViewModel", "Failed to load export plans", it)
                emit(emptyList())
            },
        preferences.observeBoolean(smartGoalNotificationsKey, true)
            .catch {
                Log.e("DataSettingsViewModel", "Failed to load smart goal notifications", it)
                emit(true)
            },
        backupRepository.backups
            .map { backup ->
                backup?.let {
                    MigrationBackupUiInfo(
                        fileName = it.file.name,
                        sourceVersion = it.sourceVersion,
                        targetVersion = it.targetVersion,
                        createdAtMs = it.createdAtMs,
                    )
                }
            }
            .flowOn(dispatchers.io)
            .catch {
                Log.e("DataSettingsViewModel", "Failed to load migration backup", it)
                emit(null)
            },
    ) { config, plans, smartGoalNotificationsEnabled, backup ->
        DataSettingsUiState(
            autoCleanupEnabled = config.autoCleanupEnabled,
            dataRetentionYears = config.dataRetentionYears,
            incrementalBackupsEnabled = plans.all { it.incrementalEnabled },
            smartGoalNotificationsEnabled = smartGoalNotificationsEnabled,
            migrationBackup = backup,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataSettingsUiState())

    fun setAutoCleanupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                retentionConfigStore.update {
                    copy(autoCleanupEnabled = enabled, autoPurgeEnabled = enabled)
                }
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to update auto-cleanup", it)
            }
        }
    }

    fun setDataRetentionYears(years: Int) {
        if (years < 0) return
        viewModelScope.launch {
            runCatching {
                val retentionDays = if (years == 0) 0 else years * DAYS_PER_YEAR
                retentionConfigStore.update {
                    copy(
                        dataRetentionYears = years,
                        rawDataRetentionDays = retentionDays,
                        wifiCellRetentionDays = retentionDays,
                        tripRetentionDays = retentionDays,
                        dailySummaryRetentionDays = retentionDays,
                        explorationRetentionDays = retentionDays,
                        legacySessionRetentionDays = retentionDays,
                    )
                }
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to update retention years", it)
            }
        }
    }

    fun setIncrementalBackupsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                exportPlanStore.setAllIncrementalEnabled(enabled)
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to update incremental backups", it)
            }
        }
    }

    fun setSmartGoalNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                preferences.edit {
                    setBoolean(smartGoalNotificationsKey, enabled)
                }
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to update smart goal notifications", it)
            }
        }
    }

    fun resetExportWatermarks() {
        viewModelScope.launch {
            runCatching {
                exportPlanStore.resetAllWatermarks()
            }.onFailure {
                Log.e("DataSettingsViewModel", "Failed to reset export watermarks", it)
            }
        }
    }

    fun exportMigrationBackup(
        destination: Uri,
        onResult: (MigrationBackupExportResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                val output = try {
                    appContext.contentResolver.openOutputStream(destination, "rwt")
                } catch (error: IOException) {
                    Log.e("DataSettingsViewModel", "Could not open migration backup destination", error)
                    null
                } catch (error: SecurityException) {
                    Log.e("DataSettingsViewModel", "Migration backup destination was denied", error)
                    null
                } ?: return@withContext MigrationBackupExportResult.Failure

                try {
                    output.use(backupRepository::exportLatest)
                    MigrationBackupExportResult.Success
                } catch (error: DatabaseMigrationBackupException) {
                    Log.e("DataSettingsViewModel", "Migration backup export failed", error)
                    cleanupFailedExport(destination)
                } catch (error: IOException) {
                    Log.e("DataSettingsViewModel", "Could not write migration backup", error)
                    cleanupFailedExport(destination)
                } catch (error: SecurityException) {
                    Log.e("DataSettingsViewModel", "Migration backup destination denied writing", error)
                    cleanupFailedExport(destination)
                }
            }
            onResult(result)
        }
    }

    fun deleteAllCollectedData(onResult: (DataDeletionResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                try {
                    deletionService.deleteAll()
                    DataDeletionResult.Success
                } catch (error: DatabaseMigrationBackupException) {
                    Log.e("DataSettingsViewModel", "Could not delete migration backup", error)
                    DataDeletionResult.Failure
                } catch (error: android.database.sqlite.SQLiteException) {
                    Log.e("DataSettingsViewModel", "Could not delete collected database data", error)
                    DataDeletionResult.Failure
                }
            }
            onResult(result)
        }
    }

    private fun cleanupFailedExport(destination: Uri): MigrationBackupExportResult {
        try {
            if (appContext.contentResolver.delete(destination, null, null) > 0) {
                return MigrationBackupExportResult.Failure
            }
        } catch (error: SecurityException) {
            Log.w("DataSettingsViewModel", "Could not remove failed backup export", error)
        } catch (error: IllegalArgumentException) {
            Log.w("DataSettingsViewModel", "Backup provider rejected failed-export cleanup", error)
        } catch (error: UnsupportedOperationException) {
            Log.w("DataSettingsViewModel", "Backup provider does not support document deletion", error)
        }

        return try {
            val output = appContext.contentResolver.openOutputStream(destination, "rwt")
                ?: return MigrationBackupExportResult.CleanupRequired
            output.use { it.flush() }
            MigrationBackupExportResult.Failure
        } catch (error: IOException) {
            Log.e("DataSettingsViewModel", "Could not truncate failed backup export", error)
            MigrationBackupExportResult.CleanupRequired
        } catch (error: SecurityException) {
            Log.e("DataSettingsViewModel", "Could not access failed backup export for cleanup", error)
            MigrationBackupExportResult.CleanupRequired
        } catch (error: IllegalArgumentException) {
            Log.e("DataSettingsViewModel", "Backup provider rejected failed-export truncation", error)
            MigrationBackupExportResult.CleanupRequired
        } catch (error: UnsupportedOperationException) {
            Log.e("DataSettingsViewModel", "Backup provider cannot truncate failed export", error)
            MigrationBackupExportResult.CleanupRequired
        }
    }

    private companion object {
        const val DAYS_PER_YEAR = 365
    }
}
