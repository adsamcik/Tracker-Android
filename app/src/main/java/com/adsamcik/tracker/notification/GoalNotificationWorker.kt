package com.adsamcik.tracker.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.R
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import com.adsamcik.tracker.shared.preferences.Preferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate

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
    private val preferences: Preferences,
    private val goalsSettingsRepository: GoalsSettingsRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!goalsSettingsRepository.data.first().notificationsEnabled) return Result.success()

        val progress = goalProgressProvider.goalProgressFlow.first { candidate ->
            candidate.stepsToday != QualifiedStepCount.Unavailable(
                QualifiedStepCountUnavailableReason.MISSING,
            )
        }
        if (!progress.gamificationEnabled || progress.goalSteps <= 0) {
            return Result.success()
        }
        val stepsToday = (progress.stepsToday as? QualifiedStepCount.Ready)?.value
            ?: return Result.success()

        val ratio = requireNotNull(progress.progress)
        val remaining = (progress.goalSteps - stepsToday).coerceAtLeast(0)
        val todayEpochDay = LocalDate.now().toEpochDay()
        val lastNotifiedDay = preferences.fetchLong(KEY_LAST_NOTIFIED_DAY, -1L)
        val lastNotifiedThreshold = preferences.fetchInt(KEY_LAST_NOTIFIED_THRESHOLD, 0)

        val threshold = progressNotificationThreshold(ratio) ?: return Result.success()

        // Already notified for this threshold (or higher) today
        if (lastNotifiedDay == todayEpochDay && lastNotifiedThreshold >= threshold) {
            return Result.success()
        }

        sendNotification(threshold, remaining)

        preferences.editSuspend {
            setLong(KEY_LAST_NOTIFIED_DAY, todayEpochDay)
            setInt(KEY_LAST_NOTIFIED_THRESHOLD, threshold)
        }

        return Result.success()
    }

    private fun sendNotification(
        threshold: Int,
        remaining: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
            if (perm != PackageManager.PERMISSION_GRANTED) return
        }

        val channelId = appContext.getString(com.adsamcik.tracker.shared.base.R.string.channel_goals_id)

        val title = when (threshold) {
            THRESHOLD_90 -> appContext.getString(R.string.goal_notification_title_almost)
            else -> appContext.getString(R.string.goal_notification_title_progress)
        }
        val body = when (threshold) {
            THRESHOLD_90 -> appContext.resources.getQuantityString(
                R.plurals.goal_notification_body_90,
                remaining,
                remaining,
            )
            else -> appContext.resources.getQuantityString(
                R.plurals.goal_notification_body_75,
                remaining,
                remaining,
            )
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
            .setContentTitle(title)
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
        internal const val KEY_LAST_NOTIFIED_DAY = "last_goal_notification_day"
        internal const val KEY_LAST_NOTIFIED_THRESHOLD = "last_goal_notification_threshold"

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

        internal fun progressNotificationThreshold(progress: Float): Int? = when {
            progress >= 1f -> null
            progress >= 0.90f -> THRESHOLD_90
            progress >= 0.75f -> THRESHOLD_75
            else -> null
        }
    }
}
