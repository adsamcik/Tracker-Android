package com.adsamcik.tracker.app.settings

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.adsamcik.tracker.activity.api.ActivityRecognitionApi
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.tracker.api.TrackingPurposeDeletionFencer
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailureReason
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
import com.adsamcik.tracker.app.maintenance.RetentionPipelineWorker
import com.adsamcik.tracker.app.startup.TrackingStartupDeletionBarrier
import com.adsamcik.tracker.impexp.importer.DataImporter
import com.adsamcik.tracker.impexp.exporter.automation.ExportAutomationController
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.points.database.PointsAwardedDao
import com.adsamcik.tracker.points.event.PointsDomainEventConsumer
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupException
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityBootstrapCoordinator
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationDebt
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationFailure
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationResult
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.data.worker.AchievementWorker
import com.adsamcik.tracker.maintenance.DatabaseMaintenanceWorker
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingStopQuiescenceResult
import com.adsamcik.tracker.tracker.resilience.PendingSignalDrainWork
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import com.adsamcik.tracker.tracker.worker.HistoricalTrajectoryReconstructionWorker
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Provider

interface CollectedDataDeletionService {
	suspend fun deleteAll(): CollectedDataDeletionCompletion

	suspend fun reconcilePendingDeletion(): CollectedDataDeletionCompletion
}

/** Completion is reported only after authority repair, durable marker clear, and barrier reopen. */
sealed interface CollectedDataDeletionCompletion {
	data object Complete : CollectedDataDeletionCompletion

	data class Retryable(
		val failure: CollectedDataDeletionReconciliationFailure,
	) : CollectedDataDeletionCompletion

	data class Unverifiable(
		val failure: CollectedDataDeletionReconciliationFailure,
	) : CollectedDataDeletionCompletion
}

sealed interface CollectedDataDeletionReconciliationFailure {
	val failureCode: String

	data class RetentionAuthority(
		val failures: List<RetentionAuthorityResult.Unavailable>,
	) : CollectedDataDeletionReconciliationFailure {
		init {
			require(failures.isNotEmpty())
		}

		override val failureCode: String = "POST_DELETE_RETENTION_AUTHORITY"
	}

	data object RetentionResultSetInvalid : CollectedDataDeletionReconciliationFailure {
		override val failureCode: String = "POST_DELETE_RETENTION_RESULT_SET_INVALID"
	}

	data class PurposeSettings(
		val debt: TrackingPurposeSettingsReconciliationDebt,
	) : CollectedDataDeletionReconciliationFailure {
		override val failureCode: String = "POST_DELETE_PURPOSE_SETTINGS"
	}

	data class SourcePolicyAuthority(
		val debt: SourcePolicyRevisionReconciliationDebt,
	) : CollectedDataDeletionReconciliationFailure {
		override val failureCode: String = "POST_DELETE_SOURCE_POLICY_AUTHORITY"
	}

	data class ProviderFence(
		val debt: CollectedDataProviderFenceDebt,
	) : CollectedDataDeletionReconciliationFailure {
		override val failureCode: String = "COLLECTED_DATA_PROVIDER_FENCE"
	}

	data class DeletionPreparationAndFence(
		val journalFailureCode: String,
		val fenceDebt: CollectedDataProviderFenceDebt,
	) : CollectedDataDeletionReconciliationFailure {
		override val failureCode: String = "DELETE_PREPARATION_AND_PROVIDER_FENCE"
	}

	data object DeletionMarkerRemoval : CollectedDataDeletionReconciliationFailure {
		override val failureCode: String = "POST_DELETE_MARKER_REMOVAL"
	}

	data class CollectedDataProviderFenceDebt(
		val failures: List<CollectedDataProviderFenceFailure>,
	) {
		init {
			require(failures.isNotEmpty())
		}
	}

	sealed interface CollectedDataProviderFenceFailure {
		data class Activity(val code: String) : CollectedDataProviderFenceFailure
		data class PurposeOwners(val debt: TrackingPurposeSettingsReconciliationDebt) :
			CollectedDataProviderFenceFailure
		data object WritersUnavailable : CollectedDataProviderFenceFailure
		data object WritersInProgress : CollectedDataProviderFenceFailure
		data object StartupQuiescenceInProgress : CollectedDataProviderFenceFailure
	}

	data object DeletionMarkerDurability : CollectedDataDeletionReconciliationFailure {
		override val failureCode: String = "POST_DELETE_MARKER_DURABILITY"
	}

	data object DeletionMarkerPublication : CollectedDataDeletionReconciliationFailure {
		override val failureCode: String = "DELETE_MARKER_PUBLICATION"
	}

	data object DeletionMarkerIntegrity : CollectedDataDeletionReconciliationFailure {
		override val failureCode: String = "DELETE_MARKER_INTEGRITY"
	}

	data object DeletionExecution : CollectedDataDeletionReconciliationFailure {
		override val failureCode: String = "COLLECTED_DATA_DELETION_EXECUTION"
	}
}

data class CollectedDataDeletionOperation(
	val operationId: String,
	val targetCollectedDataEpoch: Long,
	val retainedFromMs: Long?,
	val deletedAtMs: Long,
) {
	init {
		require(operationId.isNotBlank())
		require(targetCollectedDataEpoch > 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require(deletedAtMs >= 0L)
	}
}

interface CollectedDataWriterQuiescer {
	suspend fun quiesce()

	fun resume()
}

class DefaultCollectedDataWriterQuiescer(
	private val context: Context,
	private val trackerStateReader: TrackerStateReader,
	private val activityWatcherController: ActivityWatcherController,
	private val exportAutomationController: ExportAutomationController,
	private val workManager: WorkManager = WorkManager.getInstance(context),
	private val quiescenceTimeoutMs: Long = WRITER_QUIESCENCE_TIMEOUT_MS,
	private val awaitTrackerQuiescence: suspend (Context) -> TrackingStopQuiescenceResult =
		{ stopContext -> TrackerServiceApi.stopServiceAndAwaitQuiescence(stopContext) },
) : CollectedDataWriterQuiescer {
	private var restoreRetentionSchedule = false

	override suspend fun quiesce() {
		try {
			withTimeout(quiescenceTimeoutMs) {
				restoreRetentionSchedule = restoreRetentionSchedule ||
					hasActiveUniqueWork(RetentionPipelineWorker.WORK_NAME) ||
					hasActiveUniqueWork(RetentionPipelineWorker.LEGACY_WORK_NAME)
				activityWatcherController.pauseForDataDeletion()
				val trackerStop = awaitTrackerQuiescence(context)
				if (trackerStop != TrackingStopQuiescenceResult.HANDLED) {
					throw DatabaseMigrationBackupException(
						"Could not establish tracker writer quiescence: $trackerStop",
					)
				}
				// Presentation state is not the writer boundary. Once the durable STOP is handled it
				// may still lag briefly, so retain this wait only as UI-state cleanup.
				if (trackerStateReader.isServiceRunning) {
					trackerStateReader.isServiceRunningFlow.first { isRunning -> !isRunning }
				}
				awaitCancellation(
					ActivityRecognitionApi.cancelPendingWork(context),
					"activity recognition",
				)
				awaitCancellation(
					workManager.cancelAllWorkByTag(PointsDomainEventConsumer.POINTS_WORK_TAG),
					"points",
				)
				awaitCancellation(
					workManager.cancelAllWorkByTag(AchievementWorker.WORK_TAG),
					"achievement",
				)
				awaitCancellation(
					workManager.cancelUniqueWork(DailySummaryMaterializationWorker.UNIQUE_WORK_ID),
					"daily summary",
				)
				awaitCancellation(
					workManager.cancelUniqueWork(
						HistoricalTrajectoryReconstructionWorker.UNIQUE_WORK_NAME,
					),
					"historical reconstruction",
				)
				awaitCancellation(
					DataImporter.cancel(context),
					"data import",
				)
				awaitCancellation(
					PendingSignalDrainWork.cancel(context),
					"pending-signal recovery",
				)
				awaitCancellation(
					workManager.cancelUniqueWork(RetentionPipelineWorker.WORK_NAME),
					"data retention",
				)
				awaitCancellation(
					workManager.cancelUniqueWork(RetentionPipelineWorker.LEGACY_WORK_NAME),
					"legacy data retention",
				)
				awaitCancellation(
					DatabaseMaintenanceWorker.cancel(context),
					"database maintenance",
				)
				exportAutomationController.pauseForDataDeletion()
			}
		} catch (error: TimeoutCancellationException) {
			throw DatabaseMigrationBackupException(
				"Timed out while stopping collected-data writers",
				error,
			)
		}
	}

	override fun resume() {
		if (restoreRetentionSchedule) {
			RetentionPipelineWorker.ensureScheduled(context)
		}
		restoreRetentionSchedule = false
		DailySummaryMaterializationWorker.schedule(context)
		exportAutomationController.resumeAfterDataDeletion()
		activityWatcherController.resumeAfterDataDeletion()
	}

	private suspend fun awaitCancellation(operation: Operation, writerName: String) {
		try {
			operation.await()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (error: Exception) {
			throw DatabaseMigrationBackupException(
				"Could not stop $writerName work",
				error,
			)
		}
	}

	private suspend fun hasActiveUniqueWork(uniqueWorkName: String): Boolean = try {
		workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).first().any { workInfo ->
			workInfo.state in ACTIVE_WORK_STATES
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (error: Exception) {
		throw DatabaseMigrationBackupException(
			"Could not check $uniqueWorkName work",
			error,
		)
	}

	private companion object {
		const val WRITER_QUIESCENCE_TIMEOUT_MS = 30_000L
		val ACTIVE_WORK_STATES = setOf(
			WorkInfo.State.ENQUEUED,
			WorkInfo.State.RUNNING,
			WorkInfo.State.BLOCKED,
		)
	}
}

class DefaultCollectedDataDeletionService(
	private val context: Context,
	private val pointsAwardedDao: PointsAwardedDao,
	private val exportPlanStore: ExportPlanStore,
	private val writerQuiescer: CollectedDataWriterQuiescer,
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore,
	private val startupDeletionBarrier: TrackingStartupDeletionBarrier =
		TrackingStartupDeletionBarrier(),
	private val activityRegistrationArbiterProvider: Provider<ActivityRegistrationArbiter>? = null,
	private val purposeDeletionFencerProvider: Provider<TrackingPurposeDeletionFencer>? = null,
	private val automaticControlRestorer: PostDeletionAutomaticControlRestorer,
	private val retentionAuthorityProducer: RetentionAuthorityProducer,
	private val sourcePolicyAuthorityBootstrapCoordinatorProvider:
		Provider<SourcePolicyAuthorityBootstrapCoordinator>? = null,
	private val traceboxDataDeletion: suspend () -> Boolean,
	private val trackingDiagnosticDataDeletion: suspend () -> Boolean = { true },
	private val appDatabaseDeletion: suspend (
		Context,
		CollectedDataDeletionOperation,
	) -> CollectedDataDeletionOperationEntity =
		{ context, operation ->
			AppDatabase.deleteAllCollectedData(
				context = context,
				operationId = operation.operationId,
				collectedDataEpoch = operation.targetCollectedDataEpoch,
				retainedFromMs = operation.retainedFromMs,
				updatedAtMs = operation.deletedAtMs,
			)
		},
	private val readDatabaseDeletionOperation: suspend (
		Context,
		String,
	) -> CollectedDataDeletionOperationEntity? =
		{ context, operationId ->
			AppDatabase.readCollectedDataDeletionOperation(context, operationId)
		},
	private val postDatabaseDeletion: suspend (CollectedDataDeletionOperation) -> Unit = { },
	private val markerFile: File = File(
		context.noBackupFilesDir,
		"collected-data-deletion-pending",
	),
	private val directorySync: (File) -> Unit = ::syncDirectory,
	private val markerDelete: (File) -> Boolean = File::delete,
	private val currentTimeMillis: () -> Long = System::currentTimeMillis,
	private val providerFenceTimeoutMs: Long = PROVIDER_FENCE_TIMEOUT_MS,
	private val providerFenceScope: CoroutineScope =
		CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : CollectedDataDeletionService {
	private val deletionMutex = Mutex()
	private val providerFenceFlightMutex = Mutex()
	private val providerFenceFlights = mutableMapOf<String, ProviderFenceFlight>()
	private var deletionFlight: DeletionFlight? = null
	private val clearingMarkerFile = File(
		checkNotNull(markerFile.parentFile),
		"${markerFile.name}.clearing",
	)
	private val preparedMarkerFile = File(
		checkNotNull(markerFile.parentFile),
		"${markerFile.name}.tmp",
	)
	private var markerClearPendingInProcess = false

	init {
		require(providerFenceTimeoutMs > 0L)
	}

	override suspend fun deleteAll(): CollectedDataDeletionCompletion =
		awaitDeletionStart(deletionMutex.withLock {
			activeDeletionFlight()?.let { return@withLock DeletionStart.Await(it) }
			if (clearingMarkerFile.exists() || markerClearPendingInProcess) {
				return@withLock startDeletionFlight(
					pendingDeletionIdentity(clearingMarkerFile),
					::reconcileMarkerClearCompletion,
				)
			}
			when (val journal = prepareNewDeletionJournal()) {
				is DeletionJournalPreparation.Ready -> {
					startDeletionFlight(journal.operation.operationId) {
						runDeletion(journal.operation)
					}
				}
				is DeletionJournalPreparation.Failed -> {
					if (markerFile.exists() || preparedMarkerFile.exists()) {
						startDeletionFlight(
							journal.operation?.operationId
								?: pendingDeletionIdentity(markerFile, preparedMarkerFile),
						) {
							fenceAfterMarkerPublicationFailure(journal.completion)
						}
					} else {
						DeletionStart.Immediate(journal.completion)
					}
				}
			}
		})

	override suspend fun reconcilePendingDeletion(): CollectedDataDeletionCompletion =
		awaitDeletionStart(deletionMutex.withLock {
			activeDeletionFlight()?.let { return@withLock DeletionStart.Await(it) }
			when {
				clearingMarkerFile.exists() || markerClearPendingInProcess -> {
					startDeletionFlight(
						pendingDeletionIdentity(clearingMarkerFile),
						::reconcileMarkerClearCompletion,
					)
				}
				markerFile.exists() || preparedMarkerFile.exists() -> {
					when (val journal = recoverDeletionJournal()) {
						is DeletionJournalPreparation.Ready ->
							startDeletionFlight(journal.operation.operationId) {
								runDeletion(journal.operation)
							}
						is DeletionJournalPreparation.Failed ->
							startDeletionFlight(
								journal.operation?.operationId
									?: pendingDeletionIdentity(markerFile, preparedMarkerFile),
							) {
								fenceAfterMarkerPublicationFailure(journal.completion)
							}
					}
				}
				else -> {
					if (startupDeletionBarrier.isClosed) startupDeletionBarrier.reopen()
					DeletionStart.Immediate(CollectedDataDeletionCompletion.Complete)
				}
			}
		})

	private fun activeDeletionFlight(): DeletionFlight? {
		val current = deletionFlight ?: return null
		check(current.operationIdentity.isNotBlank())
		return if (current.task.isCompleted) {
			deletionFlight = null
			null
		} else {
			current.awaiterCount += 1
			current
		}
	}

	private fun startDeletionFlight(
		operationIdentity: String,
		operation: suspend () -> CollectedDataDeletionCompletion,
	): DeletionStart.Await {
		require(operationIdentity.isNotBlank())
		val task = providerFenceScope.async(start = CoroutineStart.UNDISPATCHED) {
			try {
				operation()
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				CollectedDataDeletionCompletion.Retryable(
					CollectedDataDeletionReconciliationFailure.DeletionExecution,
				)
			}
		}
		val flight = DeletionFlight(
			operationIdentity = operationIdentity,
			task = task,
			awaiterCount = 1,
		)
		deletionFlight = flight
		return DeletionStart.Await(flight)
	}

	private suspend fun awaitDeletionStart(
		start: DeletionStart,
	): CollectedDataDeletionCompletion = when (start) {
		is DeletionStart.Await -> try {
			start.flight.task.await()
		} finally {
			withContext(NonCancellable) {
				deletionMutex.withLock {
					check(start.flight.awaiterCount > 0)
					start.flight.awaiterCount -= 1
				}
			}
		}
		is DeletionStart.Immediate -> start.completion
	}

	private fun pendingDeletionIdentity(vararg candidates: File): String {
		val existing = candidates.firstOrNull(File::exists)
			?: return "pending-deletion"
		return decodeDeletionOperation(existing)?.operationId
			?: "unreadable:${existing.absolutePath}"
	}

	private suspend fun runDeletion(
		operation: CollectedDataDeletionOperation,
	): CollectedDataDeletionCompletion {
		var purposeDeletionFencer: TrackingPurposeDeletionFencer? = null
		startupDeletionBarrier.beginCloseAdmission()
		try {
			val dependencies = resolveProviderFenceDependencies()
			purposeDeletionFencer = dependencies.purposes
			// Admission is already closed and writers/providers have now received cancellation. Only
			// after the admitted startup operation unwinds may destructive Room deletion begin. An
			// operation admitted before closeAdmission may have resumed providers while unwinding, so
			// deletion takes the final fence after the barrier reaches quiescence.
			val fenceFailures = runProviderFenceProtocol(dependencies)
			if (fenceFailures.isNotEmpty()) {
				return CollectedDataDeletionCompletion.Retryable(
					CollectedDataDeletionReconciliationFailure.ProviderFence(
						CollectedDataProviderFenceDebt(fenceFailures),
					),
				)
			}
			// The durable journal exists before this destructive boundary. Remove legacy vaults
			// before opening the active Room database so no import callback can race the clear.
			deleteRetiredDatabases()
			var databaseOperation = readDatabaseDeletionOperation(
				context,
				operation.operationId,
			)?.also { receipt ->
				requireMatchingDatabaseOperation(operation, receipt)
			}
			val lifecycle = if (databaseOperation == null) {
				collectedDataLifecycleStore.beginFullDeletion(
					operationId = operation.operationId,
					targetEpoch = operation.targetCollectedDataEpoch,
					deletedAtMs = operation.deletedAtMs,
				)
			} else {
				collectedDataLifecycleStore.snapshot()
			}
			check(lifecycle.epoch == operation.targetCollectedDataEpoch)
			check(lifecycle.retainedFromMs == operation.retainedFromMs)
			if (databaseOperation == null) {
				databaseOperation = performDeletion(operation)
			}
			var committedOperation = requireNotNull(databaseOperation)
			if (
				committedOperation.phase ==
				CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED
			) {
				postDatabaseDeletion(operation)
				committedOperation = requireNotNull(
					readDatabaseDeletionOperation(context, operation.operationId),
				) { "Writer re-arm did not preserve the deletion operation receipt" }
				requireMatchingDatabaseOperation(operation, committedOperation)
			}
			check(
				committedOperation.phase ==
					CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED,
			) { "Collected-data deletion writer re-arm remains incomplete" }
			val initialReconciliation = reconcilePostDeletionAuthorityAndRetention()
			if (initialReconciliation != null) {
				return keepPurposeOwnersClosed(
					purposeDeletionFencer,
					initialReconciliation,
				)
			}
			exportPlanStore.resetAllWatermarks()
			deleteDiagnostics()
			// Enqueue the durable recovery owner while the deletion marker and process barrier still
			// fence Room/providers. It will make one attempt only after this generation is Ready.
			automaticControlRestorer.schedule(operation.targetCollectedDataEpoch)
			val confirmedReconciliation = reconcilePostDeletionAuthorityAndRetention()
			if (confirmedReconciliation != null) {
				return keepPurposeOwnersClosed(
					purposeDeletionFencer,
					confirmedReconciliation,
				)
			}
			// Provider and demand reconciliation is startup-owned. Keep every producer closed until
			// the final marker transition is durable and the startup barrier has reopened.
			markerClearPendingInProcess = true
			val markerFailure = clearDeletionMarker()
			if (markerFailure != null) {
				return keepPurposeOwnersClosed(
					purposeDeletionFencer,
					markerFailure,
				)
			}
			startupDeletionBarrier.reopen()
			markerClearPendingInProcess = false
			return CollectedDataDeletionCompletion.Complete
		} catch (cancelled: CancellationException) {
			throw cancelled
		}
	}

	private suspend fun reconcileMarkerClearCompletion(): CollectedDataDeletionCompletion {
		startupDeletionBarrier.beginCloseAdmission()
		val dependencies = resolveProviderFenceDependencies()
		val purposeDeletionFencer = dependencies.purposes
		val fenceFailures = runProviderFenceProtocol(dependencies)
		if (fenceFailures.isNotEmpty()) {
			return CollectedDataDeletionCompletion.Retryable(
				CollectedDataDeletionReconciliationFailure.ProviderFence(
					CollectedDataProviderFenceDebt(fenceFailures),
				),
			)
		}
		markerClearPendingInProcess = true
		val markerFailure = clearDeletionMarker()
		if (markerFailure != null) {
			return keepPurposeOwnersClosed(purposeDeletionFencer, markerFailure)
		}
		startupDeletionBarrier.reopen()
		markerClearPendingInProcess = false
		return CollectedDataDeletionCompletion.Complete
	}

	private suspend fun reconcilePostDeletionRetention(): CollectedDataDeletionCompletion? {
		val results = try {
			retentionAuthorityProducer.reconcileCurrentSettings()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return CollectedDataDeletionCompletion.Retryable(
				CollectedDataDeletionReconciliationFailure.RetentionAuthority(
					DURABLE_AMBIENT_SOURCES.map { source ->
						RetentionAuthorityResult.Unavailable(
							source,
							RetentionAuthorityScope.LIVE_AMBIENT,
							RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
						)
					},
				),
			)
		}

		val durable = results.filter {
			it.scope == RetentionAuthorityScope.LIVE_AMBIENT &&
				it.source in DURABLE_AMBIENT_SOURCES
		}
		if (durable.size != DURABLE_AMBIENT_SOURCES.size ||
			durable.map { it.source }.toSet() != DURABLE_AMBIENT_SOURCES
		) {
			return CollectedDataDeletionCompletion.Unverifiable(
				CollectedDataDeletionReconciliationFailure.RetentionResultSetInvalid,
			)
		}
		val failures = durable.filterIsInstance<RetentionAuthorityResult.Unavailable>()
		if (failures.isEmpty()) return null
		val failure = CollectedDataDeletionReconciliationFailure.RetentionAuthority(failures)
		return if (failures.any { it.reason in UNVERIFIABLE_RETENTION_FAILURES }) {
			CollectedDataDeletionCompletion.Unverifiable(failure)
		} else {
			CollectedDataDeletionCompletion.Retryable(failure)
		}
	}

	private suspend fun reconcilePostDeletionAuthorityAndRetention():
		CollectedDataDeletionCompletion? {
		reconcilePostDeletionSourcePolicyAuthority()?.let { return it }
		return reconcilePostDeletionRetention()
	}

	private suspend fun reconcilePostDeletionSourcePolicyAuthority():
		CollectedDataDeletionCompletion? {
		val coordinator = sourcePolicyAuthorityBootstrapCoordinatorProvider?.get() ?: return null
		val result = try {
			coordinator.reconcileAuthorityForRetentionBootstrap()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			SourcePolicyRevisionReconciliationResult.Retryable(
				SourcePolicyRevisionReconciliationDebt(
					policyRevision = null,
					failures = listOf(
						SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable,
					),
				),
			)
		}
		val debt = when (result) {
			is SourcePolicyRevisionReconciliationResult.Complete -> return null
			is SourcePolicyRevisionReconciliationResult.Retryable -> result.debt
			is SourcePolicyRevisionReconciliationResult.Unverifiable -> result.debt
		}
		val failure = CollectedDataDeletionReconciliationFailure.SourcePolicyAuthority(debt)
		return if (result is SourcePolicyRevisionReconciliationResult.Retryable) {
			CollectedDataDeletionCompletion.Retryable(failure)
		} else {
			CollectedDataDeletionCompletion.Unverifiable(failure)
		}
	}

	private suspend fun keepPurposeOwnersClosed(
		fencer: TrackingPurposeDeletionFencer?,
		original: CollectedDataDeletionCompletion,
	): CollectedDataDeletionCompletion {
		if (fencer == null) return original
		val result = when (
			val attempt = runBoundedProviderFence(
				PURPOSE_FENCE_FLIGHT,
				ProviderFencePass.HOLD_CLOSED,
			) {
				fencer.fenceForCollectedDataDeletion()
			}
		) {
			is ProviderFenceAttempt.Completed -> attempt.value
			ProviderFenceAttempt.Failed ->
				return providerFenceRetry(
					CollectedDataProviderFenceFailure.PurposeOwners(
						unavailablePurposeDebt(),
					),
				)
			ProviderFenceAttempt.TimedOut ->
				return providerFenceRetry(
					CollectedDataProviderFenceFailure.PurposeOwners(
						inProgressPurposeDebt(),
					),
				)
		}
		return if (
			result is TrackingPurposeSettingsReconciliationResult.Complete ||
			original is CollectedDataDeletionCompletion.Unverifiable
		) {
			original
		} else {
			providerFenceRetry(
				CollectedDataProviderFenceFailure.PurposeOwners(
					(result as TrackingPurposeSettingsReconciliationResult.Debt).debt,
				),
			)
		}
	}

	private suspend fun fenceCollectedDataWriters(
		activityRegistrationArbiter: ActivityRegistrationArbiter?,
		purposeDeletionFencer: TrackingPurposeDeletionFencer?,
		pass: ProviderFencePass,
	): CollectedDataDeletionCompletion? {
		val failures = mutableListOf<CollectedDataProviderFenceFailure>()
		if (activityRegistrationArbiter != null) {
			when (
				val attempt = runBoundedProviderFence(ACTIVITY_FENCE_FLIGHT, pass) {
					activityRegistrationArbiter.closeForCollectedDataDeletion()
				}
			) {
				is ProviderFenceAttempt.Completed -> {
					val result = attempt.value
					if (result.status != ActivityRegistrationStatus.APPLIED) {
						failures += CollectedDataProviderFenceFailure.Activity(
							result.failureCode?.name ?: "UNKNOWN",
						)
					}
				}
				ProviderFenceAttempt.Failed ->
					failures += CollectedDataProviderFenceFailure.Activity("UNAVAILABLE")
				ProviderFenceAttempt.TimedOut ->
					failures += CollectedDataProviderFenceFailure.Activity("IN_PROGRESS")
			}
		}
		if (purposeDeletionFencer != null) {
			when (
				val attempt = runBoundedProviderFence(PURPOSE_FENCE_FLIGHT, pass) {
					purposeDeletionFencer.fenceForCollectedDataDeletion()
				}
			) {
				is ProviderFenceAttempt.Completed -> {
					val result = attempt.value
					if (result is TrackingPurposeSettingsReconciliationResult.Debt) {
						failures += CollectedDataProviderFenceFailure.PurposeOwners(result.debt)
					}
				}
				ProviderFenceAttempt.Failed ->
					failures += CollectedDataProviderFenceFailure.PurposeOwners(
						unavailablePurposeDebt(),
					)
				ProviderFenceAttempt.TimedOut ->
					failures += CollectedDataProviderFenceFailure.PurposeOwners(
						inProgressPurposeDebt(),
					)
			}
		}
		when (runBoundedProviderFence(WRITER_FENCE_FLIGHT, pass) { writerQuiescer.quiesce() }) {
			is ProviderFenceAttempt.Completed -> Unit
			ProviderFenceAttempt.Failed ->
				failures += CollectedDataProviderFenceFailure.WritersUnavailable
			ProviderFenceAttempt.TimedOut ->
				failures += CollectedDataProviderFenceFailure.WritersInProgress
		}
		return failures.takeIf { it.isNotEmpty() }?.let {
			CollectedDataDeletionCompletion.Retryable(
				CollectedDataDeletionReconciliationFailure.ProviderFence(
					CollectedDataProviderFenceDebt(failures),
				),
			)
		}
	}

	private suspend fun <T> runBoundedProviderFence(
		key: String,
		pass: ProviderFencePass,
		operation: suspend () -> T,
	): ProviderFenceAttempt<T> = withContext(NonCancellable) {
		val previous = providerFenceFlightMutex.withLock {
			providerFenceFlights[key]
		}
		if (previous != null) {
			val observed = try {
				withTimeoutOrNull(providerFenceTimeoutMs) {
					previous.task.await()
				}
			} catch (_: CancellationException) {
				Result.failure<Any?>(
					IllegalStateException("Provider fence owner scope was cancelled"),
				)
			}
			if (observed == null) {
				return@withContext ProviderFenceAttempt.TimedOut
			}
			providerFenceFlightMutex.withLock {
				if (providerFenceFlights[key] === previous) providerFenceFlights.remove(key)
			}
		}

		val flight = providerFenceFlightMutex.withLock {
			check(providerFenceFlights[key] == null) {
				"Provider fence callback overlap detected for $key: " +
					"${providerFenceFlights[key]?.pass} -> $pass"
			}
			val task = providerFenceScope.async {
				runCatching { operation() as Any? }
			}
			ProviderFenceFlight(pass, task).also { providerFenceFlights[key] = it }
		}
		@Suppress("UNCHECKED_CAST")
		val task = flight.task as Deferred<Result<T>>
		val result = try {
			withTimeoutOrNull(providerFenceTimeoutMs) {
				task.await()
			}
		} catch (_: CancellationException) {
			Result.failure<T>(
				IllegalStateException("Provider fence owner scope was cancelled"),
			)
		}
		if (result != null) {
			providerFenceFlightMutex.withLock {
				if (providerFenceFlights[key] === flight) providerFenceFlights.remove(key)
			}
		}

		if (result == null) {
			ProviderFenceAttempt.TimedOut
		} else {
			result.fold(
				onSuccess = { ProviderFenceAttempt.Completed(it) },
				onFailure = { ProviderFenceAttempt.Failed },
			)
		}
	}

	private suspend fun awaitStartupQuiescence(): CollectedDataDeletionCompletion? =
		try {
			startupDeletionBarrier.awaitQuiescence()
			null
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			providerFenceRetry(
				CollectedDataProviderFenceFailure.StartupQuiescenceInProgress,
			)
		}

	private fun unavailablePurposeDebt(): TrackingPurposeSettingsReconciliationDebt =
		TrackingPurposeSettingsReconciliationDebt(
			listOf(
				TrackingPurposeSettingsReconciliationFailure(
					source = null,
					reason = TrackingPurposeSettingsReconciliationFailureReason
						.OWNER_RECONCILIATION_FAILED,
				),
			),
		)

	private fun inProgressPurposeDebt(): TrackingPurposeSettingsReconciliationDebt =
		TrackingPurposeSettingsReconciliationDebt(
			listOf(
				TrackingPurposeSettingsReconciliationFailure(
					source = null,
					reason = TrackingPurposeSettingsReconciliationFailureReason
						.OWNER_OPERATION_IN_PROGRESS,
				),
			),
		)

	private fun providerFenceRetry(
		vararg failures: CollectedDataProviderFenceFailure,
	): CollectedDataDeletionCompletion.Retryable =
		CollectedDataDeletionCompletion.Retryable(
			CollectedDataDeletionReconciliationFailure.ProviderFence(
				CollectedDataProviderFenceDebt(failures.toList()),
			),
		)

	/** Completes both local diagnostic stores inside the durable, retryable deletion operation. */
	private suspend fun deleteDiagnostics() {
		val trackingDiagnosticsComplete = deleteDiagnosticStore(
			name = "Tracking diagnostic",
			delete = trackingDiagnosticDataDeletion,
		)
		val traceboxComplete = deleteDiagnosticStore(
			name = "Tracebox diagnostic",
			delete = traceboxDataDeletion,
		)
		if (!trackingDiagnosticsComplete) {
			throw DatabaseMigrationBackupException(
				"Tracking diagnostic data deletion remains pending",
			)
		}
		if (!traceboxComplete) {
			throw DatabaseMigrationBackupException(
				"Tracebox diagnostic data deletion remains pending",
			)
		}
	}

	private suspend fun deleteDiagnosticStore(
		name: String,
		delete: suspend () -> Boolean,
	): Boolean = try {
		delete()
	} catch (error: CancellationException) {
		throw error
	} catch (error: Exception) {
		throw DatabaseMigrationBackupException(
			"$name data deletion remains pending",
			error,
		)
	}

	private suspend fun performDeletion(
		operation: CollectedDataDeletionOperation,
	): CollectedDataDeletionOperationEntity {
		pointsAwardedDao.deleteAll()
		return appDatabaseDeletion(context, operation).also { receipt ->
			requireMatchingDatabaseOperation(operation, receipt)
			check(
				receipt.phase ==
					CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED,
			) { "Collected-data clear did not commit its durable operation phase" }
		}
	}

	private fun deleteRetiredDatabases() {
		RETIRED_DATABASE_NAMES.forEach(::deleteRetiredDatabase)
	}

	private fun deleteRetiredDatabase(databaseName: String) {
		context.deleteDatabase(databaseName)
		val database = context.getDatabasePath(databaseName)
		val remainingFiles = listOf(
			database,
			File("${database.path}-wal"),
			File("${database.path}-shm"),
			File("${database.path}-journal"),
		).filter(File::exists)
		if (remainingFiles.isNotEmpty()) {
			throw DatabaseMigrationBackupException(
				"Could not delete retired collected-data database $databaseName",
			)
		}
	}

	private suspend fun prepareNewDeletionJournal(): DeletionJournalPreparation {
		if (markerFile.exists() || preparedMarkerFile.exists()) {
			return recoverDeletionJournal()
		}
		val lifecycle = try {
			collectedDataLifecycleStore.snapshot()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return DeletionJournalPreparation.Failed(
				CollectedDataDeletionCompletion.Retryable(
					CollectedDataDeletionReconciliationFailure.DeletionMarkerPublication,
				),
			)
		}
		val deletedAtMs = currentTimeMillis()
		val operation = CollectedDataDeletionOperation(
			operationId = UUID.randomUUID().toString(),
			targetCollectedDataEpoch = try {
				Math.addExact(lifecycle.epoch, 1L)
			} catch (_: ArithmeticException) {
				return DeletionJournalPreparation.Failed(
					CollectedDataDeletionCompletion.Unverifiable(
						CollectedDataDeletionReconciliationFailure.DeletionMarkerIntegrity,
					),
				)
			},
			retainedFromMs = lifecycle.retainedFromMs
				?.let { maxOf(it, deletedAtMs) }
				?: deletedAtMs,
			deletedAtMs = deletedAtMs,
		)
		return publishPreparedDeletionJournal(operation)
	}

	private fun publishPreparedDeletionJournal(
		operation: CollectedDataDeletionOperation,
	): DeletionJournalPreparation {
		val parent = checkNotNull(markerFile.parentFile)
		if (!parent.isDirectory && !parent.mkdirs()) {
			return DeletionJournalPreparation.Failed(
				CollectedDataDeletionCompletion.Retryable(
					CollectedDataDeletionReconciliationFailure.DeletionMarkerPublication,
				),
				operation,
			)
		}
		try {
			FileOutputStream(preparedMarkerFile).use { output ->
				output.write(operation.encode().encodeToByteArray())
				output.fd.sync()
			}
			directorySync(parent)
			if (!preparedMarkerFile.renameTo(markerFile) && !markerFile.exists()) {
				return DeletionJournalPreparation.Failed(
					CollectedDataDeletionCompletion.Retryable(
						CollectedDataDeletionReconciliationFailure.DeletionMarkerPublication,
					),
					operation,
				)
			}
			directorySync(parent)
			return DeletionJournalPreparation.Ready(operation)
		} catch (_: Exception) {
			return DeletionJournalPreparation.Failed(
				if (markerFile.exists()) {
					CollectedDataDeletionCompletion.Unverifiable(
						CollectedDataDeletionReconciliationFailure.DeletionMarkerDurability,
					)
				} else {
					CollectedDataDeletionCompletion.Retryable(
						CollectedDataDeletionReconciliationFailure.DeletionMarkerPublication,
					)
				},
				operation,
			)
		}
	}

	private suspend fun recoverDeletionJournal(): DeletionJournalPreparation {
		val parent = checkNotNull(markerFile.parentFile)
		if (markerFile.exists()) {
			val operation = decodeDeletionOperation(markerFile)
				?: return DeletionJournalPreparation.Failed(
					CollectedDataDeletionCompletion.Unverifiable(
						CollectedDataDeletionReconciliationFailure.DeletionMarkerIntegrity,
					),
				)
			try {
				RandomAccessFile(markerFile, "rw").use { it.fd.sync() }
				directorySync(parent)
			} catch (_: Exception) {
				return DeletionJournalPreparation.Failed(
					CollectedDataDeletionCompletion.Unverifiable(
						CollectedDataDeletionReconciliationFailure.DeletionMarkerDurability,
					),
					operation,
				)
			}
			if (preparedMarkerFile.exists()) {
				if (!deleteMarkerFile(preparedMarkerFile)) {
					return DeletionJournalPreparation.Failed(
						CollectedDataDeletionCompletion.Retryable(
							CollectedDataDeletionReconciliationFailure.DeletionMarkerRemoval,
						),
						operation,
					)
				}
				try {
					directorySync(parent)
				} catch (_: Exception) {
					return DeletionJournalPreparation.Failed(
						CollectedDataDeletionCompletion.Unverifiable(
							CollectedDataDeletionReconciliationFailure.DeletionMarkerDurability,
						),
						operation,
					)
				}
			}
			return DeletionJournalPreparation.Ready(operation)
		}
		if (!preparedMarkerFile.exists()) {
			return DeletionJournalPreparation.Failed(
				CollectedDataDeletionCompletion.Unverifiable(
					CollectedDataDeletionReconciliationFailure.DeletionMarkerIntegrity,
				),
			)
		}
		val operation = decodeDeletionOperation(preparedMarkerFile)
			?: return DeletionJournalPreparation.Failed(
				CollectedDataDeletionCompletion.Unverifiable(
					CollectedDataDeletionReconciliationFailure.DeletionMarkerIntegrity,
				),
			)
		return try {
			RandomAccessFile(preparedMarkerFile, "rw").use { it.fd.sync() }
			directorySync(parent)
			if (!preparedMarkerFile.renameTo(markerFile) && !markerFile.exists()) {
				return DeletionJournalPreparation.Failed(
					CollectedDataDeletionCompletion.Retryable(
						CollectedDataDeletionReconciliationFailure.DeletionMarkerPublication,
					),
					operation,
				)
			}
			directorySync(parent)
			DeletionJournalPreparation.Ready(operation)
		} catch (_: Exception) {
			DeletionJournalPreparation.Failed(
				if (markerFile.exists()) {
					CollectedDataDeletionCompletion.Unverifiable(
						CollectedDataDeletionReconciliationFailure.DeletionMarkerDurability,
					)
				} else {
					CollectedDataDeletionCompletion.Retryable(
						CollectedDataDeletionReconciliationFailure.DeletionMarkerPublication,
					)
				},
				operation,
			)
		}
	}

	private suspend fun fenceAfterMarkerPublicationFailure(
		original: CollectedDataDeletionCompletion,
	): CollectedDataDeletionCompletion {
		if (!markerFile.exists() && !preparedMarkerFile.exists()) {
			if (startupDeletionBarrier.isClosed) startupDeletionBarrier.reopen()
			return original
		}
		startupDeletionBarrier.beginCloseAdmission()
		val dependencies = resolveProviderFenceDependencies()
		val fenceFailures = runProviderFenceProtocol(dependencies)
		if (fenceFailures.isEmpty()) return original
		val debt = CollectedDataProviderFenceDebt(fenceFailures)
		val combined = CollectedDataDeletionReconciliationFailure.DeletionPreparationAndFence(
			journalFailureCode = when (original) {
				CollectedDataDeletionCompletion.Complete -> "UNKNOWN"
				is CollectedDataDeletionCompletion.Retryable -> original.failure.failureCode
				is CollectedDataDeletionCompletion.Unverifiable -> original.failure.failureCode
			},
			fenceDebt = debt,
		)
		return if (original is CollectedDataDeletionCompletion.Unverifiable) {
			CollectedDataDeletionCompletion.Unverifiable(combined)
		} else {
			CollectedDataDeletionCompletion.Retryable(combined)
		}
	}

	private fun CollectedDataDeletionCompletion.providerFenceFailures():
		List<CollectedDataProviderFenceFailure> =
		(
			(this as? CollectedDataDeletionCompletion.Retryable)
				?.failure as? CollectedDataDeletionReconciliationFailure.ProviderFence
			)?.debt?.failures ?: listOf(CollectedDataProviderFenceFailure.WritersUnavailable)

	private suspend fun runProviderFenceProtocol(
		dependencies: ProviderFenceDependencies,
	): List<CollectedDataProviderFenceFailure> {
		val first = fenceCollectedDataWriters(
			dependencies.activity,
			dependencies.purposes,
			ProviderFencePass.PRE_QUIESCENCE,
		)?.providerFenceFailures().orEmpty()
		val quiescence = awaitStartupQuiescence()
			?.providerFenceFailures()
			.orEmpty()
		val final = fenceCollectedDataWriters(
			dependencies.activity,
			dependencies.purposes,
			ProviderFencePass.POST_QUIESCENCE,
		)?.providerFenceFailures().orEmpty()
		if (dependencies.failures.isEmpty() && quiescence.isEmpty() && final.isEmpty()) {
			return emptyList()
		}
		val unresolvedPreQuiescence = if (quiescence.isEmpty() && final.isEmpty()) {
			emptyList()
		} else {
			first
		}
		return dependencies.failures + unresolvedPreQuiescence + quiescence + final
	}

	private fun resolveProviderFenceDependencies(): ProviderFenceDependencies {
		val failures = mutableListOf<CollectedDataProviderFenceFailure>()
		val activity = activityRegistrationArbiterProvider?.let { provider ->
			try {
				provider.get()
			} catch (_: Exception) {
				failures += CollectedDataProviderFenceFailure.Activity("RESOLUTION_FAILED")
				null
			}
		}
		val purposes = purposeDeletionFencerProvider?.let { provider ->
			try {
				provider.get()
			} catch (_: Exception) {
				failures += CollectedDataProviderFenceFailure.PurposeOwners(
					unavailablePurposeDebt(),
				)
				null
			}
		}
		return ProviderFenceDependencies(activity, purposes, failures)
	}

	private fun requireMatchingDatabaseOperation(
		expected: CollectedDataDeletionOperation,
		actual: CollectedDataDeletionOperationEntity,
	) {
		check(actual.operationId == expected.operationId)
		check(actual.targetCollectedDataEpoch == expected.targetCollectedDataEpoch)
		check(actual.retainedFromMs == expected.retainedFromMs)
		check(actual.deletedAtMs == expected.deletedAtMs)
	}

	private fun CollectedDataDeletionOperation.encode(): String = buildString {
		appendLine(DELETION_MARKER_VERSION)
		appendLine(operationId)
		appendLine(targetCollectedDataEpoch)
		appendLine(retainedFromMs ?: NULL_RETAINED_FROM)
		appendLine(deletedAtMs)
	}

	private fun decodeDeletionOperation(file: File): CollectedDataDeletionOperation? = try {
		val lines = file.readLines()
		if (lines.size != DELETION_MARKER_LINE_COUNT ||
			lines[0] != DELETION_MARKER_VERSION
		) {
			return null
		}
		CollectedDataDeletionOperation(
			operationId = lines[1].also { UUID.fromString(it) },
			targetCollectedDataEpoch = lines[2].toLong(),
			retainedFromMs = lines[3].toLong().takeUnless { it == NULL_RETAINED_FROM },
			deletedAtMs = lines[4].toLong(),
		)
	} catch (_: Exception) {
		null
	}

	private fun clearDeletionMarker(): CollectedDataDeletionCompletion? {
		val parent = checkNotNull(markerFile.parentFile)
		if (markerFile.exists()) {
			if (clearingMarkerFile.exists() && !deleteMarkerFile(clearingMarkerFile)) {
				return CollectedDataDeletionCompletion.Retryable(
					CollectedDataDeletionReconciliationFailure.DeletionMarkerRemoval,
				)
			}
			val renamed = try {
				markerFile.renameTo(clearingMarkerFile)
			} catch (_: Exception) {
				false
			}
			if (!renamed) {
				return CollectedDataDeletionCompletion.Retryable(
					CollectedDataDeletionReconciliationFailure.DeletionMarkerRemoval,
				)
			}
			try {
				directorySync(parent)
			} catch (_: Exception) {
				return CollectedDataDeletionCompletion.Unverifiable(
					CollectedDataDeletionReconciliationFailure.DeletionMarkerDurability,
				)
			}
		}
		if (clearingMarkerFile.exists() && !deleteMarkerFile(clearingMarkerFile)) {
			return CollectedDataDeletionCompletion.Retryable(
				CollectedDataDeletionReconciliationFailure.DeletionMarkerRemoval,
			)
		}
		return try {
			directorySync(parent)
			null
		} catch (_: Exception) {
			CollectedDataDeletionCompletion.Unverifiable(
				CollectedDataDeletionReconciliationFailure.DeletionMarkerDurability,
			)
		}
	}

	private fun deleteMarkerFile(file: File): Boolean = try {
		markerDelete(file)
	} catch (_: Exception) {
		false
	}

	private sealed interface DeletionJournalPreparation {
		data class Ready(
			val operation: CollectedDataDeletionOperation,
		) : DeletionJournalPreparation

		data class Failed(
			val completion: CollectedDataDeletionCompletion,
			val operation: CollectedDataDeletionOperation? = null,
		) : DeletionJournalPreparation
	}

	private sealed interface ProviderFenceAttempt<out T> {
		data class Completed<T>(val value: T) : ProviderFenceAttempt<T>
		data object Failed : ProviderFenceAttempt<Nothing>
		data object TimedOut : ProviderFenceAttempt<Nothing>
	}

	private data class ProviderFenceDependencies(
		val activity: ActivityRegistrationArbiter?,
		val purposes: TrackingPurposeDeletionFencer?,
		val failures: List<CollectedDataProviderFenceFailure>,
	)

	private data class ProviderFenceFlight(
		val pass: ProviderFencePass,
		val task: Deferred<Result<Any?>>,
	)

	private enum class ProviderFencePass {
		PRE_QUIESCENCE,
		POST_QUIESCENCE,
		HOLD_CLOSED,
	}

	private data class DeletionFlight(
		val operationIdentity: String,
		val task: Deferred<CollectedDataDeletionCompletion>,
		var awaiterCount: Int,
	)

	private sealed interface DeletionStart {
		data class Await(
			val flight: DeletionFlight,
		) : DeletionStart

		data class Immediate(
			val completion: CollectedDataDeletionCompletion,
		) : DeletionStart
	}

	private companion object {
		const val PROVIDER_FENCE_TIMEOUT_MS = 30_000L
		const val ACTIVITY_FENCE_FLIGHT = "activity"
		const val PURPOSE_FENCE_FLIGHT = "purpose"
		const val WRITER_FENCE_FLIGHT = "writers"
		const val DELETION_MARKER_VERSION = "TRACKER_COLLECTED_DATA_DELETION_V2"
		const val DELETION_MARKER_LINE_COUNT = 5
		const val NULL_RETAINED_FROM = -1L
		val DURABLE_AMBIENT_SOURCES = setOf(
			TrackingSourceComponent.STEPS,
			TrackingSourceComponent.WIFI,
			TrackingSourceComponent.CELL,
		)
		val UNVERIFIABLE_RETENTION_FAILURES = setOf(
			RetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED,
			RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
			RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
		val RETIRED_DATABASE_NAMES = listOf(
			LEGACY_DATABASE_NAME,
			"stats_database",
			"challenge_database",
		)

		fun syncDirectory(directory: File) {
			val descriptor = Os.open(
				directory.path,
				OsConstants.O_RDONLY,
				0,
			)
			try {
				Os.fsync(descriptor)
			} finally {
				Os.close(descriptor)
			}
		}
	}
}
