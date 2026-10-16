package com.adsamcik.tracker.game.event

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.game.progression.SessionXpAwardResult
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
import com.adsamcik.tracker.stats.api.scheduler.AchievementEvaluationScheduler
import com.adsamcik.tracker.stats.data.repository.AchievementMetricQualification
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
	private val achievementProgressDao: AchievementProgressDao,
	@ApplicationContext private val context: Context,
) {
	private val processMutex = Mutex()

	suspend fun processUnconsumed() = processMutex.withLock {
		while (true) {
			val expectedGeneration = trackingStartupGate.currentGeneration
			val batch = loadAcceptedBatch(expectedGeneration) ?: return@withLock
			if (batch.isEmpty()) return@withLock
			if (!processBatch(batch, expectedGeneration)) return@withLock
		}
	}

	private suspend fun processBatch(
		batch: List<UnconsumedEvent>,
		expectedGeneration: Long,
	): Boolean {
		var index = 0
		while (index < batch.size) {
			val unconsumed = batch[index]
			if (unconsumed.event is DomainEvent.AchievementUnlocked) {
				val firstIndex = index
				while (index < batch.size && batch[index].event is DomainEvent.AchievementUnlocked) {
					index += 1
				}
				if (!processAchievementEvents(batch.subList(firstIndex, index), expectedGeneration)) return false
				continue
			}
			if (!processOrdinaryEvent(unconsumed, expectedGeneration)) return false
			index += 1
		}
		return true
	}

	private suspend fun processOrdinaryEvent(unconsumed: UnconsumedEvent, expectedGeneration: Long): Boolean {
		val handled = try {
			handleEvent(unconsumed.event, expectedGeneration)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return false
		}
		return handled && acknowledgeEvent(unconsumed, expectedGeneration)
	}

	private suspend fun processAchievementEvents(events: List<UnconsumedEvent>, expectedGeneration: Long): Boolean {
		val run = events.map { requireNotNull(it.event as? DomainEvent.AchievementUnlocked) }
		if (!handleAchievementRun(run, expectedGeneration)) return false
		for (event in events) {
			if (!acknowledgeEvent(event, expectedGeneration)) return false
		}
		return true
	}

	/** Loads and consumes one ordered notification run under the same startup-generation lease. */
	private suspend fun handleAchievementRun(
		events: List<DomainEvent.AchievementUnlocked>,
		expectedGeneration: Long,
	): Boolean = try {
		trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
			// Qualified reconcilers use this same lease for row/outbox replacement. Keeping the
			// bounded authority read and notification delivery together prevents a correction from
			// interleaving after authorization but before user-visible delivery.
			val progressByMetric = achievementProgressDao.getAll().mapNotNull { row ->
				MetricKey.fromStorageKey(row.metricKey)?.let { metric -> metric to row }
			}.toMap()
			for (event in events) {
				if (isAuthorizedAchievement(event, progressByMetric)) onAchievementUnlocked(event)
			}
			true
		} ?: false
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Exception) {
		false
	}

	private fun isAuthorizedAchievement(
		event: DomainEvent.AchievementUnlocked,
		progressByMetric: Map<MetricKey, AchievementProgressEntity>,
	): Boolean {
		val definition = AchievementCatalog.byId(event.achievementId) ?: return false
		if (definition.tier.name != event.tier) return false
		val metric = definition.metric
		return if (AchievementMetricQualification.requiresQualifiedStepsAuthority(metric)) {
			isCurrentQualifiedStepsUnlock(event, metric, progressByMetric)
		} else {
			val row = progressByMetric[metric]
			// Live unlocks can precede progress persistence. A later recomputation may retract
			// that tier; an older row cannot veto a fresh event. Equal milliseconds keep the
			// event eligible because these wall-clock fields cannot order same-ms writes.
			AchievementMetricQualification.isTrustedPersistedProgress(metric, row) &&
				(row == null || row.lastTierIndex >= definition.tierIndex ||
					row.updatedAt <= event.timestampMs.raw)
		}
	}

	private suspend fun acknowledgeEvent(
		unconsumed: UnconsumedEvent,
		expectedGeneration: Long,
	): Boolean {
		val event = unconsumed.event
		return trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
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
		is DomainEvent.AchievementUnlocked -> false
		else -> trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
			when (event) {
				// This compatibility event has no source-qualified Steps authority. Durable numeric
				// observers refresh product state directly; consuming it must have no goal side effect.
				is DomainEvent.DailySummaryUpdated -> Unit
				else -> Unit
			}
			true
		} ?: false
	}

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private fun isCurrentQualifiedStepsUnlock(
		event: DomainEvent.AchievementUnlocked,
		metric: MetricKey,
		qualifiedByMetric: Map<MetricKey, AchievementProgressEntity>,
	): Boolean {
		val expectedRevision = event.authorityRevision ?: return false
		val expectedDigest = event.authorityDigest ?: return false
		val definition = AchievementCatalog.byId(event.achievementId) ?: return false
		if (definition.metric != metric || definition.tier.name != event.tier) return false
		val row = qualifiedByMetric[metric] ?: return false
		return row.authorizesQualifiedUnlock(expectedRevision, expectedDigest, definition.tierIndex)
	}

	private fun AchievementProgressEntity.authorizesQualifiedUnlock(
		expectedRevision: Long,
		expectedDigest: String,
		tierIndex: Int,
	): Boolean {
		if (authorityKind != AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1 ||
			authorityState != AchievementProgressEntity.AUTHORITY_STATE_READY ||
			authorityRevision != expectedRevision || authorityDigest != expectedDigest
		) {
			return false
		}
		return lastTierIndex >= tierIndex && qualifiedNotificationClaimedTierIndex?.let { it >= tierIndex } == true
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
		fun achievementUnlocked(event: DomainEvent.AchievementUnlocked): Int = if (
			event.authorityRevision != null && event.authorityDigest != null
		) {
			"${event.achievementId}:${event.tier}:qualified-unlocked".hashCode()
		} else {
			"${event.achievementId}:${event.timestampMs.raw}:unlocked".hashCode()
		}
	}

}
