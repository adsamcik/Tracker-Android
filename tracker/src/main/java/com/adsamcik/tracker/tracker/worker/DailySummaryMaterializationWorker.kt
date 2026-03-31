package com.adsamcik.tracker.tracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that materializes daily summary rows from session segment data.
 *
 * Runs daily at ~midnight and on tracking stop to ensure daily_summary rows
 * survive even if the app is killed during tracking.
 *
 * Reads completed session segments for today and writes/updates the
 * daily_summary row with aggregated totals via [DailySummaryAggregator].
 */
class DailySummaryMaterializationWorker(
	context: Context,
	workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

	override suspend fun doWork(): Result {
		val database = AppDatabase.database(applicationContext)
		val aggregator = DailySummaryAggregator(
			dailySummaryDao = database.dailySummaryDao(),
			sessionSegmentDao = database.sessionSegmentDao(),
		)

		aggregator.materializeToday()

		return Result.success()
	}

	companion object {
		private const val UNIQUE_PERIODIC_ID = "APP.DAILY_SUMMARY_MATERIALIZATION"
		private const val UNIQUE_ONESHOT_ID = "APP.DAILY_SUMMARY_MATERIALIZATION_ONESHOT"

		/**
		 * Schedule the periodic 24-hour materialization worker.
		 */
		fun schedule(context: Context) {
			val workManager = WorkManager.getInstance(context)
			val builder = PeriodicWorkRequestBuilder<DailySummaryMaterializationWorker>(
				24, TimeUnit.HOURS
			)
			workManager.enqueueUniquePeriodicWork(
				UNIQUE_PERIODIC_ID,
				ExistingPeriodicWorkPolicy.KEEP,
				builder.build()
			)
		}

		/**
		 * Enqueue a one-shot materialization. Called when a tracking session ends
		 * to ensure the daily_summary row is immediately up-to-date.
		 */
		fun runOnce(context: Context) {
			val workManager = WorkManager.getInstance(context)
			workManager.enqueueUniqueWork(
				UNIQUE_ONESHOT_ID,
				ExistingWorkPolicy.REPLACE,
				OneTimeWorkRequestBuilder<DailySummaryMaterializationWorker>().build(),
			)
		}
	}
}
