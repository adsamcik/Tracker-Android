package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Schedules periodic and event-driven widget updates.
 *
 * Update strategy:
 * - Periodic: WorkManager PeriodicWorkRequest every 15 minutes (minimum allowed).
 * - Event-driven: Called from ToggleTrackingAction and TrackerService lifecycle
 *   broadcasts to provide near-instant updates on start/stop.
 *
 * Limitation: True real-time updates (every 5s) are not feasible with Glance.
 * Android enforces a 15-minute minimum for periodic WorkManager tasks.
 * The widget snapshot is refreshed on tracking state changes and periodically.
 */
object WidgetUpdateScheduler {

    private const val WORK_NAME = "tracker_widget_update"

    /**
     * Enqueues a periodic 15-minute widget update worker.
     * Safe to call multiple times; KEEP policy preserves existing work.
     */
    fun schedulePeriodicUpdates(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
            15, TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Cancels periodic widget updates.
     * Call when all widgets are removed.
     */
    fun cancelPeriodicUpdates(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Immediately updates all Glance widgets.
     * Called from action callbacks and service lifecycle events.
     */
    suspend fun updateAllWidgets(context: Context) {
        QuickStartWidget().updateAll(context)
        TodaySummaryWidget().updateAll(context)
        ActiveSessionWidget().updateAll(context)
    }
}

/**
 * WorkManager Worker that triggers widget refresh.
 */
class WidgetUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        WidgetUpdateScheduler.updateAllWidgets(applicationContext)
        return Result.success()
    }
}
