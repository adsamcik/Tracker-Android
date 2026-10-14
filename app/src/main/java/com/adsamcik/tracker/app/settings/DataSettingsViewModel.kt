package com.adsamcik.tracker.app.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.DataImporter
import com.adsamcik.tracker.impexp.importer.file.LocationFileConflictChoice
import com.adsamcik.tracker.impexp.importer.file.LocationFileImportPreview
import com.adsamcik.tracker.impexp.importer.file.LocationFileImportPreviewer
import com.adsamcik.tracker.impexp.importer.file.LocationFileImportReview
import com.adsamcik.tracker.impexp.backup.DataBackupRestore
import com.adsamcik.tracker.impexp.backup.DataBackupInspection
import com.adsamcik.tracker.impexp.backup.DataBackupPreview
import com.adsamcik.tracker.impexp.backup.DataBackupRestoreResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.R
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanId
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseState
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportStatus
import com.adsamcik.tracker.shared.base.result.runCatchingCancellable
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.FilterOutputStream
import java.io.OutputStream
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
    val databasePlansToReview: Map<ExportPlanId, String> = emptyMap(),
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

data class LocationImportChoice(val uri: Uri, val preview: LocationFileImportPreview)
data class FileImportUiState(
    val checking: Boolean = false,
    val locationChoice: LocationImportChoice? = null,
    val backupChoice: DataBackupPreview? = null,
    val error: Boolean = false,
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
    private val startupGate: TrackingStartupGate,
    private val dataBackupRestore: DataBackupRestore,
    private val retentionAuthorityProducer: RetentionAuthorityProducer =
        UnavailableRetentionAuthorityProducer,
    private val purposeSettingsReconciler: TrackingPurposeSettingsReconciler =
        TrackingPurposeSettingsReconciler {
            TrackingPurposeSettingsReconciliationResult.Complete(emptySet())
        },
) : ViewModel() {
    private val _fileImportState = MutableStateFlow(FileImportUiState())
    val fileImportState: StateFlow<FileImportUiState> = _fileImportState.asStateFlow()

    fun requestFileImport(uri: Uri) {
        if (_fileImportState.value.checking || _fileImportState.value.locationChoice != null ||
            _fileImportState.value.backupChoice != null) return
        _fileImportState.value = FileImportUiState(checking = true)
        viewModelScope.launch {
            try {
                when (val inspection = dataBackupRestore.inspect(uri)) {
                    is DataBackupInspection.Ready -> {
                        _fileImportState.value = FileImportUiState(backupChoice = inspection.preview)
                        return@launch
                    }
                    DataBackupInspection.Invalid -> {
                        _fileImportState.value = FileImportUiState(error = true)
                        return@launch
                    }
                    DataBackupInspection.NotBackup -> Unit
                }
                val isLocation = withContext(dispatchers.io) {
                    DocumentFile.fromSingleUri(appContext, uri)?.name
                        ?.endsWith(".trackerlocation", ignoreCase = true) == true
                }
                if (isLocation) {
                    val preview = LocationFileImportPreviewer.inspect(appContext, uri)
                    if (preview.conflictCount > 0) {
                        _fileImportState.value = FileImportUiState(locationChoice = LocationImportChoice(uri, preview))
                    } else enqueueFileImport(uri, LocationFileImportReview(preview, LocationFileConflictChoice.KEEP_EXISTING))
                } else enqueueFileImport(uri, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _fileImportState.value = FileImportUiState(error = true)
            }
        }
    }

    fun chooseLocationImport(choice: LocationFileConflictChoice) {
        val pending = _fileImportState.value.locationChoice ?: return
        enqueueFileImport(pending.uri, LocationFileImportReview(pending.preview, choice))
    }

    fun dismissFileImport() {
        val token = _fileImportState.value.backupChoice?.token
        _fileImportState.value = FileImportUiState()
        if (token != null) viewModelScope.launch {
            try {
                withContext(NonCancellable + dispatchers.io) { dataBackupRestore.discardPreview(token) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The owned preview remains available to the existing startup/Clear all cleanup.
                _fileImportState.value = _fileImportState.value.copy(error = true)
            }
        }
    }

    fun restoreConfirmedBackup() {
        val preview = _fileImportState.value.backupChoice ?: return
        _fileImportState.value = FileImportUiState(checking = true)
        viewModelScope.launch {
            try {
                _fileImportState.value = FileImportUiState(error =
                    dataBackupRestore.restore(preview) != DataBackupRestoreResult.Restored)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _fileImportState.value = FileImportUiState(error = true)
            }
        }
    }

    private fun enqueueFileImport(uri: Uri, review: LocationFileImportReview?) {
        _fileImportState.value = FileImportUiState()
        DataImporter.import(appContext, uri, review) {
            _fileImportState.value = FileImportUiState(error = true)
        }
    }

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
            databasePlansToReview = plans.filter { it.databaseScopeReviewRequired }.associate { it.id to it.name },
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

    fun resumeReviewedDatabasePlans(ids: Set<ExportPlanId>) {
        viewModelScope.launch {
            try {
                for (id in ids) exportPlanStore.reviewDatabaseScopeAndEnable(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _fileImportState.value = FileImportUiState(error = true)
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
            val result = exportPreservedData(destination) {
                backupRepository.exportLatest(it)
            }
            onResult(result)
        }
    }

    fun exportLegacyDatabase(
        destination: Uri,
        onResult: (LegacyDatabaseExportResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = when (exportPreservedData(destination) { legacyDatabaseRepository.export(it) }) {
                MigrationBackupExportResult.Success -> LegacyDatabaseExportResult.Success
                MigrationBackupExportResult.Failure -> LegacyDatabaseExportResult.Failure
                MigrationBackupExportResult.CleanupRequired -> LegacyDatabaseExportResult.CleanupRequired
            }
            onResult(result)
        }
    }

    private suspend fun exportPreservedData(
        destination: Uri,
        write: (OutputStream) -> Unit,
    ): MigrationBackupExportResult {
        val generation = startupGate.currentGeneration
        if (startupGate.reconcile() !is TrackingStartupResult.Ready) {
            return MigrationBackupExportResult.Failure
        }
        return startupGate.withReadyGenerationTransfer(generation) {
            withContext(dispatchers.io) {
                var opened = false
                var completed = false
                try {
                    val operationContext = currentCoroutineContext()
                    operationContext.ensureActive()
                    val output = appContext.contentResolver.openOutputStream(destination, "rwt")
                        ?: return@withContext MigrationBackupExportResult.Failure
                    opened = true
                    var destinationClosed = false
                    val cancellableOutput = object : FilterOutputStream(output) {
                        override fun write(value: Int) {
                            operationContext.ensureActive()
                            out.write(value)
                        }

                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            operationContext.ensureActive()
                            out.write(bytes, offset, length)
                        }

                        override fun close() {
                            if (destinationClosed) return
                            try {
                                operationContext.ensureActive()
                            } finally {
                                super.close()
                                destinationClosed = true
                            }
                        }
                    }
                    cancellableOutput.use {
                        write(it)
                        // Legacy export closes the stream before marking its vault exportable.
                        // A cancellation after that commit must retain the completed user copy.
                        completed = destinationClosed
                        operationContext.ensureActive()
                    }
                    completed = true
                    MigrationBackupExportResult.Success
                } catch (cancelled: CancellationException) {
                    if (opened && !completed) {
                        try {
                            withContext(NonCancellable) { cleanupFailedExport(destination) }
                        } catch (cleanupFailure: Exception) {
                            if (cleanupFailure !== cancelled) cancelled.addSuppressed(cleanupFailure)
                        }
                    }
                    throw cancelled
                } catch (_: Exception) {
                    if (opened && !completed) withContext(NonCancellable) { cleanupFailedExport(destination) }
                    else MigrationBackupExportResult.Failure
                }
            }
        } ?: MigrationBackupExportResult.Failure
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (deleted) return MigrationBackupExportResult.Failure

        return try {
            val output = appContext.contentResolver.openOutputStream(destination, "rwt")
                ?: return MigrationBackupExportResult.CleanupRequired
            output.use { it.flush() }
            MigrationBackupExportResult.Failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MigrationBackupExportResult.CleanupRequired
        }
    }

    private companion object {
        const val DAYS_PER_YEAR = 365
    }
}
