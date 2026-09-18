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

        if (!storedConfig.autoPurgeEnabled && !storedConfig.autoCleanupEnabled) return Result.success()
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
		}
		val startupGeneration = trackingStartupGate.currentGeneration

        return try {
			requireReadyGeneration(startupGeneration)
			val appDatabase = appDatabaseProvider.get()
            val now = System.currentTimeMillis()

            val rawCutoff = config.rawDataRetentionDays.takeUnless { it == 0 }?.let { retentionDays ->
                now - retentionDays.toLong() * Time.DAY_IN_MILLISECONDS
            }
			val wifiCellCutoff = config.wifiCellRetentionDays.takeUnless { it == 0 }?.let {
				computeWifiCellCutoffMillis(it, now)
			}
			val requestedFloor = listOfNotNull(rawCutoff, wifiCellCutoff).maxOrNull()
				?: collectedDataLifecycleStore.snapshot().retainedFromMs
			val settledFloor = requestedFloor?.let { floor ->
				when (val settlement = retentionFloorSettlement.settle(
					database = appDatabase,
					lifecycleStore = collectedDataLifecycleStore,
					startupGate = trackingStartupGate,
					expectedStartupGeneration = startupGeneration,
					requestedRetainedFromMs = floor,
					operationId = authority.retentionFloorOperationId(floor),
					updatedAtMs = now,
					verifyApprovedOperation = { authority.requireIdentity() },
				)) {
					is RetentionFloorSettlementResult.Settled -> settlement
					RetentionFloorSettlementResult.StartupGenerationChanged ->
						throw StartupGenerationChangedException
					is RetentionFloorSettlementResult.Retryable ->
						throw RetentionFloorSettlementDeferredException
				}
			}
			if (settledFloor != null) {
				migrationBackupRepository.deleteAll()
			}
            val rawRetentionResult = if (rawCutoff == null) {
                RawRetentionResult.NOT_APPLICABLE
            } else {
				val lifecycle = requireNotNull(settledFloor).lifecycle
                val result = purgeRawData(
                	appDatabase,
                	rawCutoff,
                	lifecycle,
                	now,
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
						activeLocalSources = settlement.reconciledSources,
						appliedAtMs = now,
					) is PeriodicAmbientRetentionResult.Retryable
				) {
					maintenanceDeferred = true
				}
				val exactFloor = requireNotNull(settlement.lifecycle.retainedFromMs)
				// Captured radio maintenance authenticates retained WAL after source settlement.
				if (!pruneCapturedCellData(
						appDatabase,
						exactFloor,
						now,
						startupGeneration,
						authority,
					)
				) {
					maintenanceDeferred = true
				}
				if (!pruneCapturedWifiData(
						appDatabase,
						exactFloor,
						now,
						startupGeneration,
						authority,
					)
				) {
					maintenanceDeferred = true
				}
			}
			if (rawCutoff != null && !maintenanceDeferred) {
                requireReadyGeneration(startupGeneration)
                authority.requireIdentity()
                appDatabase.pruneSourceEventStorageBefore(
                	createdBeforeMs = rawCutoff,
                	verifyCollectedDataAccess = {
                		requireReadyGeneration(startupGeneration)
                		authority.requireIdentity()
                	},
                )
			}
			purgeWifiCellData(appDatabase, config, now, startupGeneration, authority)
			purgeTripData(appDatabase, config, now, startupGeneration, authority)
			purgeDailySummaries(appDatabase, config, now, startupGeneration, authority)
			purgeExplorationData(appDatabase, config, now, startupGeneration, authority)
			purgeOperationalData(appDatabase, config, now, startupGeneration, authority)

            if (rawRetentionResult == RawRetentionResult.DEFERRED_FOR_PENDING_SIGNALS ||
				maintenanceDeferred
			) {
                Result.retry()
            } else {
                Result.success()
            }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: StartupGenerationChangedException) {
			Result.success()
		} catch (_: ActivityRetentionDeferredException) {
			Result.retry()
		} catch (_: RetentionFloorSettlementDeferredException) {
			Result.retry()
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Tracebox.log.error(error, TrackerTraceboxTemplates.DATA_RETENTION_FAILED)
            Result.retry()
        }
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
		config: RetentionConfigState,
		now: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
	) {
        if (config.wifiCellRetentionDays == 0) return
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
        if (db.pendingSignalDao().hasAny()) return
		val cutoff = computeWifiCellCutoffMillis(config.wifiCellRetentionDays, now)
        db.withTransaction {
			requireReadyGeneration(startupGeneration)
			authority.requireIdentity()
			try {
				if (db.pendingSignalDao().hasAny()) return@withTransaction
				db.cellSampleDao().deleteOlderThan(cutoff)
				db.wifiObservationDao().deleteOlderThan(cutoff)
			} finally {
				requireReadyGeneration(startupGeneration)
				authority.requireIdentity()
			}
        }
    }

    private suspend fun purgeTripData(
		db: AppDatabase,
		config: RetentionConfigState,
		now: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
	) {
        if (config.tripRetentionDays == 0) return
        val cutoff = now - config.tripRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
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
		config: RetentionConfigState,
		now: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
	) {
        if (config.dailySummaryRetentionDays == 0) return
        val cutoffMs = now - config.dailySummaryRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        val cutoffDay = cutoffMs / Time.DAY_IN_MILLISECONDS
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
		config: RetentionConfigState,
		now: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
	) {
        if (config.explorationRetentionDays == 0) return
        val cutoff = now - config.explorationRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
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
		config: RetentionConfigState,
		now: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
	) {
		if (config.rawDataRetentionDays == 0) return
		val rawCutoff = now - config.rawDataRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
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
}
