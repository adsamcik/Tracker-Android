package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.adsamcik.tracker.tracker.worker.PendingSignalDrainWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface PendingSignalDrainScheduler {
	fun enqueueExpedited()
}

@Singleton
class WorkManagerPendingSignalDrainScheduler @Inject constructor(
	@ApplicationContext private val context: Context,
) : PendingSignalDrainScheduler {
	override fun enqueueExpedited() {
		WorkManager.getInstance(context).enqueueUniqueWork(
			UNIQUE_WORK_NAME,
			ExistingWorkPolicy.KEEP,
			buildPendingSignalDrainRequest(),
		)
	}
}

internal fun buildPendingSignalDrainRequest(): OneTimeWorkRequest =
	OneTimeWorkRequestBuilder<PendingSignalDrainWorker>()
		.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
		.build()

private const val UNIQUE_WORK_NAME = "TRACKER.PENDING_SIGNAL_CRASH_DRAIN"

