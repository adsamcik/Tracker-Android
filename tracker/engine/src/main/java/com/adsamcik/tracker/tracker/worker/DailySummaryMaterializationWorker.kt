package com.adsamcik.tracker.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.tracker.source.deletion.StepsDailySummaryRepairComposer
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPreflight
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/** Exact worker write primitive: process-wide day lock first, then the Room transaction. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun materializeDailySummaryDayInTransaction(
	database: AppDatabase,
	aggregator: DailySummaryAggregator,
	epochDay: Long,
	capturedZoneId: ZoneId,
	beforeMaterialize: suspend () -> Unit = {},
	afterMaterialize: suspend () -> Unit = {},
): DailySummaryMaterializationOutcome {
	var outcome: DailySummaryMaterializationOutcome? = null
	aggregator.withDayLocks(listOf(epochDay)) { lockedDays ->
		database.withTransaction {
			beforeMaterialize()
			try {
				val existing = database.dailySummaryDao().getByDay(epochDay)
				val storedZoneId = existing?.calendarZoneId
				val authorityZone = if (storedZoneId == null) {
					capturedZoneId
				} else {
					try {
						ZoneId.of(storedZoneId)
					} catch (_: DateTimeException) {
						return@withTransaction
					}
				}
				val dayStartMs = try {
					LocalDate.ofEpochDay(epochDay).atStartOfDay(authorityZone).toInstant().toEpochMilli()
				} catch (_: DateTimeException) {
					return@withTransaction
				}
				val dayEndMs = try {
					LocalDate.ofEpochDay(epochDay + 1L).atStartOfDay(authorityZone).toInstant().toEpochMilli()
				} catch (_: DateTimeException) {
					return@withTransaction
				}
				val attributedBounds = if (existing != null && storedZoneId == null) {
					allZoneDayBounds(epochDay) ?: return@withTransaction
				} else {
					MaterializationDayBounds(dayStartMs, dayEndMs)
				}
				val hasAttributedSegmentOverlap = database.sessionSegmentDao()
					.hasAttributedOverlappingForSourceRepair(
						fromMs = attributedBounds.fromMs,
						toMs = attributedBounds.toMs,
					)
				val hasSourceRunOverlap = database.trackingHistoryReadDao()
					.serviceRunCandidatePage(
						fromMs = attributedBounds.fromMs,
						toMs = attributedBounds.toMs,
						limit = 1,
						afterStartedAtMs = null,
						afterServiceRunId = null,
					)
					.isNotEmpty()
				val hasSourceAwareOverlap = hasAttributedSegmentOverlap || hasSourceRunOverlap
				if (existing != null && storedZoneId == null && hasSourceAwareOverlap) {
					outcome = DailySummaryMaterializationOutcome.Unverifiable
					return@withTransaction
				}
				val authorityAggregator = aggregator.withCalendarZone(authorityZone)
				if (!hasSourceAwareOverlap) {
					authorityAggregator.materializeDayFromSegmentsWhileLocked(epochDay, lockedDays)
					outcome = DailySummaryMaterializationOutcome.Ready
					return@withTransaction
				}
				outcome = when (
					val preflight = StepsDailySummaryRepairComposer(database)
						.composeForMaterialization(epochDay, authorityZone)
				) {
					is StepsDayRepairPreflight.Ready -> {
						val plan = preflight.plans.singleOrNull()
						if (plan == null || plan.epochDay != epochDay || plan.zoneId != authorityZone) {
							DailySummaryMaterializationOutcome.Unverifiable
						} else {
							authorityAggregator.repairDayFromSourceTotalsWhileLocked(
								epochDay = epochDay,
								lockedDays = lockedDays,
								totals = plan.totals,
							)
							DailySummaryMaterializationOutcome.Ready
						}
					}
					StepsDayRepairPreflight.Materializing ->
						DailySummaryMaterializationOutcome.Materializing
					is StepsDayRepairPreflight.Unsupported ->
						DailySummaryMaterializationOutcome.Unverifiable
				}
			} finally {
				afterMaterialize()
			}
		}
	}
	return outcome ?: DailySummaryMaterializationOutcome.Unverifiable
}

internal sealed interface DailySummaryMaterializationOutcome {
	data object Ready : DailySummaryMaterializationOutcome
	data object Materializing : DailySummaryMaterializationOutcome
	data object Unverifiable : DailySummaryMaterializationOutcome
}

private data class MaterializationDayBounds(val fromMs: Long, val toMs: Long)

private fun allZoneDayBounds(epochDay: Long): MaterializationDayBounds? = try {
	MaterializationDayBounds(
		fromMs = LocalDate.ofEpochDay(epochDay)
			.atStartOfDay(ZoneOffset.MAX)
			.toInstant()
			.toEpochMilli(),
		toMs = LocalDate.ofEpochDay(epochDay + 1L)
			.atStartOfDay(ZoneOffset.MIN)
			.toInstant()
			.toEpochMilli(),
	)
} catch (_: DateTimeException) {
	null
} catch (_: ArithmeticException) {
	null
}

/**
 * Periodic worker that materializes daily summary rows from session segment data.
 *
 * Runs daily at ~midnight and on tracking stop to ensure daily_summary rows
 * survive even if the app is killed during tracking.
 *
 * Reads completed session segments for today and writes/updates the
 * daily_summary row with aggregated totals via [DailySummaryAggregator].
 *
 * Marks `daily_summary` dirty after each successful materialization so the unified
 * rule engine's next flush re-evaluates daily_summary-backed metrics — needed
 * because this worker is the catch-up path when the in-orchestrator materialization
 * fails or runs out-of-band of a tracking session.
 */
@HiltWorker
class DailySummaryMaterializationWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val appDatabaseProvider: Provider<AppDatabase>,
	private val dirtyTracker: MetricDirtyTracker,
	private val trackingStartupGate: TrackingStartupGate,
) : CoroutineWorker(context, workerParams) {

	override suspend fun doWork(): Result {
		val startupGeneration = trackingStartupGate.currentGeneration
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
		}

		return try {
			requireReadyGeneration(startupGeneration)
			val database = appDatabaseProvider.get()
			val zoneId = ZoneId.systemDefault()
			val epochDay = LocalDate.now(zoneId).toEpochDay()
			val aggregator = DailySummaryAggregator(
				dailySummaryDao = database.dailySummaryDao(),
				sessionSegmentDao = database.sessionSegmentDao(),
				onDailySummaryWritten = {
					requireReadyGeneration(startupGeneration)
					dirtyTracker.markDirty(MetricKeys.TABLE_DAILY_SUMMARY)
				},
				verifyCollectedDataAccess = { requireReadyGeneration(startupGeneration) },
				zoneId = zoneId,
			)

			// The process-wide day mutex is always acquired before Room's write transaction. Deletion
			// uses the same order, so neither path can hold the database while waiting on the other.
			val outcome = materializeDailySummaryDayInTransaction(
				database = database,
				aggregator = aggregator,
				epochDay = epochDay,
				capturedZoneId = zoneId,
				beforeMaterialize = { requireReadyGeneration(startupGeneration) },
				afterMaterialize = { requireReadyGeneration(startupGeneration) },
			)

			when (outcome) {
				DailySummaryMaterializationOutcome.Ready -> Result.success()
				DailySummaryMaterializationOutcome.Materializing -> Result.retry()
				DailySummaryMaterializationOutcome.Unverifiable -> Result.failure()
			}
		} catch (_: StartupGenerationChangedException) {
			Result.success()
		}
	}

	private fun requireReadyGeneration(startupGeneration: Long) {
		if (!trackingStartupGate.isReady ||
			trackingStartupGate.currentGeneration != startupGeneration
		) {
			throw StartupGenerationChangedException
		}
	}

	companion object {
		// Single unique work name for BOTH periodic + one-shot materialization so
		// WorkManager serializes them at the queue level (APPEND policy chains the
		// one-shot after any currently-running or queued periodic run). The
		// DailySummaryAggregator also serializes per-day in-process for defense in
		// depth, but the work-name unification stops two RUNNERS from starting in
		// the first place when the system is under load.
		const val UNIQUE_WORK_ID = "APP.DAILY_SUMMARY_MATERIALIZATION"

		/**
		 * Schedule the periodic 24-hour materialization worker.
		 */
		fun schedule(context: Context) {
			val workManager = WorkManager.getInstance(context)
			val builder = PeriodicWorkRequestBuilder<DailySummaryMaterializationWorker>(
				24, TimeUnit.HOURS
			)
			workManager.enqueueUniquePeriodicWork(
				UNIQUE_WORK_ID,
				ExistingPeriodicWorkPolicy.KEEP,
				builder.build()
			)
		}

		/**
		 * Enqueue a one-shot materialization. Called when a tracking session ends
		 * to ensure the daily_summary row is immediately up-to-date.
		 *
		 * Uses APPEND_OR_REPLACE so it chains after any currently-executing periodic
		 * run instead of starting concurrently — eliminating the upsert race where
		 * both workers compute totals from different segment snapshots.
		 */
		fun runOnce(context: Context) {
			val workManager = WorkManager.getInstance(context)
			workManager.enqueueUniqueWork(
				UNIQUE_WORK_ID,
				ExistingWorkPolicy.APPEND_OR_REPLACE,
				OneTimeWorkRequestBuilder<DailySummaryMaterializationWorker>().build(),
			)
		}
	}

	private object StartupGenerationChangedException : RuntimeException()
}
