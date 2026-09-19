package com.adsamcik.tracker.app.maintenance

import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import dev.tracebox.Tracebox
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ActivityCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.RetentionFloorDestructivePlan
import com.adsamcik.tracker.shared.base.database.RoomTruncateImportedActivityRetention
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
import java.util.concurrent.TimeUnit
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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        when (val operation = retentionConfigStore.withExactApprovedOperation(::doApprovedWork)) {
            is ExactApprovedRetentionOperationResult.Completed -> operation.value
            is ExactApprovedRetentionOperationResult.Rejected -> Result.retry()
        }

    private suspend fun doApprovedWork(authority: ApprovedRetentionOperation): Result {
        authority.requireIdentity()
        val storedConfig = authority.configuration
        val config = storedConfig.forWorker()
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
		}
		val startupGeneration = trackingStartupGate.currentGeneration
		val appDatabase = try {
			appDatabaseProvider.get()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return Result.retry()
		}
		val workExecutionId = id.toString()
		val pendingOperation = try {
			retentionFloorSettlement.pendingOperation(
				appDatabase,
				workExecutionId,
				// Periodic WorkManager retries increment this count; the next acknowledged
				// periodic execution restarts at zero and may acknowledge the prior FINAL receipt.
				resumeCompletedExecution = runAttemptCount > 0,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return Result.retry()
		}
		if (
			!storedConfig.autoPurgeEnabled &&
			!storedConfig.autoCleanupEnabled &&
			pendingOperation == null
		) {
			return Result.success()
		}

        return try {
			requireReadyGeneration(startupGeneration)
			val requestedAtMs = pendingOperation?.requestedAtMs ?: System.currentTimeMillis()
			val rawCutoff = if (pendingOperation != null) {
				pendingOperation.destructivePlan.rawRetentionCutoffMs
			} else {
				config.rawDataRetentionDays.takeUnless { it == 0 }?.let {
					computeRetentionCutoffMillis(it, requestedAtMs)
				}
			}
			val wifiCellCutoff = if (pendingOperation != null) {
				pendingOperation.destructivePlan.wifiCellRetentionCutoffMs
			} else {
				config.wifiCellRetentionDays.takeUnless { it == 0 }?.let {
					computeRetentionCutoffMillis(it, requestedAtMs)
				}
			}
			val requestedFloor = pendingOperation?.requestedRetainedFromMs
				?: listOfNotNull(rawCutoff, wifiCellCutoff).maxOrNull()
				?: collectedDataLifecycleStore.snapshot().retainedFromMs
			val destructivePlan = pendingOperation?.destructivePlan ?: requestedFloor?.let { floor ->
				destructivePlan(config, requestedAtMs, floor)
			}
			val settledFloor = requestedFloor?.let { floor ->
				val plan = requireNotNull(destructivePlan)
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
							workExecutionId,
						),
					updatedAtMs = plan.requestedAtMs,
					workExecutionId = pendingOperation?.workExecutionId ?: workExecutionId,
					destructivePlan = plan,
					verifyApprovedOperation = { authority.requireIdentity() },
				)) {
					is RetentionFloorSettlementResult.Settled -> settlement
					RetentionFloorSettlementResult.StartupGenerationChanged ->
						throw StartupGenerationChangedException
					is RetentionFloorSettlementResult.Retryable ->
						throw RetentionFloorSettlementDeferredException
				}
			}
			val operationNow = settledFloor?.let {
				maxOf(
						it.requestedAtMs,
						requireNotNull(it.lifecycle.retainedFromMs),
				)
			} ?: requestedAtMs
			val operationPlan = settledFloor?.destructivePlan
			val operationRawCutoff = operationPlan?.rawRetentionCutoffMs ?: rawCutoff
			if (settledFloor != null) {
				if (settledFloor.sourceMaintenanceCompleted) {
					return completeSettlement(
						appDatabase,
						startupGeneration,
						settledFloor,
						operationNow,
						authority,
					)
				}
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
                )
                requireReadyGeneration(startupGeneration)
                authority.requireIdentity()
                trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
                	authority.requireIdentity()
                	stepsSessionFactProjectionLaneProvider.get().drainAvailable()
                } ?: throw StartupGenerationChangedException
                result
            }
			var maintenanceDeferred = false
			settledFloor?.let { settlement ->
				requireReadyGeneration(startupGeneration)
				authority.requireIdentity()
				if (periodicAmbientRetentionMaintenance.run(
						database = appDatabase,
						lifecycle = settlement.lifecycle,
						appliedAtMs = operationNow,
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
					)
				) {
					maintenanceDeferred = true
				}
			}
			val sourceEventCutoff = operationPlan?.sourceEventRetentionCutoffMs ?: rawCutoff
			if (sourceEventCutoff != null && !maintenanceDeferred) {
				val exactFloor = requireNotNull(settledFloor?.lifecycle?.retainedFromMs)
				check(exactFloor >= sourceEventCutoff)
                requireReadyGeneration(startupGeneration)
                authority.requireIdentity()
                appDatabase.pruneSourceEventStorageBefore(
					createdBeforeMs = exactFloor,
                	verifyCollectedDataAccess = {
                		requireReadyGeneration(startupGeneration)
                		authority.requireIdentity()
                	},
                )
			}
			val plannedRadioCutoff = listOfNotNull(
				operationPlan?.rawRetentionCutoffMs ?: rawCutoff,
				operationPlan?.wifiCellRetentionCutoffMs ?: wifiCellCutoff,
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
			)
			purgeTripData(
				appDatabase,
				if (operationPlan != null) {
					operationPlan.tripRetentionCutoffMs
				} else {
					config.tripRetentionDays.takeUnless { it == 0 }?.let {
						computeRetentionCutoffMillis(it, requestedAtMs)
					}
				},
				operationNow,
				startupGeneration,
				authority,
			)
			purgeDailySummaries(
				appDatabase,
				if (operationPlan != null) {
					operationPlan.dailySummaryRetentionCutoffDay
				} else {
					config.dailySummaryRetentionDays.takeUnless { it == 0 }?.let {
						computeRetentionCutoffMillis(it, requestedAtMs) /
							Time.DAY_IN_MILLISECONDS
					}
				},
				startupGeneration,
				authority,
			)
			purgeExplorationData(
				appDatabase,
				if (operationPlan != null) {
					operationPlan.explorationRetentionCutoffMs
				} else {
					config.explorationRetentionDays.takeUnless { it == 0 }?.let {
						computeRetentionCutoffMillis(it, requestedAtMs)
					}
				},
				startupGeneration,
				authority,
			)
			purgeOperationalData(
				appDatabase,
				if (operationPlan != null) {
					operationPlan.operationalRetentionCutoffMs
				} else {
					rawCutoff
				},
				startupGeneration,
				authority,
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
				)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
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
	): Result = when (retentionFloorSettlement.complete(
		database = database,
		startupGate = trackingStartupGate,
		expectedStartupGeneration = startupGeneration,
		settlement = settlement,
		completedAtMs = completedAtMs,
		verifyApprovedOperation = { authority.requireIdentity() },
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
    ): RawRetentionResult {
        return db.withTransaction {
			requireReadyGeneration(startupGeneration)
			authority.requireIdentity()
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
				requireReadyGeneration(startupGeneration)
				authority.requireIdentity()
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
	): Boolean {
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
		val result = trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			authority.requireIdentity()
			cellCapturedRetentionService.prune(
				database = db,
				beforeMs = retainedFromMs,
				markedAtMs = now,
			)
		} ?: throw StartupGenerationChangedException
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
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
	): Boolean {
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
		val result = trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			authority.requireIdentity()
			wifiCapturedRetentionService.prune(
				database = db,
				beforeMs = retainedFromMs,
				markedAtMs = now,
			)
		} ?: throw StartupGenerationChangedException
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
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
	): RadioRetentionResult {
		if (cutoff == null) return RadioRetentionResult.NOT_APPLICABLE
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
		if (db.pendingSignalDao().hasAny()) {
			return RadioRetentionResult.DEFERRED_FOR_PENDING_SIGNALS
		}
		val pruned = db.withTransaction {
			requireReadyGeneration(startupGeneration)
			authority.requireIdentity()
			try {
				if (db.pendingSignalDao().hasAny()) return@withTransaction false
				db.cellSampleDao().deleteOlderThan(cutoff)
				db.wifiObservationDao().deleteOlderThan(cutoff)
				true
			} finally {
				requireReadyGeneration(startupGeneration)
				authority.requireIdentity()
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
	) {
		if (cutoff == null) return
		db.withTransaction {
			requireReadyGeneration(startupGeneration)
			authority.requireIdentity()
			try {
				db.sessionSegmentDao().deleteOlderThan(cutoff)
				db.pruneImportedStepsSegmentsBefore(cutoff, now)
			} finally {
				requireReadyGeneration(startupGeneration)
				authority.requireIdentity()
			}
		}
    }

    private suspend fun purgeDailySummaries(
		db: AppDatabase,
		cutoffDay: Long?,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
	) {
		if (cutoffDay == null) return
		db.withTransaction {
			requireReadyGeneration(startupGeneration)
			authority.requireIdentity()
			try {
				db.dailySummaryDao().deleteOlderThan(cutoffDay)
			} finally {
				requireReadyGeneration(startupGeneration)
				authority.requireIdentity()
			}
		}
    }

    private suspend fun purgeExplorationData(
		db: AppDatabase,
		cutoff: Long?,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
	) {
		if (cutoff == null) return
		db.withTransaction {
			requireReadyGeneration(startupGeneration)
			authority.requireIdentity()
			try {
				db.explorationCellDao().deleteOlderThan(cutoff)
				db.explorationStreakDao().deleteOlderThan(cutoff)
				db.achievementProgressDao().deleteOlderThan(cutoff)
			} finally {
				requireReadyGeneration(startupGeneration)
				authority.requireIdentity()
			}
		}
    }

	private suspend fun purgeOperationalData(
		db: AppDatabase,
		rawCutoff: Long?,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
	) {
		if (rawCutoff == null) return
		db.withTransaction {
			requireReadyGeneration(startupGeneration)
			authority.requireIdentity()
			try {
				val domainEventDao = db.domainEventDao()
				val cursorCutoff = domainEventDao.getMinimumCursorTimestampMs()
				// With no consumer cursors yet, retention falls back to the configured raw cutoff.
				val domainEventCutoff = cursorCutoff?.let { minOf(rawCutoff, it) } ?: rawCutoff
				domainEventDao.deleteOlderThan(domainEventCutoff)
				db.exportLogDao().deleteOlderThan(rawCutoff)
			} finally {
				requireReadyGeneration(startupGeneration)
				authority.requireIdentity()
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
			requestedFloor: Long,
		): RetentionFloorDestructivePlan = RetentionFloorDestructivePlan(
			workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
			requestedAtMs = requestedAtMs,
			requestedRetainedFromMs = requestedFloor,
			rawRetentionCutoffMs = config.rawDataRetentionDays.takeUnless { it == 0 }?.let {
				computeRetentionCutoffMillis(it, requestedAtMs)
			},
			sourceEventRetentionCutoffMs =
				config.rawDataRetentionDays.takeUnless { it == 0 }?.let {
					computeRetentionCutoffMillis(it, requestedAtMs)
				},
			wifiCellRetentionCutoffMs =
				config.wifiCellRetentionDays.takeUnless { it == 0 }?.let {
					computeRetentionCutoffMillis(it, requestedAtMs)
				},
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
			operationalRetentionCutoffMs =
				config.rawDataRetentionDays.takeUnless { it == 0 }?.let {
					computeRetentionCutoffMillis(it, requestedAtMs)
				},
		)

        fun ensureScheduled(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)
            val request = PeriodicWorkRequestBuilder<RetentionPipelineWorker>(
                7, TimeUnit.DAYS,
            ).build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).run {
                cancelUniqueWork(WORK_NAME)
                cancelUniqueWork(LEGACY_WORK_NAME)
            }
        }

        fun schedule(context: Context) = ensureScheduled(context)
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
