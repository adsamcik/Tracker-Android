package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class DatabaseMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val sessionSegmentDao: SessionSegmentDao,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        sessionSegmentDao.deleteEmpty()
        return Result.success()
    }

    companion object {
        private const val MAINTENANCE_UNIQUE_ID = "AppDatabaseMaintenance"
        private const val REPEAT_INTERVAL_H: Long = 6L

        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            val builder = PeriodicWorkRequestBuilder<DatabaseMaintenanceWorker>(
                REPEAT_INTERVAL_H,
                TimeUnit.HOURS
            )
            workManager.enqueueUniquePeriodicWork(
                MAINTENANCE_UNIQUE_ID,
                ExistingPeriodicWorkPolicy.UPDATE,
                builder.build()
            )
        }
    }
}
