package com.adsamcik.tracker.app.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class RetentionCancellationRecoveryWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val retentionWorkScheduler: RetentionWorkScheduler,
) : CoroutineWorker(appContext, params) {
	override suspend fun doWork(): Result {
		val enabled = when (inputData.getString(MODE_KEY)) {
			MODE_ENABLED -> true
			MODE_DISABLED -> false
			else -> return Result.failure()
		}
		return try {
			retentionWorkScheduler.recoverCurrentPreference(enabled)
			Result.success()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RetentionWorkCancellationPendingException) {
			Result.retry()
		} catch (_: Exception) {
			Result.retry()
		}
	}

	companion object {
		internal const val UNIQUE_WORK_NAME = "APP.RETENTION_CANCELLATION_RECOVERY"
		internal const val MODE_KEY = "retention_schedule_mode"
		internal const val MODE_ENABLED = "ENABLED"
		internal const val MODE_DISABLED = "DISABLED"
	}
}
