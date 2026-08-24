package com.adsamcik.tracker.activity.api.registration

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Rare, source-local retry owner for GMS removals that outlive collected Room rows. */
@HiltWorker
internal class ActivityRegistrationCleanupWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val arbiter: ActivityRegistrationArbiter,
) : CoroutineWorker(appContext, params) {
	override suspend fun doWork(): Result {
		return when (runActivityRegistrationCleanup(arbiter)) {
			ActivityRegistrationCleanupWorkOutcome.COMPLETE -> Result.success()
			ActivityRegistrationCleanupWorkOutcome.RETRY -> Result.retry()
			ActivityRegistrationCleanupWorkOutcome.FAILED -> Result.failure()
		}
	}
}

internal enum class ActivityRegistrationCleanupWorkOutcome { COMPLETE, RETRY, FAILED }

internal suspend fun runActivityRegistrationCleanup(
	arbiter: ActivityRegistrationArbiter,
): ActivityRegistrationCleanupWorkOutcome {
	val cleanup = arbiter.retryPendingProviderCleanup()
	return when {
		// This worker may run before Tracker Room startup. It owns only the no-backup cleanup
		// journal and provider removals; the startup/post-deletion reconciler resumes demands later.
		cleanup.complete -> ActivityRegistrationCleanupWorkOutcome.COMPLETE
		cleanup.retryable -> ActivityRegistrationCleanupWorkOutcome.RETRY
		else -> ActivityRegistrationCleanupWorkOutcome.FAILED
	}
}

@Singleton
class ActivityRegistrationCleanupScheduler @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	fun ensureScheduled() {
		val request = OneTimeWorkRequestBuilder<ActivityRegistrationCleanupWorker>()
			.setBackoffCriteria(
				BackoffPolicy.EXPONENTIAL,
				MINIMUM_BACKOFF_SECONDS,
				TimeUnit.SECONDS,
			)
			.build()
		WorkManager.getInstance(context).enqueueUniqueWork(
			UNIQUE_WORK_NAME,
			ExistingWorkPolicy.KEEP,
			request,
		)
	}

	companion object {
		internal const val UNIQUE_WORK_NAME = "ACTIVITY.REGISTRATION_CLEANUP"
		private const val MINIMUM_BACKOFF_SECONDS = 30L
	}
}
