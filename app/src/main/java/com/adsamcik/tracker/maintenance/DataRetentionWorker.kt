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
import com.adsamcik.tracker.app.maintenance.RetentionPipelineWorker
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ActivityCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.RoomTruncateImportedActivityRetention
import com.adsamcik.tracker.shared.base.database.TruncateImportedActivityRetentionRequest
import com.adsamcik.tracker.shared.base.database.TruncateImportedActivityRetentionResult
import com.adsamcik.tracker.shared.base.database.markAuthenticatedStepsRunsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.pruneCapturedActivityFactsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.pruneImportedStepsSegmentsBefore
import com.adsamcik.tracker.shared.base.database.pruneSourceEventStorageBefore
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
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
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result =
        when (val operation = retentionConfigStore.withExactApprovedOperation(::doApprovedWork)) {
            is ExactApprovedRetentionOperationResult.Completed -> operation.value
            is ExactApprovedRetentionOperationResult.Rejected -> Result.retry()
        }

    private suspend fun doApprovedWork(authority: ApprovedRetentionOperation): Result {
        authority.requireIdentity()
        val config = authority.configuration
        if (!config.autoCleanupEnabled) {
            return Result.success()
        }

		val years = config.dataRetentionYears
		if (years == 0) {
			return Result.success()
		}
		val startupGeneration = trackingStartupGate.currentGeneration
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
		}
		val now = System.currentTimeMillis()
		val cutoff = computeCutoffMillis(years, now)
        return try {
			requireReadyGeneration(startupGeneration)
			val appDatabase = appDatabaseProvider.get()
			// The policy and backup cleanup precede the physical delete, so a WAL
			// entry can defer that delete without preserving expired history in a
			// migration snapshot.
			requireReadyGeneration(startupGeneration)
			authority.requireIdentity()
			val lifecycle = collectedDataLifecycleStore.advanceRetainedFrom(cutoff)
			requireReadyGeneration(startupGeneration)
			authority.requireIdentity()
			migrationBackupRepository.deleteAll()
			val rawRetentionResult = pruneRawData(
				appDatabase,
				cutoff,
				lifecycle,
				now,
				startupGeneration,
				authority,
			)
			val retainedFromMs = requireNotNull(lifecycle.retainedFromMs) {
				"Captured radio retention requires a durable retained-from floor"
			}
			if (rawRetentionResult == RawRetentionPruneResult.PRUNED) {
				requireReadyGeneration(startupGeneration)
				authority.requireIdentity()
				trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
					authority.requireIdentity()
					stepsSessionFactProjectionLaneProvider.get().drainAvailable()
				} ?: throw StartupGenerationChangedException
			}
			// Captured radio maintenance authenticates its retained source WAL after lifecycle
			// settlement and before the shared physical WAL prune, including deferred raw runs.
			val cellRetentionAccepted = pruneCapturedCellData(
				appDatabase,
				retainedFromMs,
				now,
				startupGeneration,
				authority,
			)
			val wifiRetentionAccepted = pruneCapturedWifiData(
				appDatabase,
				retainedFromMs,
				now,
				startupGeneration,
				authority,
			)
			if (!cellRetentionAccepted || !wifiRetentionAccepted) {
				throw RadioRetentionDeferredException
			}
			when (rawRetentionResult) {
				RawRetentionPruneResult.PRUNED -> {
					// Captured radio revisions authenticate their exact attributed segment.
					pruneExpiredSessionSegments(
						appDatabase,
						cutoff,
						startupGeneration,
						authority,
					)
					requireReadyGeneration(startupGeneration)
					authority.requireIdentity()
					appDatabase.pruneSourceEventStorageBefore(
						createdBeforeMs = cutoff,
						verifyCollectedDataAccess = {
							requireReadyGeneration(startupGeneration)
							authority.requireIdentity()
						},
					)
					Result.success()
				}
				RawRetentionPruneResult.DEFERRED_FOR_PENDING_SIGNALS -> {
					Result.retry()
				}
			}
        } catch (e: CancellationException) {
            throw e
		} catch (_: StartupGenerationChangedException) {
			Result.success()
		} catch (_: ActivityRetentionDeferredException) {
			Result.retry()
		} catch (_: RadioRetentionDeferredException) {
			Result.retry()
        } catch (error: Exception) {
            Tracebox.log.error(error, TrackerTraceboxTemplates.DATA_RETENTION_FAILED)
            Result.retry()
        }
    }

    companion object {
        private const val ONE_YEAR_MILLIS: Long = 365L * 24L * 60L * 60L * 1000L

        /** Schedule weekly cleanup with unique work policy. */
        fun ensureScheduled(context: Context) {
            RetentionPipelineWorker.ensureScheduled(context)
        }

        /** Cancel scheduled cleanup. */
        fun cancel(context: Context) {
            RetentionPipelineWorker.cancel(context)
        }

        internal fun syncScheduling(context: Context, enabled: Boolean) {
            try {
                if (enabled) ensureScheduled(context) else cancel(context)
            } catch (_: IllegalStateException) {
                // WorkManager may not be initialized in tests; ignore the exception as before.
                return
            }
        }

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
    ): RawRetentionPruneResult {
        val pruned = appDatabase.withTransaction {
        	requireReadyGeneration(startupGeneration)
        	authority.requireIdentity()
        	try {
				val sourceEvidenceStateDao = appDatabase.sourceEvidenceStateDao()
				val lifecycleChanged = sourceEvidenceStateDao.synchronizeLifecycle(
					epoch = lifecycle.epoch,
					retainedFromMs = lifecycle.retainedFromMs,
					updatedAtMs = updatedAtMs,
				)
				if (!lifecycleChanged) {
					check(sourceEvidenceStateDao.incrementRevision(updatedAtMs) == 1) {
						"Unable to advance source-evidence revision for raw-data retention"
					}
				}
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
				requireReadyGeneration(startupGeneration)
				authority.requireIdentity()
			}
        }
        return if (pruned) {
			requireReadyGeneration(startupGeneration)
			authority.requireIdentity()
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

	private suspend fun pruneExpiredSessionSegments(
		appDatabase: AppDatabase,
		cutoffMillis: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
	) {
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
		appDatabase.withTransaction {
			requireReadyGeneration(startupGeneration)
			authority.requireIdentity()
			try {
				appDatabase.sessionSegmentDao().deleteOlderThan(cutoffMillis)
			} finally {
				requireReadyGeneration(startupGeneration)
				authority.requireIdentity()
			}
		}
	}

	private suspend fun pruneCapturedCellData(
		appDatabase: AppDatabase,
		retainedFromMs: Long,
		updatedAtMs: Long,
		startupGeneration: Long,
		authority: ApprovedRetentionOperation,
	): Boolean {
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
		val result = trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			authority.requireIdentity()
			cellCapturedRetentionService.prune(
				database = appDatabase,
				beforeMs = retainedFromMs,
				markedAtMs = updatedAtMs,
			)
		} ?: throw StartupGenerationChangedException
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
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
	): Boolean {
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
		val result = trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			authority.requireIdentity()
			wifiCapturedRetentionService.prune(
				database = appDatabase,
				beforeMs = retainedFromMs,
				markedAtMs = updatedAtMs,
			)
		} ?: throw StartupGenerationChangedException
		requireReadyGeneration(startupGeneration)
		authority.requireIdentity()
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
}
