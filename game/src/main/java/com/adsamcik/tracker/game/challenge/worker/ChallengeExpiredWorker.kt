package com.adsamcik.tracker.game.challenge.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.shared.base.Time
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

internal class ChallengeExpiredWorker(context: Context, workerParams: WorkerParameters) : Worker(
		context,
		workerParams
) {

	@EntryPoint
	@InstallIn(SingletonComponent::class)
	interface ChallengeManagerEntryPoint {
		fun challengeManager(): ChallengeManager
	}

	override fun doWork(): Result {
		val challengeManager = EntryPointAccessors.fromApplication(
			applicationContext,
			ChallengeManagerEntryPoint::class.java
		).challengeManager()
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
