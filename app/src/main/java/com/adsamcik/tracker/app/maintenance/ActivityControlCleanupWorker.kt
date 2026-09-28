package com.adsamcik.tracker.app.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.source.projection.ActivityControlRetentionMaintenance
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import kotlinx.coroutines.CancellationException

/** CONTROL evidence has its own lifetime even when optional history cleanup is disabled. */
@HiltWorker
class ActivityControlCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val startupGate: TrackingStartupGate,
    private val maintenance: ActivityControlRetentionMaintenance,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        when (startupGate.reconcile()) {
            is TrackingStartupResult.Ready ->
                if (maintenance.pruneDue()) Result.retry() else Result.success()
            is TrackingStartupResult.RetryableFailure -> Result.retry()
            is TrackingStartupResult.Blocked -> Result.success()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val WORK_NAME = "APP.ACTIVITY_CONTROL_EXPIRY"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ActivityControlCleanupWorker>(Duration.ofHours(12))
                    .build(),
            )
        }
    }
}
