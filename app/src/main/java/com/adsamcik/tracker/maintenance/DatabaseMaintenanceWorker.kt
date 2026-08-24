package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Provider

@HiltWorker
class DatabaseMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
	private val appDatabaseProvider: Provider<AppDatabase>,
	private val trackingStartupGate: TrackingStartupGate,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
		val startupGeneration = trackingStartupGate.currentGeneration
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
		}
		if (!trackingStartupGate.isReady ||
			trackingStartupGate.currentGeneration != startupGeneration
		) {
			return Result.success()
		}
		val database = appDatabaseProvider.get()
		try {
			database.withTransaction {
				requireReadyGeneration(startupGeneration)
				try {
					database.sessionSegmentDao().deleteEmpty()
				} finally {
					requireReadyGeneration(startupGeneration)
				}
			}
		} catch (_: StartupGenerationChangedException) {
			return Result.success()
		}
        return Result.success()
    }

	private fun requireReadyGeneration(startupGeneration: Long) {
		if (!trackingStartupGate.isReady ||
			trackingStartupGate.currentGeneration != startupGeneration
		) {
			throw StartupGenerationChangedException
		}
	}

    companion object {
        internal const val MAINTENANCE_UNIQUE_ID = "AppDatabaseMaintenance"
        private const val REPEAT_INTERVAL_H: Long = 6L

        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            val builder = PeriodicWorkRequestBuilder<DatabaseMaintenanceWorker>(
                REPEAT_INTERVAL_H,
                TimeUnit.HOURS
            )
            workManager.enqueueUniquePeriodicWork(
                MAINTENANCE_UNIQUE_ID,
                ExistingPeriodicWorkPolicy.KEEP,
                builder.build()
            )
        }

		fun cancel(context: Context): Operation =
			WorkManager.getInstance(context).cancelUniqueWork(MAINTENANCE_UNIQUE_ID)
    }

	private object StartupGenerationChangedException : RuntimeException()
}
