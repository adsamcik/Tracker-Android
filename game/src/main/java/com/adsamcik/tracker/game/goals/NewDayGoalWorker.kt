package com.adsamcik.tracker.game.goals

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.Time
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Notifies goal tracker of a new day.
 */
internal class NewDayGoalWorker(context: Context, workerParams: WorkerParameters) : Worker(
		context,
		workerParams
) {
	override fun doWork(): Result {
		GoalTracker.onNewDay()
		return Result.success()
	}

	companion object {
		private const val UNIQUE_WORK_NAME = "GAME.GOALS.NEW_DAY_WORKER"

		/**
		 * Ensures new day goals worker is schedule
		 */
		fun ensureScheduled(context: Context) {
			val workManager = WorkManager.getInstance(context)
			val delay = Duration.between(Time.now, Time.tomorrow)

			val workRequest = PeriodicWorkRequestBuilder<NewDayGoalWorker>(Duration.ofDays(1L))
					.setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
					.addTag(UNIQUE_WORK_NAME)
					.build()

			workManager.enqueueUniquePeriodicWork(
					UNIQUE_WORK_NAME,
					ExistingPeriodicWorkPolicy.KEEP,
					workRequest
			)
		}
	}

}
