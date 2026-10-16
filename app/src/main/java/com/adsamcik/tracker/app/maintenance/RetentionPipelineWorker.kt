package com.adsamcik.tracker.app.maintenance

import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import dev.tracebox.Tracebox
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ActivityCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.RetentionFloorDestructivePlan
import com.adsamcik.tracker.shared.base.database.RetentionFloorSettlementOperation
import com.adsamcik.tracker.shared.base.database.RetentionFloorOperationLookupResult
import com.adsamcik.tracker.shared.base.database.RetentionFloorSettlementDisposition
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionCompletionResult
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionContinuationResult
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionPlanResult
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionReceipt
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionStartResult
import com.adsamcik.tracker.shared.base.database.RoomTruncateImportedActivityRetention
import com.adsamcik.tracker.shared.base.database.SourceEventStoragePruneResult
import com.adsamcik.tracker.shared.base.database.TruncateImportedActivityRetentionRequest
import com.adsamcik.tracker.shared.base.database.TruncateImportedActivityRetentionResult
import com.adsamcik.tracker.shared.base.database.markAuthenticatedStepsRunsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.pruneCapturedActivityFactsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.pruneImportedStepsSegmentsBefore
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.pruneSourceEventStorageBefore
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.ApprovedRetentionOperation
import com.adsamcik.tracker.shared.preferences.retention.ExactApprovedRetentionOperationResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.projection.ActivityControlRetentionMaintenance
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import javax.inject.Provider

@HiltWorker
// One approved request owns its settlement, cancellation receipt and source cleanup sequence.
@Suppress("LargeClass", "TooManyFunctions")
class RetentionPipelineWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val retentionConfigStore: RetentionConfigStore,
    private val collectedDataLifecycleStore: CollectedDataLifecycleStore,
    private val appDatabaseProvider: Provider<AppDatabase>,
    private val migrationBackupRepository: DatabaseMigrationBackupRepository,
	private val trackingStartupGate: TrackingStartupGate,
	private val stepsSessionFactProjectionLaneProvider: Provider<StepsSessionFactProjectionLane>,
	private val activityControlRetentionMaintenance: ActivityControlRetentionMaintenance,
	private val importedActivityRetentionProvider: Provider<RoomTruncateImportedActivityRetention>,

	private val cellCapturedRetentionService: CellCapturedRetentionService,
	private val wifiCapturedRetentionService: WifiCapturedRetentionService,
	private val retentionFloorSettlement: RetentionFloorSettlement,
	private val periodicAmbientRetentionMaintenance: PeriodicAmbientRetentionMaintenance,
	private val workExecutionCoordinator: RetentionWorkExecutionCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
		val startupGeneration = trackingStartupGate.currentGeneration
		val expectedSettlementOperationId = inputData.getString(SETTLEMENT_OPERATION_ID_KEY)
		if (expectedSettlementOperationId != null &&
			(expectedSettlementOperationId.isBlank() ||
				inputData.getLong(SETTLEMENT_COLLECTED_DATA_EPOCH_KEY, MISSING_SETTLEMENT_EPOCH) < 0L)
		) {
			return Result.failure()
		}
		startupResult(expectedSettlementOperationId)?.let { return it }
		val admitted = admitExecution(startupGeneration) ?: return Result.retry()
		return runAdmittedExecution(admitted, startupGeneration)
	}

	private suspend fun startupResult(expectedSettlementOperationId: String?): Result? =
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> null
			is TrackingStartupResult.RetryableFailure -> Result.retry()
			is TrackingStartupResult.Blocked -> if (expectedSettlementOperationId == null) {
				Result.success()
			} else {
				Result.retry()
			}
		}

	private suspend fun admitExecution(
		startupGeneration: Long,
	): Triple<AppDatabase, RetentionWorkExecutionStartResult, Boolean>? = try {
			trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
				val database = appDatabaseProvider.get()
				// CONTROL cleanup has independent debt; it must not postpone approved history work.
				val controlCleanupPending = try {
					activityControlRetentionMaintenance.pruneDue()
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: Exception) {
					true
				}
				val started = workExecutionCoordinator.begin(
					database = database,
					workRequestId = id.toString(),
					workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					runAttemptCount = runAttemptCount,
					startedAtMs = System.currentTimeMillis(),
				)
				Triple(database, started, controlCleanupPending)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			null
		}

	private suspend fun runAdmittedExecution(
		admitted: Triple<AppDatabase, RetentionWorkExecutionStartResult, Boolean>,
		startupGeneration: Long,
	): Result = when (val started = admitted.second) {
		is RetentionWorkExecutionStartResult.Open -> runOpenExecution(
			admitted.first, started.receipt, startupGeneration, admitted.third,
		)
		is RetentionWorkExecutionStartResult.Retryable -> Result.retry()
		RetentionWorkExecutionStartResult.AlreadyCompleted ->
			if (admitted.third) Result.retry() else Result.success()
		RetentionWorkExecutionStartResult.CancellationRequested,
		RetentionWorkExecutionStartResult.AbandonedByCancellation,
		RetentionWorkExecutionStartResult.SupersededByFullDeletion -> Result.success()
	}

	private suspend fun runOpenExecution(
		appDatabase: AppDatabase,
		execution: RetentionWorkExecutionReceipt,
		startupGeneration: Long,
		controlCleanupPending: Boolean,
	): Result {
		val result = when (
			val operation = retentionConfigStore.withExactApprovedOperation { authority ->
				doApprovedWork(authority, appDatabase, execution, startupGeneration)
			}
		) {
			is ExactApprovedRetentionOperationResult.Completed -> operation.value
			is ExactApprovedRetentionOperationResult.Rejected -> Result.retry()
		}
		if (result != Result.success()) return result
		return completeExecution(appDatabase, execution, controlCleanupPending)
	}

	private suspend fun completeExecution(
		appDatabase: AppDatabase,
		execution: RetentionWorkExecutionReceipt,
		controlCleanupPending: Boolean,
	): Result = try {
			when (
				workExecutionCoordinator.complete(
					database = appDatabase,
					receipt = execution,
					completedAtMs = maxOf(System.currentTimeMillis(), execution.startedAtMs),
				)
			) {
				RetentionWorkExecutionCompletionResult.Completed ->
					if (controlCleanupPending) Result.retry() else Result.success()
				RetentionWorkExecutionCompletionResult.CancellationRequested,
				RetentionWorkExecutionCompletionResult.AbandonedByCancellation,
				RetentionWorkExecutionCompletionResult.SupersededByFullDeletion,
				-> Result.success()
				is RetentionWorkExecutionCompletionResult.Retryable -> Result.retry()
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			Result.retry()
		}

	private data class ApprovedWork(
		val authority: ApprovedRetentionOperation,
		val database: AppDatabase,
		val execution: RetentionWorkExecutionReceipt,
		val startupGeneration: Long,
	)

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private suspend fun doApprovedWork(
		authority: ApprovedRetentionOperation,
		appDatabase: AppDatabase,
		execution: RetentionWorkExecutionReceipt,
		startupGeneration: Long,
	): Result {
		authority.requireIdentity()
		val storedConfig = authority.configuration
		val config = storedConfig.forWorker()
		val expectedOperationId = inputData.getString(SETTLEMENT_OPERATION_ID_KEY)
		val expectedEpoch = expectedOperationId?.let {
			inputData.getLong(SETTLEMENT_COLLECTED_DATA_EPOCH_KEY, MISSING_SETTLEMENT_EPOCH)
		}
		val work = ApprovedWork(authority, appDatabase, execution, startupGeneration)
		initialAuthorityResult(work)?.let { return it }
		val pending = try {
			readPendingOperation(work, expectedOperationId)
		} catch (_: RetentionExecutionDeferredException) {
			return Result.retry()
		}
		if (expectedOperationId != null && pending == null) {
			return exactSettlementTerminalResult(appDatabase, expectedOperationId, requireNotNull(expectedEpoch))
		}
		if (!storedConfig.autoPurgeEnabled && !storedConfig.autoCleanupEnabled &&
			pending == null && execution.destructivePlan == null) {
			return Result.success()
		}
		return runApprovedMaintenance(work, config, pending)
	}

	private suspend fun initialAuthorityResult(work: ApprovedWork): Result? = try {
		requireDestructiveAuthority(work.database, work.execution, work.startupGeneration, work.authority)
		null
	} catch (_: RetentionExecutionStoppedException) {
		Result.success()
	} catch (_: RetentionExecutionDeferredException) {
		Result.retry()
	} catch (_: StartupGenerationChangedException) {
		Result.retry()
	}

	private suspend fun readPendingOperation(
		work: ApprovedWork,
		expectedOperationId: String?,
	): RetentionFloorSettlementOperation? = try {
		when (val lookup = retentionFloorSettlement.pendingOperation(
			work.database, work.execution, expectedOperationId,
		)) {
			is RetentionFloorOperationLookupResult.Available -> lookup.operation
			is RetentionFloorOperationLookupResult.IncompatibleExecutor,
			is RetentionFloorOperationLookupResult.ExecutionOwned -> throw RetentionExecutionDeferredException()
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		// An unreadable or differently owned journal keeps the same worker retry outcome.
		throw RetentionExecutionDeferredException()
	}

	private suspend fun runApprovedMaintenance(
		work: ApprovedWork,
		config: RetentionConfigState,
		pending: RetentionFloorSettlementOperation?,
	): Result = try {
		performApprovedMaintenance(work, config, pending)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: RetentionExecutionStoppedException) {
		Result.success()
	} catch (_: RetentionExecutionDeferredException) {
		Result.retry()
	} catch (_: StartupGenerationChangedException) {
		Result.retry()
	} catch (_: ActivityRetentionDeferredException) {
		Result.retry()
	} catch (_: RetentionFloorSettlementDeferredException) {
		Result.retry()
	} catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
		Tracebox.log.error(error, TrackerTraceboxTemplates.DATA_RETENTION_FAILED)
		Result.retry()
	}

	private suspend fun performApprovedMaintenance(
		work: ApprovedWork,
		config: RetentionConfigState,
		pending: RetentionFloorSettlementOperation?,
	): Result {
		requireReadyGeneration(work.startupGeneration)
		val requestedAtMs = pending?.requestedAtMs
			?: work.execution.destructivePlan?.requestedAtMs ?: work.execution.startedAtMs
		val proposed = pending?.destructivePlan ?: work.execution.destructivePlan ?: destructivePlan(
			config, requestedAtMs, collectedDataLifecycleStore.snapshot().retainedFromMs,
		)
		if (!proposed.isDestructive && pending == null) {
			return Result.success()
		}
		val attached = attachDestructivePlan(work, proposed)
		val settled = settleRequestedFloor(work, pending, attached, requestedAtMs)
		return runPlannedMaintenance(work, settled, attached, requestedAtMs)
	}

	private suspend fun runPlannedMaintenance(
		work: ApprovedWork,
		settled: RetentionFloorSettlementResult.Settled?,
		attached: RetentionFloorDestructivePlan,
		requestedAtMs: Long,
	): Result {
		val operationNow = settled?.sourceMaintenanceAtMs ?: requestedAtMs
		val operationPlan = settled?.destructivePlan ?: attached
		if (settled?.sourceMaintenanceCompleted == true) {
			return finishSettlement(work, settled, operationNow)
		}
		if (settled != null) {
			requireDestructiveAuthority(work.database, work.execution, work.startupGeneration, work.authority)
			migrationBackupRepository.deleteAll()
		}
		val maintenanceDeferred = purgeSourceData(work, operationPlan, settled, operationNow)
		return when {
			maintenanceDeferred -> Result.retry()
			settled == null -> Result.success()
			else -> finishSettlement(work, settled, operationNow)
		}
	}

	private suspend fun purgeSourceData(
		work: ApprovedWork,
		operationPlan: RetentionFloorDestructivePlan,
		settled: RetentionFloorSettlementResult.Settled?,
		operationNow: Long,
	): Boolean {
		val rawResult = purgePlannedRawData(work, operationPlan.rawRetentionCutoffMs, settled, operationNow)
		var maintenanceDeferred = maintainSettledSources(work, settled, operationNow)
		if (!maintenanceDeferred) {
			maintenanceDeferred = pruneSettledSourceEvents(work, operationPlan.sourceEventRetentionCutoffMs, settled)
		}
		val radioResult = purgeDependentData(work, operationPlan, settled, operationNow)
		return rawResult == RawRetentionResult.DEFERRED_FOR_PENDING_SIGNALS ||
			radioResult == RadioRetentionResult.DEFERRED_FOR_PENDING_SIGNALS || maintenanceDeferred
	}

	private suspend fun attachDestructivePlan(
		work: ApprovedWork,
		plan: RetentionFloorDestructivePlan,
	): RetentionFloorDestructivePlan = when (val attached = workExecutionCoordinator.attachPlan(
		database = work.database, receipt = work.execution, plan = plan,
	)) {
		is RetentionWorkExecutionPlanResult.Attached -> requireNotNull(attached.receipt.destructivePlan)
		RetentionWorkExecutionPlanResult.CancellationRequested,
		RetentionWorkExecutionPlanResult.AbandonedByCancellation -> throw RetentionExecutionStoppedException()
		is RetentionWorkExecutionPlanResult.Retryable -> throw RetentionExecutionDeferredException()
	}

	private suspend fun settleRequestedFloor(
		work: ApprovedWork,
		pending: RetentionFloorSettlementOperation?,
		plan: RetentionFloorDestructivePlan,
		requestedAtMs: Long,
	): RetentionFloorSettlementResult.Settled? {
		val floor = plan.requestedRetainedFromMs ?: return null
		return when (val settlement = retentionFloorSettlement.settle(
			database = work.database,
			lifecycleStore = collectedDataLifecycleStore,
			startupGate = trackingStartupGate,
			expectedStartupGeneration = work.startupGeneration,
			requestedRetainedFromMs = floor,
			operationId = pending?.operationId
				?: work.authority.retentionFloorOperationId(floor, requestedAtMs, work.execution.executionId),
			updatedAtMs = plan.requestedAtMs,
			workExecutionId = pending?.workExecutionId ?: work.execution.executionId,
			destructivePlan = plan,
			verifyApprovedOperation = {
				requireDestructiveAuthority(work.database, work.execution, work.startupGeneration, work.authority)
			},
		)) {
			is RetentionFloorSettlementResult.Settled -> settlement
			RetentionFloorSettlementResult.StartupGenerationChanged -> throw StartupGenerationChangedException
			is RetentionFloorSettlementResult.Retryable -> throw RetentionFloorSettlementDeferredException
		}
	}

	private suspend fun purgePlannedRawData(
		work: ApprovedWork,
		cutoff: Long?,
		settled: RetentionFloorSettlementResult.Settled?,
		operationNow: Long,
	): RawRetentionResult {
		if (cutoff == null) {
			return RawRetentionResult.NOT_APPLICABLE
		}
		val lifecycle = requireNotNull(settled).lifecycle
		val exactFloor = requireNotNull(lifecycle.retainedFromMs)
		check(exactFloor >= cutoff)
		val result = purgeRawData(
			work.database, exactFloor, lifecycle, operationNow,
			work.startupGeneration, work.authority, work.execution,
		)
		requireDestructiveAuthority(work.database, work.execution, work.startupGeneration, work.authority)
		trackingStartupGate.withReadyGenerationOperation(work.startupGeneration) {
			requireDestructiveAuthority(work.database, work.execution, work.startupGeneration, work.authority)
			stepsSessionFactProjectionLaneProvider.get().drainAvailable()
		} ?: throw StartupGenerationChangedException
		return result
	}

	private suspend fun maintainSettledSources(
		work: ApprovedWork,
		settled: RetentionFloorSettlementResult.Settled?,
		operationNow: Long,
	): Boolean {
		if (settled == null) {
			return false
		}
		requireDestructiveAuthority(work.database, work.execution, work.startupGeneration, work.authority)
		var deferred = periodicAmbientRetentionMaintenance.run(
			database = work.database, lifecycle = settled.lifecycle, appliedAtMs = operationNow,
			verifyExecutionContinuation = {
				workExecutionCoordinator.requireExecutionContinuation(work.database, work.execution)
			},
		) is PeriodicAmbientRetentionResult.Retryable
		val exactFloor = requireNotNull(settled.lifecycle.retainedFromMs)
		// Retained WAL remains available until all-day and both captured radio owners finish.
		if (!pruneCapturedCellData(
			work.database, exactFloor, operationNow, work.startupGeneration, work.authority, work.execution,
		)) {
			deferred = true
		}
		if (!pruneCapturedWifiData(
			work.database, exactFloor, operationNow, work.startupGeneration, work.authority, work.execution,
		)) {
			deferred = true
		}
		return deferred
	}

	private suspend fun pruneSettledSourceEvents(
		work: ApprovedWork,
		cutoff: Long?,
		settled: RetentionFloorSettlementResult.Settled?,
	): Boolean {
		if (cutoff == null) {
			return false
		}
		val exactFloor = requireNotNull(settled?.lifecycle?.retainedFromMs)
		check(exactFloor >= cutoff)
		requireDestructiveAuthority(work.database, work.execution, work.startupGeneration, work.authority)
		return work.database.pruneSourceEventStorageBefore(
			createdBeforeMs = exactFloor,
			verifyCollectedDataAccess = {
				requireDestructiveAuthority(work.database, work.execution, work.startupGeneration, work.authority)
			},
		) is SourceEventStoragePruneResult.Deferred
	}

	private suspend fun purgeDependentData(
		work: ApprovedWork,
		plan: RetentionFloorDestructivePlan,
		settled: RetentionFloorSettlementResult.Settled?,
		operationNow: Long,
	): RadioRetentionResult {
		val plannedRadioCutoff = listOfNotNull(plan.rawRetentionCutoffMs, plan.wifiCellRetentionCutoffMs)
			.maxOrNull()?.let { cutoff ->
				val settledCutoff = settled?.lifecycle?.retainedFromMs
				if (settledCutoff != null) {
					check(settledCutoff >= cutoff)
					settledCutoff
				} else {
					cutoff
				}
			}
		val radioResult = purgeWifiCellData(
			work.database, plannedRadioCutoff, work.startupGeneration, work.authority, work.execution,
		)
		purgeTripData(
			work.database, plan.tripRetentionCutoffMs, operationNow,
			work.startupGeneration, work.authority, work.execution,
		)
		purgeDailySummaries(
			work.database, plan.dailySummaryRetentionCutoffDay, work.startupGeneration, work.authority, work.execution,
		)
		purgeExplorationData(
			work.database, plan.explorationRetentionCutoffMs, work.startupGeneration, work.authority, work.execution,
		)
		purgeOperationalData(
			work.database, plan.operationalRetentionCutoffMs, work.startupGeneration, work.authority, work.execution,
		)
		return radioResult
	}

	private suspend fun finishSettlement(
		work: ApprovedWork,
		settled: RetentionFloorSettlementResult.Settled,
		operationNow: Long,
	): Result = completeSettlement(
		work.database, work.startupGeneration, settled, operationNow, work.authority, work.execution,
	)

	private suspend fun completeSettlement(
		database: AppDatabase,
		startupGeneration: Long,
		settlement: RetentionFloorSettlementResult.Settled,
		completedAtMs: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
	): Result = when (retentionFloorSettlement.complete(
		database = database,
		startupGate = trackingStartupGate,
		expectedStartupGeneration = startupGeneration,
		settlement = settlement,
		completedAtMs = completedAtMs,
		verifyApprovedOperation = {
			requireDestructiveAuthority(
				database,
				execution,
				startupGeneration,
				authority,
			)
		},
	)) {
		RetentionFloorSettlementCompletionResult.Completed,
			is RetentionFloorSettlementCompletionResult.SupersededByFullDeletion,
			-> Result.success()
		RetentionFloorSettlementCompletionResult.StartupGenerationChanged,
		RetentionFloorSettlementCompletionResult.Retryable,
			-> Result.retry()
	}

    private suspend fun purgeRawData(
        db: AppDatabase,
        cutoff: Long,
        lifecycle: CollectedDataLifecycleSnapshot,
        updatedAtMs: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
    ): RawRetentionResult {
        return db.withTransaction {
			requireDestructiveAuthority(db, execution, startupGeneration, authority)
			try {
				val sourceEvidenceStateDao = db.sourceEvidenceStateDao()
				val retainedFromMs = requireNotNull(lifecycle.retainedFromMs) {
					"Raw retention must establish a durable retained-from floor"
				}
				db.markAuthenticatedStepsRunsAffectedByRetentionFloor(
					beforeMs = retainedFromMs,
					collectedDataEpoch = lifecycle.epoch,
					markedAtMs = updatedAtMs,
				)
				val activityState = requireNotNull(sourceEvidenceStateDao.get()) {
					"Activity retention requires source-evidence authority"
				}
				val activityRetainedFromMs = requireNotNull(activityState.retainedFromMs)
				when (importedActivityRetentionProvider.get().truncate(
					TruncateImportedActivityRetentionRequest(
						expectedCollectedDataEpoch = activityState.collectedDataEpoch,
						expectedSourceEvidenceRevision = activityState.revision,
						retainedFromMs = activityRetainedFromMs,
						retainedAtMs = updatedAtMs,
					),
				)) {
					is TruncateImportedActivityRetentionResult.Truncated,
					TruncateImportedActivityRetentionResult.NoChange -> Unit
					else -> throw ActivityRetentionDeferredException
				}
				if (db.pendingSignalDao().hasAny()) {
					return@withTransaction RawRetentionResult.DEFERRED_FOR_PENDING_SIGNALS
				}
				if (hasCapturedActivityRetentionAuthority(db)) {
					when (db.pruneCapturedActivityFactsAffectedByRetentionFloor(
						beforeMs = activityRetainedFromMs,
						expectedCollectedDataEpoch = activityState.collectedDataEpoch,
						markedAtMs = updatedAtMs,
					)) {
						is ActivityCapturedRetentionResult.Pruned,
						ActivityCapturedRetentionResult.NoChange -> Unit
						is ActivityCapturedRetentionResult.Blocked -> throw ActivityRetentionDeferredException
					}
				}
				purgeRetainedRawRows(db, cutoff, lifecycle, retainedFromMs, updatedAtMs)
				RawRetentionResult.PURGED
			} finally {
				requireDestructiveAuthority(db, execution, startupGeneration, authority)
			}
        }
    }

	private suspend fun purgeRetainedRawRows(
		db: AppDatabase,
		cutoff: Long,
		lifecycle: CollectedDataLifecycleSnapshot,
		retainedFromMs: Long,
		updatedAtMs: Long,
	) {
		// A derived run is only auditable while its complete raw source range remains.
		// Cascades remove states, visits, hypotheses, and lineage links atomically.
		db.trajectoryReconstructionDao().deleteWithSourceBefore(cutoff)
		val observationDao = db.locationObservationDao()
		observationDao.deleteOlderThan(cutoff)
		db.locationProjectionDao().deleteObservationsOlderThan(cutoff)
		db.locationObservationDecisionDao().apply {
			deleteOlderThan(cutoff)
			deleteWithoutObservation()
		}
		db.trackerStateEventDao().deleteOlderThan(cutoff)
		db.locationSampleDao().deleteOlderThan(cutoff)
		db.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(
			beforeMs = retainedFromMs,
			collectedDataEpoch = lifecycle.epoch,
			markedAtMs = updatedAtMs,
		)
		db.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
			beforeMs = retainedFromMs,
			collectedDataEpoch = lifecycle.epoch,
			markedAtMs = updatedAtMs,
		)
		db.stepIntervalDao().deleteOlderThan(cutoff)
		db.activitySnapshotDao().deleteOlderThan(cutoff)
		db.trackerRunDao().deleteOlderThan(cutoff)
		db.pressureSampleDao().deleteOlderThan(cutoff)
		db.skiRunSegmentDao().deleteOlderThan(cutoff)
		db.quarantinedSignalDao().deleteAcquiredBefore(cutoff)
	}

    private enum class RawRetentionResult {
        NOT_APPLICABLE,
        PURGED,
        DEFERRED_FOR_PENDING_SIGNALS,
    }

	/** Empty dormant storage is not permission to activate or require the canonical Activity writer. */
	private suspend fun hasCapturedActivityRetentionAuthority(db: AppDatabase): Boolean {
		val owner = db.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
		)
		val facts = db.activityCapturedFactDao()
		val counts = listOf(facts.revisionCount(), facts.fragmentCount(), facts.evidenceCount(), facts.cursorCount())
		if (counts.any { it < 0L }) throw ActivityRetentionDeferredException
		val canonicalOwner = owner != null &&
			owner.owner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS &&
			owner.ownerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		return counts.any { it != 0L } || canonicalOwner
	}

	private suspend fun pruneCapturedCellData(
		db: AppDatabase,
		retainedFromMs: Long,
		now: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
	): Boolean {
		requireDestructiveAuthority(db, execution, startupGeneration, authority)
		val result = trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			requireDestructiveAuthority(db, execution, startupGeneration, authority)
			cellCapturedRetentionService.prune(
				database = db,
				beforeMs = retainedFromMs,
				markedAtMs = now,
			)
		} ?: throw StartupGenerationChangedException
		requireDestructiveAuthority(db, execution, startupGeneration, authority)
		return when (result) {
			is com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult.Pruned,
				com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult.NoChange -> true
			is com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult.Blocked -> false
		}
    }

	private suspend fun pruneCapturedWifiData(
		db: AppDatabase,
		retainedFromMs: Long,
		now: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
	): Boolean {
		requireDestructiveAuthority(db, execution, startupGeneration, authority)
		val result = trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			requireDestructiveAuthority(db, execution, startupGeneration, authority)
			wifiCapturedRetentionService.prune(
				database = db,
				beforeMs = retainedFromMs,
				markedAtMs = now,
			)
		} ?: throw StartupGenerationChangedException
		requireDestructiveAuthority(db, execution, startupGeneration, authority)
		return when (result) {
			is com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionResult.Pruned,
				com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionResult.NoChange -> true
			is com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionResult.Blocked -> false
		}
	}

    private suspend fun purgeWifiCellData(
		db: AppDatabase,
		cutoff: Long?,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
	): RadioRetentionResult {
		if (cutoff == null) return RadioRetentionResult.NOT_APPLICABLE
		requireDestructiveAuthority(db, execution, startupGeneration, authority)
		if (db.pendingSignalDao().hasAny()) {
			return RadioRetentionResult.DEFERRED_FOR_PENDING_SIGNALS
		}
		val pruned = db.withTransaction {
			requireDestructiveAuthority(db, execution, startupGeneration, authority)
			try {
				if (db.pendingSignalDao().hasAny()) return@withTransaction false
				db.cellSampleDao().deleteOlderThan(cutoff)
				db.wifiObservationDao().deleteOlderThan(cutoff)
				db.legacyPreservedDataDao().deleteWifiCountsOlderThan(cutoff)
				true
			} finally {
				requireDestructiveAuthority(db, execution, startupGeneration, authority)
			}
        }
		return if (pruned) {
			RadioRetentionResult.PURGED
		} else {
			RadioRetentionResult.DEFERRED_FOR_PENDING_SIGNALS
		}
    }

    private suspend fun purgeTripData(
		db: AppDatabase,
		cutoff: Long?,
		now: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
	) {
		if (cutoff == null) return
		db.withTransaction {
			requireDestructiveAuthority(db, execution, startupGeneration, authority)
			try {
				db.organizationAnnotationDao().purgeFreeBefore(cutoff)
				db.organizationAnnotationDao().suppressExpiredOverrides(cutoff, now)
				db.sessionSegmentDao().deleteOlderThan(cutoff)
				db.legacyPreservedDataDao().deleteRejectedSessionsOlderThan(cutoff)
				db.organizationAnnotationDao().pruneUnusedSuppression()
				db.pruneImportedStepsSegmentsBefore(cutoff, now)
			} finally {
				requireDestructiveAuthority(db, execution, startupGeneration, authority)
			}
		}
    }

    private suspend fun purgeDailySummaries(
		db: AppDatabase,
		cutoffDay: Long?,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
	) {
		if (cutoffDay == null) return
		db.withTransaction {
			requireDestructiveAuthority(db, execution, startupGeneration, authority)
			try {
				db.dailySummaryDao().deleteOlderThan(cutoffDay)
			} finally {
				requireDestructiveAuthority(db, execution, startupGeneration, authority)
			}
		}
    }

    private suspend fun purgeExplorationData(
		db: AppDatabase,
		cutoff: Long?,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
	) {
		if (cutoff == null) return
		db.withTransaction {
			requireDestructiveAuthority(db, execution, startupGeneration, authority)
			try {
				db.explorationCellDao().deleteOlderThan(cutoff)
				db.explorationStreakDao().deleteOlderThan(cutoff)
				db.achievementProgressDao().deleteOlderThan(cutoff)
			} finally {
				requireDestructiveAuthority(db, execution, startupGeneration, authority)
			}
		}
    }

	private suspend fun purgeOperationalData(
		db: AppDatabase,
		rawCutoff: Long?,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
	) {
		if (rawCutoff == null) return
		db.withTransaction {
			requireDestructiveAuthority(db, execution, startupGeneration, authority)
			try {
				val domainEventDao = db.domainEventDao()
				val cursorCutoff = domainEventDao.getMinimumCursorTimestampMs()
				// With no consumer cursors yet, retention falls back to the configured raw cutoff.
				val domainEventCutoff = cursorCutoff?.let { minOf(rawCutoff, it) } ?: rawCutoff
				domainEventDao.deleteOlderThan(domainEventCutoff)
				db.exportLogDao().deleteOlderThan(rawCutoff)
			} finally {
				requireDestructiveAuthority(db, execution, startupGeneration, authority)
			}
		}
	}

	private fun requireReadyGeneration(startupGeneration: Long) {
		if (!trackingStartupGate.isReady ||
			trackingStartupGate.currentGeneration != startupGeneration
		) {
			throw StartupGenerationChangedException
		}
	}

	private suspend fun requireDestructiveAuthority(
		database: AppDatabase,
		execution: RetentionWorkExecutionReceipt,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
	) {
		workExecutionCoordinator.requireExecutionContinuation(database, execution)
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
	}

	private suspend fun exactSettlementTerminalResult(
		database: AppDatabase,
		operationId: String,
		expectedCollectedDataEpoch: Long,
	): Result = try {
		when (
			retentionFloorSettlement.disposition(
				database,
				operationId,
				expectedCollectedDataEpoch,
			)
		) {
			RetentionFloorSettlementDisposition.Completed,
			is RetentionFloorSettlementDisposition.SupersededByFullDeletion,
			-> Result.success()
			RetentionFloorSettlementDisposition.Active,
			RetentionFloorSettlementDisposition.Missing,
			-> Result.retry()
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		Result.retry()
	}

    private fun RetentionConfigState.forWorker(): RetentionConfigState {
        if (!autoCleanupEnabled) return this
        val days = if (dataRetentionYears == 0) 0 else dataRetentionYears.coerceAtLeast(1) * DAYS_PER_YEAR
        return copy(
            rawDataRetentionDays = days,
            wifiCellRetentionDays = days,
            tripRetentionDays = days,
            dailySummaryRetentionDays = days,
            explorationRetentionDays = days,
            autoPurgeEnabled = true,
        )
    }

    companion object {
        internal const val WORK_NAME = "APP.DATA_RETENTION_PIPELINE_WEEKLY"
        internal const val LEGACY_WORK_NAME = "APP.DATA_RETENTION_WEEKLY"
		internal const val SETTLEMENT_OPERATION_ID_KEY =
			"retention_settlement_operation_id"
		internal const val SETTLEMENT_COLLECTED_DATA_EPOCH_KEY =
			"retention_settlement_collected_data_epoch"
		private const val MISSING_SETTLEMENT_EPOCH = -1L
		private const val DAYS_PER_YEAR = 365

		internal fun computeWifiCellCutoffMillis(retentionDays: Int, nowMs: Long): Long {
			return computeRetentionCutoffMillis(retentionDays, nowMs)
		}

		internal fun computeRetentionCutoffMillis(retentionDays: Int, nowMs: Long): Long {
			require(retentionDays > 0)
			return try {
				val retentionMs = Math.multiplyExact(
					retentionDays.toLong(),
					Time.DAY_IN_MILLISECONDS,
				)
				Math.subtractExact(nowMs, retentionMs).coerceAtLeast(0L)
			} catch (_: ArithmeticException) {
				0L
			}
		}

		private fun destructivePlan(
			config: RetentionConfigState,
			requestedAtMs: Long,
			currentRetainedFromMs: Long?,
		): RetentionFloorDestructivePlan {
			val rawCutoff = config.rawDataRetentionDays.takeUnless { it == 0 }?.let {
				computeRetentionCutoffMillis(it, requestedAtMs)
			}
			val wifiCellCutoff = config.wifiCellRetentionDays.takeUnless { it == 0 }?.let {
				computeRetentionCutoffMillis(it, requestedAtMs)
			}
			return RetentionFloorDestructivePlan(
				workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
				requestedAtMs = requestedAtMs,
				requestedRetainedFromMs =
					listOfNotNull(rawCutoff, wifiCellCutoff).maxOrNull()
						?: currentRetainedFromMs,
				rawRetentionCutoffMs = rawCutoff,
				sourceEventRetentionCutoffMs = rawCutoff,
				wifiCellRetentionCutoffMs = wifiCellCutoff,
				tripRetentionCutoffMs = config.tripRetentionDays.takeUnless { it == 0 }?.let {
					computeRetentionCutoffMillis(it, requestedAtMs)
				},
				dailySummaryRetentionCutoffDay =
					config.dailySummaryRetentionDays.takeUnless { it == 0 }?.let {
					computeRetentionCutoffMillis(it, requestedAtMs) /
						Time.DAY_IN_MILLISECONDS
				},
				explorationRetentionCutoffMs =
					config.explorationRetentionDays.takeUnless { it == 0 }?.let {
					computeRetentionCutoffMillis(it, requestedAtMs)
				},
				operationalRetentionCutoffMs = rawCutoff,
			)
		}

    }

	private object StartupGenerationChangedException : RuntimeException()
	private object ActivityRetentionDeferredException : RuntimeException()
	private object RetentionFloorSettlementDeferredException : RuntimeException()

	private enum class RadioRetentionResult {
		NOT_APPLICABLE,
		PURGED,
		DEFERRED_FOR_PENDING_SIGNALS,
	}
}
