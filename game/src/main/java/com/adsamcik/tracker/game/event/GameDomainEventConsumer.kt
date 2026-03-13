package com.adsamcik.tracker.game.event

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.game.CHALLENGE_LOG_SOURCE
import com.adsamcik.tracker.game.GAME_LOG_SOURCE
import com.adsamcik.tracker.game.GOALS_LOG_SOURCE
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.worker.ChallengeWorker
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consumes domain events relevant to gamification (challenges, goals).
 * Replaces broadcast-based ChallengeSessionReceiver (deleted) and GoalsSessionUpdateReceiver.
 * Uses consumer-offset tracking for crash-safe, ordered delivery.
 */
@Singleton
class GameDomainEventConsumer @Inject constructor(
	private val domainEventRepository: DomainEventRepository,
	@ApplicationContext private val context: Context,
	private val preferences: Preferences,
) {
	/** Process any unconsumed events for the game module. */
	suspend fun processUnconsumed() {
		val events = domainEventRepository.getUnconsumed(CONSUMER_ID)
		if (events.isEmpty()) return

		var latestTimestamp = EpochMs(0L)
		for (event in events) {
			handleEvent(event)
			if (event.timestampMs.raw > latestTimestamp.raw) {
				latestTimestamp = event.timestampMs
			}
		}
		domainEventRepository.markConsumed(CONSUMER_ID, latestTimestamp)
	}

	private suspend fun handleEvent(event: DomainEvent) {
		when (event) {
			is DomainEvent.SessionEnded -> onSessionEnded(event)
			is DomainEvent.DailySummaryUpdated -> onDailySummaryUpdated(event)
			is DomainEvent.AchievementUnlocked -> onAchievementUnlocked(event)
			is DomainEvent.AchievementProgress -> onAchievementProgress(event)
			else -> Unit
		}
	}

	/**
	 * Replaces ChallengeSessionReceiver.onReceive().
	 * Enqueues ChallengeWorker if challenges are enabled.
	 * Always enqueues AchievementWorker for post-session evaluation.
	 */
	private fun onSessionEnded(event: DomainEvent.SessionEnded) {
		val sessionId = event.sessionId
		if (sessionId <= 0L) return

		// Always enqueue achievement evaluation (the single unlock path)
		enqueueAchievementWorker()

		@Suppress("DEPRECATION")
		val challengesEnabled = preferences.getBooleanRes(
			R.string.settings_game_challenge_enable_key,
			R.string.settings_game_challenge_enable_default,
		)

		if (!challengesEnabled) return

		Logger.log(
			LogData(
				message = "SessionEnded event → scheduling ChallengeWorker for session $sessionId",
				source = CHALLENGE_LOG_SOURCE,
			),
		)

		val workManager = WorkManager.getInstance(context)
		val data = Data.Builder()
			.putLong(ChallengeWorker.ARG_SESSION_ID, sessionId)
			.build()
		val workRequest = OneTimeWorkRequestBuilder<ChallengeWorker>()
			.addTag(CHALLENGE_WORK_TAG)
			.setInputData(data)
			.setConstraints(
				Constraints.Builder()
					.setRequiresBatteryNotLow(true)
					.build(),
			)
			.build()
		workManager.enqueueUniqueWork(
			"$sessionId${ChallengeWorker.UNIQUE_WORK_NAME}",
			ExistingWorkPolicy.REPLACE,
			workRequest,
		)
	}

	private suspend fun onDailySummaryUpdated(event: DomainEvent.DailySummaryUpdated) {
		val cumulativeSteps = event.totalSteps.raw.toInt().coerceAtLeast(0)
		GoalTracker.updateCumulativeSteps(cumulativeSteps)
		Logger.log(
			LogData(
				message = "DailySummaryUpdated event → cumulative steps synced to goals: $cumulativeSteps",
				source = GOALS_LOG_SOURCE,
			),
		)
	}

	private fun onAchievementUnlocked(event: DomainEvent.AchievementUnlocked) {
		val title = context.getString(R.string.achievement_unlocked_notification_title, event.achievementId)
		val description = context.getString(R.string.achievement_unlocked_notification_description, event.tier)
		val launchIntent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
			?: Intent(Intent.ACTION_MAIN).apply {
				addCategory(Intent.CATEGORY_LAUNCHER)
				setPackage(context.packageName)
				flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
			}).apply {
			putExtra("navigate_to", "game")
			addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		}
		val contentIntent = PendingIntent.getActivity(
			context,
			NotificationsIds.achievementUnlocked(event),
			launchIntent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		NotificationManagerCompat.from(context).notify(
			NotificationsIds.achievementUnlocked(event),
			NotificationCompat.Builder(
				context,
				context.getString(com.adsamcik.tracker.shared.base.R.string.channel_challenges_id),
			)
				.setSmallIcon(R.drawable.ic_challenge_icon)
				.setContentTitle(title)
				.setContentText(description)
				.setStyle(NotificationCompat.BigTextStyle().bigText(description))
				.setContentIntent(contentIntent)
				.setAutoCancel(true)
				.build(),
		)
	}

	private fun onAchievementProgress(event: DomainEvent.AchievementProgress) {
		val progressFraction = if (event.targetValue <= 0L) 0.0 else event.currentValue.toDouble() / event.targetValue.toDouble()
		Logger.log(
			LogData(
				message = "Achievement progress: ${event.achievementId} ${event.currentValue}/${event.targetValue}",
				source = GAME_LOG_SOURCE,
			),
		)

		if (progressFraction >= ACHIEVEMENT_PROGRESS_NOTIFY_THRESHOLD) {
			val progressPercent = (progressFraction * 100.0).toInt().coerceIn(0, 100)
			val text = context.getString(
				R.string.achievement_progress_notification_description,
				progressPercent,
				event.currentValue,
				event.targetValue,
			)
			NotificationManagerCompat.from(context).notify(
				NotificationsIds.achievementProgress(event),
				NotificationCompat.Builder(
					context,
					context.getString(com.adsamcik.tracker.shared.base.R.string.channel_challenges_id),
				)
					.setSmallIcon(R.drawable.ic_challenge_icon)
					.setContentTitle(
						context.getString(
							R.string.achievement_progress_notification_title,
							event.achievementId,
						),
					)
					.setContentText(text)
					.setOnlyAlertOnce(true)
					.setAutoCancel(true)
					.build(),
			)
		}
	}

	private fun enqueueAchievementWorker() {
		Logger.log(
			LogData(
				message = "SessionEnded event → scheduling AchievementWorker",
				source = GAME_LOG_SOURCE,
			),
		)

		val workManager = WorkManager.getInstance(context)
		val workRequest = OneTimeWorkRequestBuilder<com.adsamcik.tracker.stats.data.worker.AchievementWorker>()
			.addTag(ACHIEVEMENT_WORK_TAG)
			.setConstraints(
				Constraints.Builder()
					.setRequiresBatteryNotLow(true)
					.build(),
			)
			.build()
		workManager.enqueueUniqueWork(
			com.adsamcik.tracker.stats.data.worker.AchievementWorker.UNIQUE_WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			workRequest,
		)
	}

	companion object {
		const val CONSUMER_ID = "game-module"
		private const val CHALLENGE_WORK_TAG = "Challenge"
		private const val ACHIEVEMENT_WORK_TAG = "Achievement"
		private const val ACHIEVEMENT_PROGRESS_NOTIFY_THRESHOLD = 0.90
	}

	private object NotificationsIds {
		fun achievementUnlocked(event: DomainEvent.AchievementUnlocked): Int {
			return "${event.achievementId}:${event.timestampMs.raw}:unlocked".hashCode()
		}

		fun achievementProgress(event: DomainEvent.AchievementProgress): Int {
			return "${event.achievementId}:${event.timestampMs.raw}:progress".hashCode()
		}
	}
}
