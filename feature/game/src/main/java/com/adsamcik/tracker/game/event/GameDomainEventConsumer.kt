package com.adsamcik.tracker.game.event

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.game.progression.SessionXpAwardResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.scheduler.AchievementEvaluationScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class GameDomainEventConsumer @Inject constructor(
	private val domainEventRepository: DomainEventRepository,
	private val achievementEvaluationScheduler: AchievementEvaluationScheduler,
	private val progressionRepository: PlayerProgressionRepository,
	private val trackingStartupGate: TrackingStartupGate,
	@ApplicationContext private val context: Context,
) {
	private val processMutex = Mutex()

	suspend fun processUnconsumed() = processMutex.withLock {
		while (true) {
			val expectedGeneration = trackingStartupGate.currentGeneration
			val batch = loadAcceptedBatch(expectedGeneration) ?: return@withLock
			if (batch.isEmpty()) return@withLock
			batch.forEach { unconsumed ->
				val event = unconsumed.event
				val handled = try {
					handleEvent(event, expectedGeneration)
				} catch (e: CancellationException) {
					throw e
				} catch (_: Exception) {
					return@withLock
				}
				if (!handled) return@withLock
				val acknowledged = trackingStartupGate.withReadyGenerationOperation(
					expectedGeneration,
				) {
					if (event is DomainEvent.SessionEnded && event.sessionId > 0L) {
						enqueueAchievementWorker()
					}
					domainEventRepository.markBatchConsumed(
						CONSUMER_ID,
						event.timestampMs,
						unconsumed.persistedId,
					)
					true
				} ?: false
				if (!acknowledged) return@withLock
			}
		}
	}

	private suspend fun loadAcceptedBatch(expectedGeneration: Long) = try {
		if (trackingStartupGate.reconcile() !is TrackingStartupResult.Ready) {
			null
		} else {
			trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
				domainEventRepository.getUnconsumedBatchWithIds(
					CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			}
		}
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Exception) {
		null
	}

	private suspend fun handleEvent(
		event: DomainEvent,
		expectedGeneration: Long,
	): Boolean = when (event) {
		is DomainEvent.SessionEnded -> {
			if (!trackingStartupGate.isReadyGeneration(expectedGeneration)) {
				false
			} else {
				// Passive XP feeds the player level that gates mini-game unlocks.
				progressionRepository.awardSessionXp(event, expectedGeneration) ==
					SessionXpAwardResult.COMPLETED
			}
		}
		else -> trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
			when (event) {
				is DomainEvent.DailySummaryUpdated -> onDailySummaryUpdated(event)
				is DomainEvent.AchievementUnlocked -> onAchievementUnlocked(event)
				else -> Unit
			}
			true
		} ?: false
	}

	private suspend fun onDailySummaryUpdated(event: DomainEvent.DailySummaryUpdated) {
		val cumulativeSteps = event.totalSteps.raw.toInt().coerceAtLeast(0)
		GoalTracker.updateUnqualifiedCumulativeStepsPresentation(cumulativeSteps)
	}

	private fun onAchievementUnlocked(event: DomainEvent.AchievementUnlocked) {
		val title = context.getString(R.string.achievement_unlocked_notification_title, event.achievementId)
		val description = context.getString(R.string.achievement_unlocked_notification_description, event.tier)
		val launchIntent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
			?: Intent(Intent.ACTION_MAIN).apply {
				addCategory(Intent.CATEGORY_LAUNCHER)
				setPackage(context.packageName)
				flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
			}).apply { putExtra("navigate_to", "game"); addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) }
		val contentIntent = PendingIntent.getActivity(context, NotificationsIds.achievementUnlocked(event), launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
		NotificationManagerCompat.from(context).notify(
			NotificationsIds.achievementUnlocked(event),
			NotificationCompat.Builder(context, context.getString(com.adsamcik.tracker.shared.base.R.string.channel_achievements_id))
				.setSmallIcon(R.drawable.ic_achievement_icon)
				.setContentTitle(title)
				.setContentText(description)
				.setStyle(NotificationCompat.BigTextStyle().bigText(description))
				.setContentIntent(contentIntent)
				.setAutoCancel(true)
				.build(),
		)
	}

	private fun enqueueAchievementWorker() {
		achievementEvaluationScheduler.scheduleEvaluation()
	}

	companion object {
		const val CONSUMER_ID = "game-module"
	}

	private object NotificationsIds {
		fun achievementUnlocked(event: DomainEvent.AchievementUnlocked): Int = "${event.achievementId}:${event.timestampMs.raw}:unlocked".hashCode()
	}
}
