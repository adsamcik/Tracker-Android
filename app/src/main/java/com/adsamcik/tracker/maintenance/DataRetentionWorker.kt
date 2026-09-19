package com.adsamcik.tracker.maintenance

import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import dev.tracebox.Tracebox
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.annotation.WorkerThread
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.app.maintenance.CellCapturedRetentionService
import com.adsamcik.tracker.app.maintenance.PeriodicAmbientRetentionMaintenance
import com.adsamcik.tracker.app.maintenance.PeriodicAmbientRetentionResult
import com.adsamcik.tracker.app.maintenance.RetentionFloorSettlement
import com.adsamcik.tracker.app.maintenance.RetentionFloorSettlementCompletionResult
import com.adsamcik.tracker.app.maintenance.RetentionFloorSettlementResult
import com.adsamcik.tracker.app.maintenance.RetentionExecutionDeferredException
import com.adsamcik.tracker.app.maintenance.RetentionExecutionStoppedException
import com.adsamcik.tracker.app.maintenance.RetentionWorkExecutionCoordinator
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ActivityCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.RetentionFloorDestructivePlan
import com.adsamcik.tracker.shared.base.database.RetentionFloorOperationLookupResult
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
import com.adsamcik.tracker.shared.base.database.pruneSourceEventStorageBefore
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.retention.ApprovedRetentionOperation
import com.adsamcik.tracker.shared.preferences.retention.ExactApprovedRetentionOperationResult
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionResult
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import javax.inject.Provider

/**
 * Periodic worker that deletes data older than N years to honor auto-cleanup setting.
 */
@HiltWorker
class DataRetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val retentionConfigStore: RetentionConfigStore,
    private val appDatabaseProvider: Provider<AppDatabase>,
    private val exportPlanStore: ExportPlanStore,
    private val migrationBackupRepository: DatabaseMigrationBackupRepository,
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore,
	private val trackingStartupGate: TrackingStartupGate,
	private val stepsSessionFactProjectionLaneProvider: Provider<StepsSessionFactProjectionLane>,
	private val importedActivityRetentionProvider: Provider<RoomTruncateImportedActivityRetention>,
	private val cellCapturedRetentionService: CellCapturedRetentionService,
	private val wifiCapturedRetentionService: WifiCapturedRetentionService,
	private val retentionFloorSettlement: RetentionFloorSettlement,
	private val periodicAmbientRetentionMaintenance: PeriodicAmbientRetentionMaintenance,
	private val workExecutionCoordinator: RetentionWorkExecutionCoordinator,
) : CoroutineWorker(context, workerParams) {

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
				workerKind = RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
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
        val config = authority.configuration
		val years = config.dataRetentionYears
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
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
			retentionFloorSettlement.pendingOperation(appDatabase, execution)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return Result.retry()
		}) {
			is RetentionFloorOperationLookupResult.Available -> lookup.operation
			is RetentionFloorOperationLookupResult.IncompatibleExecutor -> return Result.retry()
			is RetentionFloorOperationLookupResult.ExecutionOwned -> return Result.retry()
		}
		if (
			(!config.autoCleanupEnabled || years == 0) &&
			pendingOperation == null &&
			execution.destructivePlan == null
		) {
			return Result.success()
		}
		val requestedAtMs = pendingOperation?.requestedAtMs
			?: execution.destructivePlan?.requestedAtMs
			?: execution.startedAtMs
		val proposedPlan = pendingOperation?.destructivePlan
			?: execution.destructivePlan
			?: computeCutoffMillis(years, requestedAtMs).coerceAtLeast(0L).let { floor ->
				RetentionFloorDestructivePlan(
					workerKind = RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
					requestedAtMs = requestedAtMs,
					requestedRetainedFromMs = floor,
					rawRetentionCutoffMs = floor,
					sourceEventRetentionCutoffMs = floor,
					wifiCellRetentionCutoffMs = floor,
					tripRetentionCutoffMs = floor,
					dailySummaryRetentionCutoffDay = null,
					explorationRetentionCutoffMs = null,
					operationalRetentionCutoffMs = null,
				)
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
		val requestedFloor = requireNotNull(destructivePlan.requestedRetainedFromMs)
		val requestedOperationId = pendingOperation?.operationId
			?: authority.retentionFloorOperationId(
				requestedFloor,
				requestedAtMs,
				execution.executionId,
			)
        return try {
			requireDestructiveAuthority(appDatabase, execution, startupGeneration, authority)
			// The policy and backup cleanup precede the physical delete, so a WAL
			// entry can defer that delete without preserving expired history in a
			// migration snapshot.
			val settlement = when (val result = retentionFloorSettlement.settle(
				database = appDatabase,
				lifecycleStore = collectedDataLifecycleStore,
				startupGate = trackingStartupGate,
				expectedStartupGeneration = startupGeneration,
				requestedRetainedFromMs = requestedFloor,
				operationId = requestedOperationId,
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
				is RetentionFloorSettlementResult.Settled -> result
				RetentionFloorSettlementResult.StartupGenerationChanged ->
					throw StartupGenerationChangedException
				is RetentionFloorSettlementResult.Retryable ->
					throw RetentionFloorSettlementDeferredException
			}
			val lifecycle = settlement.lifecycle
			val retainedFromMs = requireNotNull(lifecycle.retainedFromMs) {
				"Captured radio retention requires a durable retained-from floor"
			}
			val operationTimeMs = settlement.sourceMaintenanceAtMs
			if (settlement.sourceMaintenanceCompleted) {
				return completeSettlement(
					appDatabase,
					startupGeneration,
					settlement,
					operationTimeMs,
					authority,
					execution,
				)
			}
			requireDestructiveAuthority(appDatabase, execution, startupGeneration, authority)
			migrationBackupRepository.deleteAll()
			val rawRetentionResult = pruneRawData(
				appDatabase,
				retainedFromMs,
				lifecycle,
				operationTimeMs,
				startupGeneration,
				authority,
				execution,
			)
			if (rawRetentionResult == RawRetentionPruneResult.PRUNED) {
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
			}
			requireDestructiveAuthority(appDatabase, execution, startupGeneration, authority)
			val ambientRetentionAccepted = periodicAmbientRetentionMaintenance.run(
					database = appDatabase,
					lifecycle = lifecycle,
					appliedAtMs = operationTimeMs,
					verifyExecutionContinuation = {
						requireExecutionContinuation(
							appDatabase,
							execution,
						)
					},
				) is PeriodicAmbientRetentionResult.Complete
			// Captured radio maintenance authenticates its retained source WAL after lifecycle
			// settlement and before the shared physical WAL prune, including deferred raw runs.
			val cellRetentionAccepted = pruneCapturedCellData(
				appDatabase,
				retainedFromMs,
				operationTimeMs,
				startupGeneration,
				authority,
				execution,
			)
			val wifiRetentionAccepted = pruneCapturedWifiData(
				appDatabase,
				retainedFromMs,
				operationTimeMs,
				startupGeneration,
				authority,
				execution,
			)
			if (!ambientRetentionAccepted) {
				throw AmbientRetentionDeferredException
			}
			if (!cellRetentionAccepted || !wifiRetentionAccepted) {
				throw RadioRetentionDeferredException
			}
			when (rawRetentionResult) {
				RawRetentionPruneResult.PRUNED -> {
					// Captured radio revisions authenticate their exact attributed segment.
					pruneExpiredSessionSegments(
						appDatabase,
						retainedFromMs,
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
					when (appDatabase.pruneSourceEventStorageBefore(
						createdBeforeMs = retainedFromMs,
						verifyCollectedDataAccess = {
							requireDestructiveAuthority(
								appDatabase,
								execution,
								startupGeneration,
								authority,
							)
						},
					)) {
						is SourceEventStoragePruneResult.Complete ->
							completeSettlement(
								appDatabase,
								startupGeneration,
								settlement,
								operationTimeMs,
								authority,
								execution,
							)
						is SourceEventStoragePruneResult.Deferred -> Result.retry()
					}
				}
				RawRetentionPruneResult.DEFERRED_FOR_PENDING_SIGNALS -> {
					Result.retry()
				}
			}
        } catch (e: CancellationException) {
            throw e
		} catch (_: RetentionExecutionStoppedException) {
			Result.success()
		} catch (_: RetentionExecutionDeferredException) {
			Result.retry()
		} catch (_: StartupGenerationChangedException) {
			Result.retry()
		} catch (_: ActivityRetentionDeferredException) {
			Result.retry()
		} catch (_: RadioRetentionDeferredException) {
			Result.retry()
		} catch (_: AmbientRetentionDeferredException) {
			Result.retry()
		} catch (_: RetentionFloorSettlementDeferredException) {
			Result.retry()
        } catch (error: Exception) {
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

    companion object {
        private const val ONE_YEAR_MILLIS: Long = 365L * 24L * 60L * 60L * 1000L

        @WorkerThread
        private fun yearsToMillis(years: Int): Long = years.coerceAtLeast(0) * ONE_YEAR_MILLIS

        /** Visible for tests to validate cutoff calculation for different retention values. */
        @JvmStatic
        @VisibleForTesting
        fun computeCutoffMillis(years: Int, nowMillis: Long = System.currentTimeMillis()): Long {
            if (years <= 0) return Long.MIN_VALUE
            return nowMillis - yearsToMillis(years)
        }
    }

    @WorkerThread
	@Suppress("LongMethod")
    private suspend fun pruneRawData(
		appDatabase: AppDatabase,
        cutoffMillis: Long,
        lifecycle: CollectedDataLifecycleSnapshot,
        updatedAtMs: Long,
		startupGeneration: Long,
        authority: ApprovedRetentionOperation,
        execution: RetentionWorkExecutionReceipt,
    ): RawRetentionPruneResult {
        val pruned = appDatabase.withTransaction {
        	requireDestructiveAuthority(
        		appDatabase,
        		execution,
        		startupGeneration,
        		authority,
        	)
        	try {
				val sourceEvidenceStateDao = appDatabase.sourceEvidenceStateDao()
				val retainedFromMs = requireNotNull(lifecycle.retainedFromMs) {
					"Raw retention must establish a durable retained-from floor"
				}
				appDatabase.markAuthenticatedStepsRunsAffectedByRetentionFloor(
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
				if (appDatabase.pendingSignalDao().hasAny()) return@withTransaction false
				if (hasCapturedActivityRetentionAuthority(appDatabase)) {
					when (appDatabase.pruneCapturedActivityFactsAffectedByRetentionFloor(
						beforeMs = activityRetainedFromMs,
						expectedCollectedDataEpoch = activityState.collectedDataEpoch,
						markedAtMs = updatedAtMs,
					)) {
						is ActivityCapturedRetentionResult.Pruned,
						ActivityCapturedRetentionResult.NoChange -> Unit
						is ActivityCapturedRetentionResult.Blocked -> throw ActivityRetentionDeferredException
					}
				}
				appDatabase.trajectoryReconstructionDao().deleteWithSourceBefore(cutoffMillis)
				val observationDao = appDatabase.locationObservationDao()
				observationDao.deleteOlderThan(cutoffMillis)
				appDatabase.locationProjectionDao().deleteObservationsOlderThan(cutoffMillis)
				appDatabase.locationObservationDecisionDao().apply {
					deleteOlderThan(cutoffMillis)
					deleteWithoutObservation()
				}
				appDatabase.trackerStateEventDao().deleteOlderThan(cutoffMillis)
				appDatabase.locationSampleDao().deleteOlderThan(cutoffMillis)
				appDatabase.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(
					beforeMs = retainedFromMs,
					collectedDataEpoch = lifecycle.epoch,
					markedAtMs = updatedAtMs,
				)
				appDatabase.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
					beforeMs = retainedFromMs,
					collectedDataEpoch = lifecycle.epoch,
					markedAtMs = updatedAtMs,
				)
				appDatabase.stepIntervalDao().deleteOlderThan(cutoffMillis)
				appDatabase.activitySnapshotDao().deleteOlderThan(cutoffMillis)
				appDatabase.trackerRunDao().deleteOlderThan(cutoffMillis)
				appDatabase.pressureSampleDao().deleteOlderThan(cutoffMillis)
				appDatabase.skiRunSegmentDao().deleteOlderThan(cutoffMillis)
				appDatabase.wifiObservationDao().deleteOlderThan(cutoffMillis)
				appDatabase.cellSampleDao().deleteOlderThan(cutoffMillis)
				appDatabase.pruneImportedStepsSegmentsBefore(cutoffMillis, updatedAtMs)
				appDatabase.quarantinedSignalDao().deleteAcquiredBefore(cutoffMillis)
				true
			} finally {
				requireDestructiveAuthority(
					appDatabase,
					execution,
					startupGeneration,
					authority,
				)
			}
        }
        return if (pruned) {
			requireDestructiveAuthority(
				appDatabase,
				execution,
				startupGeneration,
				authority,
			)
            exportPlanStore.resetAllWatermarks()
            RawRetentionPruneResult.PRUNED
        } else {
            RawRetentionPruneResult.DEFERRED_FOR_PENDING_SIGNALS
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

	private suspend fun pruneExpiredSessionSegments(
		appDatabase: AppDatabase,
		cutoffMillis: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
	) {
		requireDestructiveAuthority(
			appDatabase,
			execution,
			startupGeneration,
			authority,
		)
		appDatabase.withTransaction {
			requireDestructiveAuthority(
				appDatabase,
				execution,
				startupGeneration,
				authority,
			)
			try {
				appDatabase.sessionSegmentDao().deleteOlderThan(cutoffMillis)
			} finally {
				requireDestructiveAuthority(
					appDatabase,
					execution,
					startupGeneration,
					authority,
				)
			}
		}
	}

	private suspend fun pruneCapturedCellData(
		appDatabase: AppDatabase,
		retainedFromMs: Long,
		updatedAtMs: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
	): Boolean {
		requireDestructiveAuthority(
			appDatabase,
			execution,
			startupGeneration,
			authority,
		)
		val result = trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			requireDestructiveAuthority(
				appDatabase,
				execution,
				startupGeneration,
				authority,
			)
			cellCapturedRetentionService.prune(
				database = appDatabase,
				beforeMs = retainedFromMs,
				markedAtMs = updatedAtMs,
			)
		} ?: throw StartupGenerationChangedException
		requireDestructiveAuthority(
			appDatabase,
			execution,
			startupGeneration,
			authority,
		)
		return when (result) {
			is CellCapturedRetentionResult.Pruned,
			CellCapturedRetentionResult.NoChange -> true
			is CellCapturedRetentionResult.Blocked -> false
		}
	}

	private suspend fun pruneCapturedWifiData(
		appDatabase: AppDatabase,
		retainedFromMs: Long,
		updatedAtMs: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
		execution: RetentionWorkExecutionReceipt,
	): Boolean {
		requireDestructiveAuthority(
			appDatabase,
			execution,
			startupGeneration,
			authority,
		)
		val result = trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			requireDestructiveAuthority(
				appDatabase,
				execution,
				startupGeneration,
				authority,
			)
			wifiCapturedRetentionService.prune(
				database = appDatabase,
				beforeMs = retainedFromMs,
				markedAtMs = updatedAtMs,
			)
		} ?: throw StartupGenerationChangedException
		requireDestructiveAuthority(
			appDatabase,
			execution,
			startupGeneration,
			authority,
		)
		return when (result) {
			is WifiCapturedRetentionResult.Pruned,
			WifiCapturedRetentionResult.NoChange -> true
			is WifiCapturedRetentionResult.Blocked -> false
		}
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

    private enum class RawRetentionPruneResult {
        PRUNED,
        DEFERRED_FOR_PENDING_SIGNALS,
    }

	private object StartupGenerationChangedException : RuntimeException()
	private object ActivityRetentionDeferredException : RuntimeException()
	private object RadioRetentionDeferredException : RuntimeException()
	private object AmbientRetentionDeferredException : RuntimeException()
	private object RetentionFloorSettlementDeferredException : RuntimeException()
}
