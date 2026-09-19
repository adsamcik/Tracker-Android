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
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import javax.inject.Provider

@HiltWorker
class RetentionPipelineWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val retentionConfigStore: RetentionConfigStore,
    private val collectedDataLifecycleStore: CollectedDataLifecycleStore,
    private val appDatabaseProvider: Provider<AppDatabase>,
    private val migrationBackupRepository: DatabaseMigrationBackupRepository,
	private val trackingStartupGate: TrackingStartupGate,
	private val stepsSessionFactProjectionLaneProvider: Provider<StepsSessionFactProjectionLane>,
	private val importedActivityRetentionProvider: Provider<RoomTruncateImportedActivityRetention>,

	private val cellCapturedRetentionService: CellCapturedRetentionService,
	private val wifiCapturedRetentionService: WifiCapturedRetentionService,
	private val retentionFloorSettlement: RetentionFloorSettlement,
	private val periodicAmbientRetentionMaintenance: PeriodicAmbientRetentionMaintenance,
	private val workExecutionCoordinator: RetentionWorkExecutionCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
		val appDatabase = try {
			appDatabaseProvider.get()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return Result.retry()
		}
		val execution = when (val started = try {
			workExecutionCoordinator.begin(
				database = appDatabase,
				workRequestId = id.toString(),
				workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
				runAttemptCount = runAttemptCount,
				startedAtMs = System.currentTimeMillis(),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return Result.retry()
		}) {
			is RetentionWorkExecutionStartResult.Open -> started.receipt
			is RetentionWorkExecutionStartResult.Retryable -> return Result.retry()
			RetentionWorkExecutionStartResult.AlreadyCompleted,
			RetentionWorkExecutionStartResult.CancellationRequested,
			RetentionWorkExecutionStartResult.AbandonedByCancellation,
			RetentionWorkExecutionStartResult.SupersededByFullDeletion ->
				return Result.success()
		}
		val result = when (
			val operation = retentionConfigStore.withExactApprovedOperation { authority ->
				doApprovedWork(authority, appDatabase, execution)
			}
		) {
			is ExactApprovedRetentionOperationResult.Completed -> operation.value
			is ExactApprovedRetentionOperationResult.Rejected -> Result.retry()
		}
		if (result != Result.success()) return result
		return try {
			when (
				workExecutionCoordinator.complete(
					database = appDatabase,
					receipt = execution,
					completedAtMs = maxOf(System.currentTimeMillis(), execution.startedAtMs),
				)
			) {
				RetentionWorkExecutionCompletionResult.Completed,
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
	}

    private suspend fun doApprovedWork(
		authority: ApprovedRetentionOperation,
		appDatabase: AppDatabase,
		execution: RetentionWorkExecutionReceipt,
	): Result {
        authority.requireIdentity()
        val storedConfig = authority.configuration
        val config = storedConfig.forWorker()
		val expectedSettlementOperationId =
			inputData.getString(SETTLEMENT_OPERATION_ID_KEY)
		if (expectedSettlementOperationId != null && expectedSettlementOperationId.isBlank()) {
			return Result.failure()
		}
		val expectedSettlementEpoch = expectedSettlementOperationId?.let {
			inputData.getLong(SETTLEMENT_COLLECTED_DATA_EPOCH_KEY, MISSING_SETTLEMENT_EPOCH)
		}
		if (expectedSettlementEpoch != null && expectedSettlementEpoch < 0L) {
			return Result.failure()
		}
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return if (
				expectedSettlementOperationId == null
			) {
				Result.success()
			} else {
				exactSettlementTerminalResult(
					appDatabase,
					expectedSettlementOperationId,
					requireNotNull(expectedSettlementEpoch),
				)
			}
		}
		val startupGeneration = trackingStartupGate.currentGeneration
		try {
			requireDestructiveAuthority(appDatabase, execution, startupGeneration, authority)
		} catch (_: RetentionExecutionStoppedException) {
			return Result.success()
		} catch (_: RetentionExecutionDeferredException) {
			return Result.retry()
		}
		val pendingOperation = when (val lookup = try {
			retentionFloorSettlement.pendingOperation(
				appDatabase,
				execution,
				expectedSettlementOperationId,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return Result.retry()
		}) {
			is RetentionFloorOperationLookupResult.Available -> lookup.operation
			is RetentionFloorOperationLookupResult.IncompatibleExecutor -> return Result.retry()
			is RetentionFloorOperationLookupResult.ExecutionOwned -> return Result.retry()
		}
		if (expectedSettlementOperationId != null && pendingOperation == null) {
			return exactSettlementTerminalResult(
				appDatabase,
				expectedSettlementOperationId,
				requireNotNull(expectedSettlementEpoch),
			)
		}
		if (
			!storedConfig.autoPurgeEnabled &&
			!storedConfig.autoCleanupEnabled &&
			pendingOperation == null &&
			execution.destructivePlan == null
		) {
			return Result.success()
		}

        return try {
			requireReadyGeneration(startupGeneration)
			val requestedAtMs = pendingOperation?.requestedAtMs
				?: execution.destructivePlan?.requestedAtMs
				?: execution.startedAtMs
			val proposedPlan = pendingOperation?.destructivePlan
				?: execution.destructivePlan
				?: destructivePlan(
					config = config,
					requestedAtMs = requestedAtMs,
					currentRetainedFromMs = collectedDataLifecycleStore.snapshot().retainedFromMs,
				)
			if (!proposedPlan.isDestructive && pendingOperation == null) {
				return Result.success()
			}
			val destructivePlan = when (
				val attached = workExecutionCoordinator.attachPlan(
					database = appDatabase,
					receipt = execution,
					plan = proposedPlan,
				)
			) {
				is RetentionWorkExecutionPlanResult.Attached ->
					requireNotNull(attached.receipt.destructivePlan)
				RetentionWorkExecutionPlanResult.CancellationRequested,
				RetentionWorkExecutionPlanResult.AbandonedByCancellation ->
					return Result.success()
				is RetentionWorkExecutionPlanResult.Retryable -> return Result.retry()
			}
			val requestedFloor = destructivePlan.requestedRetainedFromMs
			val settledFloor = requestedFloor?.let { floor ->
				when (val settlement = retentionFloorSettlement.settle(
					database = appDatabase,
					lifecycleStore = collectedDataLifecycleStore,
					startupGate = trackingStartupGate,
					expectedStartupGeneration = startupGeneration,
					requestedRetainedFromMs = floor,
					operationId = pendingOperation?.operationId
						?: authority.retentionFloorOperationId(
							floor,
							requestedAtMs,
							execution.executionId,
						),
					updatedAtMs = destructivePlan.requestedAtMs,
					workExecutionId =
						pendingOperation?.workExecutionId ?: execution.executionId,
					destructivePlan = destructivePlan,
					verifyApprovedOperation = {
						requireDestructiveAuthority(
							appDatabase,
							execution,
							startupGeneration,
							authority,
						)
					},
				)) {
					is RetentionFloorSettlementResult.Settled -> settlement
					RetentionFloorSettlementResult.StartupGenerationChanged ->
						throw StartupGenerationChangedException
					is RetentionFloorSettlementResult.Retryable ->
						throw RetentionFloorSettlementDeferredException
				}
			}
			val operationNow = settledFloor?.sourceMaintenanceAtMs ?: requestedAtMs
			val operationPlan = settledFloor?.destructivePlan ?: destructivePlan
			val operationRawCutoff = operationPlan.rawRetentionCutoffMs
			if (settledFloor != null) {
				if (settledFloor.sourceMaintenanceCompleted) {
					return completeSettlement(
						appDatabase,
						startupGeneration,
						settledFloor,
						operationNow,
						authority,
						execution,
					)
				}
				requireDestructiveAuthority(appDatabase, execution, startupGeneration, authority)
				migrationBackupRepository.deleteAll()
			}
            val rawRetentionResult = if (operationRawCutoff == null) {
                RawRetentionResult.NOT_APPLICABLE
            } else {
				val lifecycle = requireNotNull(settledFloor).lifecycle
				val exactFloor = requireNotNull(lifecycle.retainedFromMs)
				check(exactFloor >= operationRawCutoff)
                val result = purgeRawData(
                	appDatabase,
					exactFloor,
                	lifecycle,
                	operationNow,
                	startupGeneration,
                	authority,
					execution,
                )
				requireDestructiveAuthority(
					appDatabase,
					execution,
					startupGeneration,
					authority,
				)
                trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
					requireDestructiveAuthority(
						appDatabase,
						execution,
						startupGeneration,
						authority,
					)
                	stepsSessionFactProjectionLaneProvider.get().drainAvailable()
                } ?: throw StartupGenerationChangedException
                result
            }
			var maintenanceDeferred = false
			settledFloor?.let { settlement ->
				requireDestructiveAuthority(
					appDatabase,
					execution,
					startupGeneration,
					authority,
				)
				if (periodicAmbientRetentionMaintenance.run(
						database = appDatabase,
						lifecycle = settlement.lifecycle,
						appliedAtMs = operationNow,
						verifyExecutionContinuation = {
							requireExecutionContinuation(
								appDatabase,
								execution,
							)
						},
					) is PeriodicAmbientRetentionResult.Retryable
				) {
					maintenanceDeferred = true
				}
				val exactFloor = requireNotNull(settlement.lifecycle.retainedFromMs)
				// Captured radio maintenance authenticates retained WAL after source settlement.
				if (!pruneCapturedCellData(
						appDatabase,
						exactFloor,
						operationNow,
						startupGeneration,
						authority,
						execution,
					)
				) {
					maintenanceDeferred = true
				}
				if (!pruneCapturedWifiData(
						appDatabase,
						exactFloor,
						operationNow,
						startupGeneration,
						authority,
						execution,
					)
				) {
					maintenanceDeferred = true
				}
			}
			val sourceEventCutoff = operationPlan.sourceEventRetentionCutoffMs
			if (sourceEventCutoff != null && !maintenanceDeferred) {
				val exactFloor = requireNotNull(settledFloor?.lifecycle?.retainedFromMs)
				check(exactFloor >= sourceEventCutoff)
				requireDestructiveAuthority(
					appDatabase,
					execution,
					startupGeneration,
					authority,
				)
				if (appDatabase.pruneSourceEventStorageBefore(
					createdBeforeMs = exactFloor,
					verifyCollectedDataAccess = {
						requireDestructiveAuthority(
							appDatabase,
							execution,
							startupGeneration,
							authority,
						)
					},
				) is SourceEventStoragePruneResult.Deferred) {
					maintenanceDeferred = true
				}
			}
			val plannedRadioCutoff = listOfNotNull(
				operationPlan.rawRetentionCutoffMs,
				operationPlan.wifiCellRetentionCutoffMs,
			).maxOrNull()?.let {
				val settled = settledFloor?.lifecycle?.retainedFromMs
				if (settled != null) {
					check(settled >= it)
					settled
				} else {
					it
				}
			}
			val radioRetentionResult = purgeWifiCellData(
				appDatabase,
				plannedRadioCutoff,
				startupGeneration,
				authority,
				execution,
			)
			purgeTripData(
				appDatabase,
				operationPlan.tripRetentionCutoffMs,
				operationNow,
				startupGeneration,
				authority,
				execution,
			)
			purgeDailySummaries(
				appDatabase,
				operationPlan.dailySummaryRetentionCutoffDay,
				startupGeneration,
				authority,
				execution,
			)
			purgeExplorationData(
				appDatabase,
				operationPlan.explorationRetentionCutoffMs,
				startupGeneration,
				authority,
				execution,
			)
			purgeOperationalData(
				appDatabase,
				operationPlan.operationalRetentionCutoffMs,
				startupGeneration,
				authority,
				execution,
			)

            if (
				rawRetentionResult == RawRetentionResult.DEFERRED_FOR_PENDING_SIGNALS ||
				radioRetentionResult == RadioRetentionResult.DEFERRED_FOR_PENDING_SIGNALS ||
				maintenanceDeferred
			) {
				Result.retry()
			} else if (settledFloor == null) {
				Result.success()
			} else {
				completeSettlement(
					appDatabase,
					startupGeneration,
					settledFloor,
					operationNow,
					authority,
					execution,
				)
			}
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
	}

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
				RawRetentionResult.PURGED
			} finally {
				requireDestructiveAuthority(db, execution, startupGeneration, authority)
			}
        }
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
				db.sessionSegmentDao().deleteOlderThan(cutoff)
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
		requireExecutionContinuation(database, execution)
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
	}

	private suspend fun requireExecutionContinuation(
		database: AppDatabase,
		execution: RetentionWorkExecutionReceipt,
	) {
		when (workExecutionCoordinator.continuation(database, execution)) {
			RetentionWorkExecutionContinuationResult.Continue -> Unit
			RetentionWorkExecutionContinuationResult.CancellationRequested,
			RetentionWorkExecutionContinuationResult.AbandonedByCancellation,
			RetentionWorkExecutionContinuationResult.SupersededByFullDeletion,
			RetentionWorkExecutionContinuationResult.AlreadyCompleted,
			-> throw RetentionExecutionStoppedException()
			is RetentionWorkExecutionContinuationResult.Retryable ->
				throw RetentionExecutionDeferredException()
		}
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
