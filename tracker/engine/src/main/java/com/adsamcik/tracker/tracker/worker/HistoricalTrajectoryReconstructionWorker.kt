package com.adsamcik.tracker.tracker.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerStateEventDao
import com.adsamcik.tracker.tracker.reconstruction.HistoricalTrajectoryReconstructionRunner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs after a tracker run has closed and the persistence WAL has drained.
 *
 * A source-revision conflict is retryable: the runner refuses to publish a derivative from a
 * snapshot that changed while it was being computed.
 */
@HiltWorker
class HistoricalTrajectoryReconstructionWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val pendingSignalDao: PendingSignalDao,
	private val trackerStateEventDao: TrackerStateEventDao,
	private val runner: HistoricalTrajectoryReconstructionRunner,
) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		if (pendingSignalDao.hasAny()) return Result.retry()
		return try {
			val sessions = trackerStateEventDao.getCompletedSessionsAwaitingReconstruction(
				algorithmVersion = runner.algorithmVersion,
				configurationVersion = runner.configurationVersion,
			)
			for (session in sessions) {
				if (pendingSignalDao.hasAny()) return Result.retry()
				runner.reconstruct(session)
			}
			Result.success()
		} catch (exception: IllegalStateException) {
			Log.i(TAG, "Historical reconstruction source changed; retrying", exception)
			Result.retry()
		} catch (exception: Exception) {
			Log.w(TAG, "Historical reconstruction failed", exception)
			Result.retry()
		}
	}

	companion object {
		const val UNIQUE_WORK_NAME = "historical-trajectory-reconstruction"
		const val TAG = "HistoricalReconstruction"

		fun schedule(context: Context) {
			val request = OneTimeWorkRequestBuilder<HistoricalTrajectoryReconstructionWorker>()
				.addTag(TAG)
				.setConstraints(
					Constraints.Builder()
						.setRequiresBatteryNotLow(true)
						.setRequiresStorageNotLow(true)
						.build(),
				)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(
				UNIQUE_WORK_NAME,
				ExistingWorkPolicy.REPLACE,
				request,
			)
		}
	}
}
