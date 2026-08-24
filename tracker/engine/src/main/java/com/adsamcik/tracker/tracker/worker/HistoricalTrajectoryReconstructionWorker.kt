package com.adsamcik.tracker.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.reconstruction.HistoricalTrajectoryReconstructionRunner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider

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
	private val appDatabaseProvider: Provider<AppDatabase>,
	private val runnerProvider: Provider<HistoricalTrajectoryReconstructionRunner>,
	private val trackingStartupGate: TrackingStartupGate,
) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val startupGeneration = trackingStartupGate.currentGeneration
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
		}
		return try {
			requireReadyGeneration(startupGeneration)
			val database = appDatabaseProvider.get()
			requireReadyGeneration(startupGeneration)
			val pendingSignalDao = database.pendingSignalDao()
			requireReadyGeneration(startupGeneration)
			val trackerStateEventDao = database.trackerStateEventDao()
			requireReadyGeneration(startupGeneration)
			val runner = runnerProvider.get()
			requireReadyGeneration(startupGeneration)
			if (pendingSignalDao.hasAny()) return Result.retry()
			requireReadyGeneration(startupGeneration)
			val sessions = trackerStateEventDao.getCompletedSessionsAwaitingReconstruction(
				algorithmVersion = runner.algorithmVersion,
				configurationVersion = runner.configurationVersion,
			)
			for (session in sessions) {
				requireReadyGeneration(startupGeneration)
				if (pendingSignalDao.hasAny()) return Result.retry()
				requireReadyGeneration(startupGeneration)
				runner.reconstruct(session) { requireReadyGeneration(startupGeneration) }
			}
			Result.success()
		} catch (_: StartupGenerationChangedException) {
			Result.success()
		} catch (exception: IllegalStateException) {
			Result.retry()
		} catch (exception: Exception) {
			Result.retry()
		}
	}

	private fun requireReadyGeneration(startupGeneration: Long) {
		if (!trackingStartupGate.isReady ||
			trackingStartupGate.currentGeneration != startupGeneration
		) {
			throw StartupGenerationChangedException
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

	private object StartupGenerationChangedException : RuntimeException()
}
