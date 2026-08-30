package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Compatibility shell for the retired periodic empty-segment cleanup.
 *
 * Older installs may still have this worker persisted in WorkManager. It intentionally performs no
 * database mutation: lifecycle rows can become terminal before the tracking components finish
 * their final writes, so no current SQL predicate can safely identify an abandoned empty segment.
 * [cancel] removes the legacy periodic request when maintenance initialization next runs.
 */
@HiltWorker
class DatabaseMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

	override suspend fun doWork(): Result = Result.success()

    companion object {
        internal const val MAINTENANCE_UNIQUE_ID = "AppDatabaseMaintenance"

		fun cancel(context: Context): Operation =
			WorkManager.getInstance(context).cancelUniqueWork(MAINTENANCE_UNIQUE_ID)
    }
}
