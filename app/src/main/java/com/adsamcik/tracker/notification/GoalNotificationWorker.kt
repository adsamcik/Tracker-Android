package com.adsamcik.tracker.notification

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate

private val Context.goalNotificationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "goal_notification_state",
)

/**
 * Periodic worker that checks daily step goal progress and sends a notification
 * when the user approaches their target. Runs every 2 hours with battery-not-low
 * constraint.
 *
 * Notifies once per threshold per day to avoid spamming:
 * - 75% reached
 * - 90% reached
 */
@HiltWorker
class GoalNotificationWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val goalProgressProvider: GoalProgressProvider,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = appContext.goalNotificationDataStore.data.first()
        val enabled = prefs[KEY_ENABLED] ?: true
        if (!enabled) return Result.success()

        val progress = goalProgressProvider.goalProgressFlow.value
        if (!progress.gamificationEnabled || progress.goalSteps <= 0) {
            return Result.success()
        }

        val ratio = progress.progress
        val todayEpochDay = LocalDate.now().toEpochDay()
        val lastNotifiedDay = prefs[KEY_LAST_NOTIFIED_DAY] ?: -1L
        val lastNotifiedThreshold = prefs[KEY_LAST_NOTIFIED_THRESHOLD] ?: 0

        val threshold = when {
            ratio >= 0.90f -> THRESHOLD_90
            ratio >= 0.75f -> THRESHOLD_75
            else -> return Result.success()
        }

        // Already completed — no need to nag
        if (ratio >= 1.0f) return Result.success()

        // Already notified for this threshold (or higher) today
        if (lastNotifiedDay == todayEpochDay && lastNotifiedThreshold >= threshold) {
            return Result.success()
        }

        sendNotification(threshold)

        appContext.goalNotificationDataStore.edit { mutable ->
            mutable[KEY_LAST_NOTIFIED_DAY] = todayEpochDay
            mutable[KEY_LAST_NOTIFIED_THRESHOLD] = threshold
        }

        return Result.success()
    }

    private fun sendNotification(threshold: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            if (perm != PackageManager.PERMISSION_GRANTED) return
        }

        val channelId = appContext.getString(com.adsamcik.tracker.shared.base.R.string.channel_goals_id)

        val body = when (threshold) {
            THRESHOLD_90 -> appContext.getString(R.string.goal_notification_body_90)
            else -> appContext.getString(R.string.goal_notification_body_75)
        }

        val launchIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                appContext,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_directions_walk_white)
            .setContentTitle(appContext.getString(R.string.goal_notification_title))
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "APP.GOAL_NOTIFICATION"
        private const val NOTIFICATION_ID = 9001
        internal const val THRESHOLD_75 = 75
        internal const val THRESHOLD_90 = 90

        internal val KEY_ENABLED = booleanPreferencesKey("smart_goal_notifications_enabled")
        internal val KEY_LAST_NOTIFIED_DAY = longPreferencesKey("last_goal_notification_day")
        internal val KEY_LAST_NOTIFIED_THRESHOLD = intPreferencesKey("last_goal_notification_threshold")

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<GoalNotificationWorker>(Duration.ofHours(2))
                .setConstraints(constraints)
                .addTag(UNIQUE_WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
