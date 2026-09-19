package com.adsamcik.tracker.app.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.R
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupException
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseState
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportStatus
import com.adsamcik.tracker.shared.base.result.runCatchingCancellable
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigurationApprovalResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionPolicyApprovalStatus
import com.adsamcik.tracker.shared.preferences.retention.UnavailableRetentionAuthorityProducer
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciler
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

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

data class LegacyDatabaseUiInfo(
    val sourceVersion: Int,
    val sizeBytes: Long,
    val importStatus: LegacyImportStatus,
    val completedAtMs: Long?,
    val importedRows: Long,
    val skippedRows: Long,
    val canDelete: Boolean,
)

enum class LegacyDatabaseExportResult {
    Success,
    Failure,
    CleanupRequired,
}

enum class LegacyDatabaseDeleteResult {
    Success,
    Failure,
}

data class DataSettingsUiState(
    val autoCleanupEnabled: Boolean = false,
    val dataRetentionYears: Int = RetentionConfigState.DEFAULT_RETENTION_YEARS,
    val incrementalBackupsEnabled: Boolean = true,
    val smartGoalNotificationsEnabled: Boolean = true,
    val retentionPolicyApprovalStatus: RetentionPolicyApprovalStatus =
        RetentionPolicyApprovalStatus.UNAPPROVED,
    val retentionPolicyFailure: RetentionAuthorityUnavailableReason? = null,
    val ambientStepsLifecycleFailure: AmbientStepsSettingsReconciliationFailure? = null,
    val purposeSettingsReconciliationDebt: TrackingPurposeSettingsReconciliationDebt? = null,
    val migrationBackup: MigrationBackupUiInfo? = null,
    val legacyDatabase: LegacyDatabaseUiInfo? = null,
)

@HiltViewModel
class DataSettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val retentionConfigStore: RetentionConfigStore,
    private val exportPlanStore: ExportPlanStore,
    private val preferences: Preferences,
    private val backupRepository: DatabaseMigrationBackupRepository,
    private val legacyDatabaseRepository: LegacyDatabaseRepository,
    private val dispatchers: DispatchersProvider,
    private val deletionService: CollectedDataDeletionService,
    private val retentionAuthorityProducer: RetentionAuthorityProducer =
        UnavailableRetentionAuthorityProducer,
    private val purposeSettingsReconciler: TrackingPurposeSettingsReconciler =
        TrackingPurposeSettingsReconciler {
            TrackingPurposeSettingsReconciliationResult.Complete(emptySet())
        },
) : ViewModel() {
    private val smartGoalNotificationsKey: String
        get() = appContext.getString(R.string.settings_smart_goal_notifications_key)

    private val retentionFailure =
        MutableStateFlow<RetentionAuthorityUnavailableReason?>(null)
    private val ambientStepsLifecycleFailure =
        MutableStateFlow<AmbientStepsSettingsReconciliationFailure?>(null)
    private val purposeSettingsDebt =
        MutableStateFlow<TrackingPurposeSettingsReconciliationDebt?>(null)

    private val retentionState = combine(
        retentionConfigStore.config
            .catch {
                currentCoroutineContext().ensureActive()
                retentionFailure.value =
                    RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE
                emit(RetentionConfigState())
            },
        retentionConfigStore.approvalStatus
            .catch {
                currentCoroutineContext().ensureActive()
                retentionFailure.value =
                    RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE
                emit(RetentionPolicyApprovalStatus.INVALID)
            },
        ::Pair,
    )

    val uiState: StateFlow<DataSettingsUiState> = combine(
        retentionState,
        exportPlanStore.plans
            .catch {
                emit(emptyList())
            },
        preferences.observeBoolean(smartGoalNotificationsKey, true)
            .catch {
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
                emit(null)
            },
        legacyDatabaseRepository.states
            .flowOn(dispatchers.io)
            .catch { error ->
                emit(
                    LegacyDatabaseState(
                        database = null,
                        importStatus = LegacyImportStatus.FAILED,
                        report = null,
                        lastError = error.message,
                        externallyExported = false,
                    ),
                )
            },
    ) { retention, plans, smartGoalNotificationsEnabled, backup, legacy ->
        val (config, approvalStatus) = retention
        DataSettingsUiState(
            autoCleanupEnabled = config.autoCleanupEnabled,
            dataRetentionYears = config.dataRetentionYears,
            incrementalBackupsEnabled = plans.all { it.incrementalEnabled },
            smartGoalNotificationsEnabled = smartGoalNotificationsEnabled,
            retentionPolicyApprovalStatus = approvalStatus,
            migrationBackup = backup,
            legacyDatabase = legacy.database?.let { database ->
                LegacyDatabaseUiInfo(
                    sourceVersion = database.sourceVersion,
                    sizeBytes = database.sizeBytes,
                    importStatus = legacy.importStatus,
                    completedAtMs = legacy.report?.completedAtMs,
                    importedRows = legacy.report?.importedRows?.values?.sum() ?: 0L,
                    skippedRows = legacy.report?.skippedRows?.values?.sum() ?: 0L,
                    canDelete = legacy.canDelete,
                )
            },
        )
    }.combine(retentionFailure) { state, failure ->
        state.copy(retentionPolicyFailure = failure)
    }.combine(ambientStepsLifecycleFailure) { state, failure ->
        state.copy(ambientStepsLifecycleFailure = failure)
    }.combine(purposeSettingsDebt) { state, debt ->
        state.copy(purposeSettingsReconciliationDebt = debt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataSettingsUiState())

    fun setAutoCleanupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            updateRetentionConfiguration {
                copy(autoCleanupEnabled = enabled, autoPurgeEnabled = enabled)
            }
        }
    }

    fun setDataRetentionYears(years: Int) {
        if (years < 0) return
        viewModelScope.launch {
            val retentionDays = if (years == 0) 0 else years * DAYS_PER_YEAR
            updateRetentionConfiguration {
                copy(
                    dataRetentionYears = years,
                    rawDataRetentionDays = retentionDays,
                    wifiCellRetentionDays = retentionDays,
                    tripRetentionDays = retentionDays,
                    dailySummaryRetentionDays = retentionDays,
                    explorationRetentionDays = retentionDays,
                )
            }
        }
    }

    private suspend fun updateRetentionConfiguration(
        block: RetentionConfigState.() -> RetentionConfigState,
    ) {
        retentionFailure.value = null
        ambientStepsLifecycleFailure.value = null
        purposeSettingsDebt.value = null
        try {
            val result = retentionConfigStore.updateWithApproval(
                block = block,
                prepare = { staged ->
                    retentionAuthorityProducer.preparePendingConfiguration(
                        staged.policy.configurationGeneration,
                    )
                },
                approve = { staged ->
                    retentionAuthorityProducer.reconcilePendingConfiguration(
                        staged.policy.configurationGeneration,
                    )
                },
            )
            publishPurposeSettingsResult(
                purposeSettingsReconciler.reconcileCurrentSettings(),
            )
            retentionFailure.value =
                (result.approval as? RetentionConfigurationApprovalResult.Unavailable)?.reason
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            retentionFailure.value = RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE
            try {
                publishPurposeSettingsResult(
                    purposeSettingsReconciler.reconcileCurrentSettings(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                retentionFailure.value = RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE
                ambientStepsLifecycleFailure.value =
                    AmbientStepsSettingsReconciliationFailure.DURABLE_AUTHORITY_REJECTED
            }
        }
    }

    fun retryPurposeSettingsReconciliation() {
        viewModelScope.launch {
            publishPurposeSettingsResult(purposeSettingsReconciler.reconcileCurrentSettings())
        }
    }

    private fun publishPurposeSettingsResult(
        result: TrackingPurposeSettingsReconciliationResult,
    ) {
        val debt = (result as? TrackingPurposeSettingsReconciliationResult.Debt)?.debt
        purposeSettingsDebt.value = debt
        ambientStepsLifecycleFailure.value = debt?.failures
            ?.firstOrNull { it.source == AmbientTrackingSource.STEPS }
            ?.let { AmbientStepsSettingsReconciliationFailure.DURABLE_AUTHORITY_REJECTED }
    }

    fun setIncrementalBackupsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatchingCancellable {
                exportPlanStore.setAllIncrementalEnabled(enabled)
            }.getOrNull()
        }
    }

    fun setSmartGoalNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatchingCancellable {
                preferences.edit {
                    setBoolean(smartGoalNotificationsKey, enabled)
                }
            }.getOrNull()
        }
    }

    fun resetExportWatermarks() {
        viewModelScope.launch {
            runCatchingCancellable {
                exportPlanStore.resetAllWatermarks()
            }.getOrNull()
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
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                } ?: return@withContext MigrationBackupExportResult.Failure

                try {
                    output.use(backupRepository::exportLatest)
                    MigrationBackupExportResult.Success
                } catch (_: DatabaseMigrationBackupException) {
                    cleanupFailedExport(destination)
                } catch (_: IOException) {
                    cleanupFailedExport(destination)
                } catch (_: SecurityException) {
                    cleanupFailedExport(destination)
                }
            }
            onResult(result)
        }
    }

    fun exportLegacyDatabase(
        destination: Uri,
        onResult: (LegacyDatabaseExportResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                val output = try {
                    appContext.contentResolver.openOutputStream(destination, "rwt")
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                } ?: return@withContext LegacyDatabaseExportResult.Failure

                try {
                    legacyDatabaseRepository.export(output)
                    LegacyDatabaseExportResult.Success
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    when (cleanupFailedExport(destination)) {
                        MigrationBackupExportResult.CleanupRequired ->
                            LegacyDatabaseExportResult.CleanupRequired
                        else -> LegacyDatabaseExportResult.Failure
                    }
                }
            }
            onResult(result)
        }
    }

    fun deleteLegacyDatabase(onResult: (LegacyDatabaseDeleteResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                try {
                    legacyDatabaseRepository.delete()
                    LegacyDatabaseDeleteResult.Success
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    LegacyDatabaseDeleteResult.Failure
                }
            }
            onResult(result)
        }
    }

    fun deleteAllCollectedData(onResult: (DataDeletionResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                try {
                    when (deletionService.deleteAll()) {
                        CollectedDataDeletionCompletion.Complete -> DataDeletionResult.Success
                        is CollectedDataDeletionCompletion.Retryable,
                        is CollectedDataDeletionCompletion.Unverifiable,
                        -> DataDeletionResult.Failure
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The durable pending marker preserves unfinished deletion work.
                    DataDeletionResult.Failure
                }
            }
            onResult(result)
        }
    }

    private fun cleanupFailedExport(destination: Uri): MigrationBackupExportResult {
        val deleted = try {
            appContext.contentResolver.delete(destination, null, null) > 0
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
        if (deleted) return MigrationBackupExportResult.Failure

        return try {
            val output = appContext.contentResolver.openOutputStream(destination, "rwt")
                ?: return MigrationBackupExportResult.CleanupRequired
            output.use { it.flush() }
            MigrationBackupExportResult.Failure
        } catch (_: IOException) {
            MigrationBackupExportResult.CleanupRequired
        } catch (_: SecurityException) {
            MigrationBackupExportResult.CleanupRequired
        } catch (_: IllegalArgumentException) {
            MigrationBackupExportResult.CleanupRequired
        } catch (_: UnsupportedOperationException) {
            MigrationBackupExportResult.CleanupRequired
        }
    }

    private companion object {
        const val DAYS_PER_YEAR = 365
    }
}
