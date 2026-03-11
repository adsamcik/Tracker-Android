package com.adsamcik.tracker.tracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that materializes daily summary rows from session segment data.
 *
 * Runs daily at ~midnight and on tracking stop to ensure daily_summary rows
 * survive even if the app is killed during tracking.
 *
 * Reads completed session segments for today and writes/updates the
 * daily_summary row with aggregated totals.
 */
class DailySummaryMaterializationWorker(
	context: Context,
	workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

	override suspend fun doWork(): Result {
		val database = AppDatabase.database(applicationContext)
		val segmentDao = database.sessionSegmentDao()
		val dailySummaryDao = database.dailySummaryDao()

		val now = Time.nowMillis
		val startOfDay = Time.todayMillis
		val endOfDay = Time.tomorrowMillis
		val epochDay = startOfDay / Time.DAY_IN_MILLISECONDS

		// Aggregate from session_segment table for today
		val segments = segmentDao.getAllBetween(startOfDay, endOfDay)
		val totalDistanceM = segments.sumOf { it.distanceM.toDouble() }.toFloat()
		val totalSteps = segments.sumOf { it.steps ?: 0 }
		val totalDurationMs = segments.sumOf { it.endTimeMs - it.startTimeMs }
		val tripCount = segments.size

		// Only write if there's data or existing row to update
		val existing = dailySummaryDao.getByDay(epochDay)
		if (segments.isNotEmpty() || existing != null) {
			dailySummaryDao.upsert(
				dateEpochDay = epochDay,
				totalDistanceM = totalDistanceM,
				totalSteps = totalSteps,
				totalDurationMs = totalDurationMs,
				tripCount = tripCount,
				activeTrackingMs = existing?.activeTrackingMs ?: 0L,
				lastUpdatedMs = now
			)
		}

		return Result.success()
	}

	companion object {
		private const val UNIQUE_ID = "APP.DAILY_SUMMARY_MATERIALIZATION"

		fun schedule(context: Context) {
			val workManager = WorkManager.getInstance(context)
			val builder = PeriodicWorkRequestBuilder<DailySummaryMaterializationWorker>(
				24, TimeUnit.HOURS
			)
			workManager.enqueueUniquePeriodicWork(
				UNIQUE_ID,
				ExistingPeriodicWorkPolicy.KEEP,
				builder.build()
			)
		}
	}
}
