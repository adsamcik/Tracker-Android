package com.adsamcik.tracker.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsImportAttemptResult
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsImportRunner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Unique periodic work preserves its original cadence through settings/session changes. */
@Singleton
class AmbientStepsImportWorkScheduler @Inject constructor(
	private val workManager: WorkManager,
) {
	suspend fun reconcile(backgroundAllowed: Boolean, requestNow: Boolean) {
		if (backgroundAllowed) {
			workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP,
				PeriodicWorkRequestBuilder<AmbientStepsImportWorker>(1, TimeUnit.HOURS)
					.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()).await()
		} else {
			workManager.cancelUniqueWork(PERIODIC_WORK).await()
		}
		if (requestNow) this.requestNow()
	}

	suspend fun requestNow() {
		workManager.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP,
			OneTimeWorkRequestBuilder<AmbientStepsImportWorker>()
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()).await()
	}

	suspend fun cancel() {
		workManager.cancelUniqueWork(PERIODIC_WORK).await()
		workManager.cancelUniqueWork(IMMEDIATE_WORK).await()
	}

	internal companion object {
		const val PERIODIC_WORK = "ambient-steps-hourly-import-v1"
		const val IMMEDIATE_WORK = "ambient-steps-current-import-v1"
	}
}

@HiltWorker
class AmbientStepsImportWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParameters: WorkerParameters,
	private val runner: AmbientStepsImportRunner,
) : CoroutineWorker(context, workerParameters) {
	override suspend fun doWork(): Result = when (runner.run()) {
		AmbientStepsImportAttemptResult.COMPLETE,
		AmbientStepsImportAttemptResult.DEFERRED -> Result.success()
		AmbientStepsImportAttemptResult.RETRY -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
	}

	private companion object { const val MAX_RETRIES = 2 }
}
