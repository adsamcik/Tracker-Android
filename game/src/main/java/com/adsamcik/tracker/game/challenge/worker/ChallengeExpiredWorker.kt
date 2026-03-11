package com.adsamcik.tracker.game.challenge.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.shared.base.Time
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
internal class ChallengeExpiredWorker @AssistedInject constructor(
		@Assisted context: Context,
		@Assisted workerParams: WorkerParameters,
		private val challengeManager: ChallengeManager,
) : CoroutineWorker(
		context,
		workerParams,
) {

	override suspend fun doWork(): Result {
		challengeManager.checkExpiredChallenges(applicationContext)
		return Result.success()
	}

	companion object {
		private const val UNIQUE_WORK_NAME = "ChallengeExpired"

		/**
		 * Schedules the next replacement of the expired challenges for the set time
		 */
		fun schedule(context: Context, nextExpiryTime: Long) {
			val delay = nextExpiryTime - Time.nowMillis
			val workManager = WorkManager.getInstance(context)
			val workRequest = OneTimeWorkRequestBuilder<ChallengeExpiredWorker>()
					.setInitialDelay(delay, TimeUnit.MILLISECONDS)
					.build()
			workManager.enqueueUniqueWork(
					UNIQUE_WORK_NAME,
					ExistingWorkPolicy.REPLACE,
					workRequest
			)
		}
	}
}
