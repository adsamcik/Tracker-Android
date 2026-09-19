package com.adsamcik.tracker.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.adsamcik.tracker.tracker.api.TrackingPurposeReconciliationRetryScheduler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class TrackingPurposeReconciliationWorkScheduler @Inject constructor(
	@ApplicationContext private val context: Context,
) : TrackingPurposeReconciliationRetryScheduler {
	override suspend fun schedule(debt: TrackingPurposeSettingsReconciliationDebt): Boolean =
		try {
			val failures = debt.failures.map { failure ->
				"${failure.source?.name ?: "GLOBAL"}:${failure.reason.name}:" +
					(failure.retentionReason ?: "")
			}.toTypedArray()
			val request = OneTimeWorkRequestBuilder<TrackingPurposeReconciliationRetryWorker>()
				.setInputData(workDataOf(FAILURES_KEY to failures))
				.setBackoffCriteria(
					BackoffPolicy.EXPONENTIAL,
					MIN_BACKOFF_SECONDS,
					TimeUnit.SECONDS,
				)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(
				UNIQUE_WORK_NAME,
				ExistingWorkPolicy.KEEP,
				request,
			).await()
			true
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			false
		}

	internal companion object {
		const val UNIQUE_WORK_NAME = "tracking-purpose-reconciliation-retry-v1"
		const val FAILURES_KEY = "tracking_purpose_reconciliation_failures"
		const val MIN_BACKOFF_SECONDS = 10L
	}
}

@HiltWorker
class TrackingPurposeReconciliationRetryWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParameters: WorkerParameters,
	private val reconciler: TrackingPurposeSettingsReconciler,
) : CoroutineWorker(context, workerParameters) {
	override suspend fun doWork(): Result = try {
		when (reconciler.reconcileCurrentSettings()) {
			is TrackingPurposeSettingsReconciliationResult.Complete -> Result.success()
			is TrackingPurposeSettingsReconciliationResult.Debt -> Result.retry()
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		Result.retry()
	}
}
