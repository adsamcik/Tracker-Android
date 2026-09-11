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
import com.adsamcik.tracker.stats.api.repository.AchievementRepository
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
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
	private val achievementRepository: AchievementRepository,
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
		val achievementAuthority = loadAchievementAuthority(batch, expectedGeneration) ?: return false
		for (unconsumed in batch) {
			val handled = try {
				handleEvent(unconsumed.event, expectedGeneration, achievementAuthority)
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (_: Exception) {
				return false
			}
			if (!handled || !acknowledgeEvent(unconsumed, expectedGeneration)) return false
		}
		return true
	}

	private suspend fun loadAchievementAuthority(
		batch: List<UnconsumedEvent>,
		expectedGeneration: Long,
	): AchievementNotificationBatchAuthority? = try {
		if (batch.none { it.event is DomainEvent.AchievementUnlocked }) {
			AchievementNotificationBatchAuthority(emptyMap(), emptyMap())
		} else {
			trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
				// One bounded snapshot read plus one qualified-row read per batch.
				// Generic live events can precede progress persistence. Dedicated qualified-Steps
				// events are committed with their row and additionally require it to remain unlocked.
				val byAchievementId = achievementRepository.getAllSnapshots().associate {
					it.id to AchievementNotificationAuthority(it.tier.name, it.metric)
				}
				val qualifiedByMetric = achievementProgressDao.getQualifiedStepsGoalRows()
					.mapNotNull { row ->
						MetricKey.fromStorageKey(row.metricKey)?.let { metric -> metric to row }
					}
					.toMap()
				AchievementNotificationBatchAuthority(byAchievementId, qualifiedByMetric)
			}
		}
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Exception) {
		null
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
		achievementAuthority: AchievementNotificationBatchAuthority,
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
				// This compatibility event has no source-qualified Steps authority. Durable numeric
				// observers refresh product state directly; consuming it must have no goal side effect.
				is DomainEvent.DailySummaryUpdated -> Unit
				is DomainEvent.AchievementUnlocked -> {
					val authority = achievementAuthority.byAchievementId[event.achievementId]
					val metric = authority?.metric ?: AchievementCatalog.byId(event.achievementId)?.metric
					val authorized = if (metric.isQualifiedStepsGoalMetric()) {
						isCurrentQualifiedStepsUnlock(
							event,
							requireNotNull(metric),
							achievementAuthority.qualifiedByMetric,
						)
					} else {
						authority?.tier == event.tier
					}
					if (authorized) {
						onAchievementUnlocked(event)
					}
				}
				else -> Unit
			}
			true
		} ?: false
	}

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
		return row.authorityKind == AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1 &&
			row.authorityState == AchievementProgressEntity.AUTHORITY_STATE_READY &&
			row.authorityRevision >= expectedRevision &&
			row.authorityDigest == expectedDigest &&
			row.lastTierIndex >= definition.tierIndex &&
			row.qualifiedNotificationClaimedTierIndex != null &&
			row.qualifiedNotificationClaimedTierIndex >= definition.tierIndex
	}

	private fun MetricKey?.isQualifiedStepsGoalMetric(): Boolean =
		this == MetricKey.GOAL_STREAK_DAYS || this == MetricKey.PERFECT_WEEKS

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

	private data class AchievementNotificationAuthority(
		val tier: String,
		val metric: MetricKey,
	)

	private data class AchievementNotificationBatchAuthority(
		val byAchievementId: Map<String, AchievementNotificationAuthority>,
		val qualifiedByMetric: Map<MetricKey, AchievementProgressEntity>,
	)
}
