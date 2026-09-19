package com.adsamcik.tracker.app.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class RetentionCancellationRecoveryWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val retentionWorkScheduler: RetentionWorkScheduler,
	private val retentionConfigStore: RetentionConfigStore,
) : CoroutineWorker(appContext, params) {
	override suspend fun doWork(): Result {
		return try {
			retentionWorkScheduler.recoverCurrentPreference(retentionConfigStore)
			Result.success()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RetentionWorkCancellationPendingException) {
			Result.retry()
		} catch (_: RetentionScheduleAuthorityUnavailableException) {
			Result.retry()
		} catch (_: Exception) {
			Result.retry()
		}
	}

	companion object {
		internal const val UNIQUE_WORK_NAME = "APP.RETENTION_CANCELLATION_RECOVERY"
	}
}
