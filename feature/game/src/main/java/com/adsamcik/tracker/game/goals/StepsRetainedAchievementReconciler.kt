package com.adsamcik.tracker.game.goals

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetrics
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetricsDecision
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetricsRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Correction-safe lifetime and best-day achievement projection from retained Steps source facts. */
@Singleton
internal class StepsRetainedAchievementReconciler @Inject constructor(
	private val database: AppDatabase,
	private val retainedMetrics: StepsRetainedMetricsRepository,
	private val dispatchers: DispatchersProvider,
	private val startupGate: TrackingStartupGate,
	private val domainEvents: DomainEventRepository,
) {
	suspend fun reconcile(observedAtMs: Long): StepsRetainedAchievementReconcileResult {
		require(observedAtMs >= 0L)
		val expectedGeneration = startupGate.currentGeneration
		val decision = try {
			retainedMetrics.readDecision()
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return StepsRetainedAchievementReconcileResult.RetryableFailure
		}
		if (decision !is StepsRetainedMetricsDecision.Snapshot) {
			return StepsRetainedAchievementReconcileResult.RetryableFailure
		}
		return try {
			startupGate.withReadyGenerationOperation(expectedGeneration) {
				withContext(dispatchers.io) {
					database.withTransaction {
						val currentRevision = database.sourceEvidenceStateDao().get()?.revision
						if (currentRevision != decision.sourceEvidenceRevision) {
							return@withTransaction StepsRetainedAchievementReconcileResult.RetryableFailure
						}
						val currentRows = database.achievementProgressDao()
							.getQualifiedStepsAchievementRows()
							.associateBy(AchievementProgressEntity::metricKey)
						when (val retained = decision.result) {
							is StepsRetainedMetrics.Ready -> applyReady(
								retained = retained,
								decision = decision,
								currentRows = currentRows,
								observedAtMs = observedAtMs,
							)
							StepsRetainedMetrics.Materializing ->
								StepsRetainedAchievementReconcileResult.Materializing(
									markAuthorityState(
										currentRows,
										decision,
										AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING,
										observedAtMs,
									),
								)
							is StepsRetainedMetrics.Unverifiable ->
								StepsRetainedAchievementReconcileResult.Unverifiable(
									markAuthorityState(
										currentRows,
										decision,
										AchievementProgressEntity.AUTHORITY_STATE_UNVERIFIABLE,
										observedAtMs,
									),
								)
						}
					}
				}
			} ?: StepsRetainedAchievementReconcileResult.RetryableFailure
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			StepsRetainedAchievementReconcileResult.RetryableFailure
		}
	}

	private suspend fun applyReady(
		retained: StepsRetainedMetrics.Ready,
		decision: StepsRetainedMetricsDecision.Snapshot,
		currentRows: Map<String, AchievementProgressEntity>,
		observedAtMs: Long,
	): StepsRetainedAchievementReconcileResult {
		val total = replace(
			metric = MetricKey.STEPS_TOTAL,
			value = retained.totalSteps,
			decision = decision,
			current = currentRows[MetricKey.STEPS_TOTAL.storageKey],
			observedAtMs = observedAtMs,
		)
		val best = replace(
			metric = MetricKey.BEST_DAILY_STEPS,
			value = retained.bestDailySteps,
			decision = decision,
			current = currentRows[MetricKey.BEST_DAILY_STEPS.storageKey],
			observedAtMs = observedAtMs,
		)
		val unlocks = total.unlocks + best.unlocks
		if (unlocks.isNotEmpty()) domainEvents.persist(unlocks)
		return StepsRetainedAchievementReconcileResult.Applied(total.changed || best.changed)
	}

	private suspend fun markAuthorityState(
		currentRows: Map<String, AchievementProgressEntity>,
		decision: StepsRetainedMetricsDecision.Snapshot,
		authorityState: String,
		observedAtMs: Long,
	): Boolean = RETAINED_STEPS_METRICS.fold(false) { changed, metric ->
		val current = currentRows[metric.storageKey]
		if (current == null ||
			current.authorityKind != AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1
		) {
			changed
		} else if (current.authorityRevision == decision.sourceEvidenceRevision &&
			current.authorityDigest == decision.sourceResultDigest &&
			current.authorityState == authorityState
		) {
			changed
		} else {
			database.achievementProgressDao().replaceQualified(
				metricKey = metric.storageKey,
				lastTierIndex = current.lastTierIndex,
				lastValue = current.lastValue,
				updatedAt = observedAtMs,
				lastUnlockedAt = current.lastUnlockedAt,
				authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
				authorityRevision = decision.sourceEvidenceRevision,
				authorityDigest = decision.sourceResultDigest,
				authorityState = authorityState,
				qualifiedNotificationClaimedTierIndex = current
					.qualifiedNotificationClaimedTierIndex,
			)
			true
		}
	}

	private suspend fun replace(
		metric: MetricKey,
		value: Long,
		decision: StepsRetainedMetricsDecision.Snapshot,
		current: AchievementProgressEntity?,
		observedAtMs: Long,
	): Replacement {
		val definitions = AchievementCatalog.byMetric(metric)
		val tier = definitions
			.filter { definition -> value.toDouble() >= definition.threshold }
			.maxOfOrNull { definition -> definition.tierIndex } ?: -1
		if (current != null && current.lastTierIndex == tier && current.lastValue == value.toDouble() &&
			current.authorityKind == AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1 &&
			current.authorityRevision == decision.sourceEvidenceRevision &&
			current.authorityDigest == decision.sourceResultDigest &&
			current.authorityState == AchievementProgressEntity.AUTHORITY_STATE_READY &&
			current.qualifiedNotificationClaimedTierIndex != null
		) {
			return Replacement(changed = false, unlocks = emptyList())
		}
		val qualifiedCurrent = current?.takeIf {
			it.authorityKind == AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1
		}
		val previousClaimedTier = qualifiedCurrent?.qualifiedNotificationClaimedTierIndex
		val unlocks = previousClaimedTier?.takeIf { tier > it }?.let { previousTier ->
			definitions
				.filter { definition ->
					definition.tierIndex > previousTier && definition.tierIndex <= tier
				}
				.map { definition ->
					DomainEvent.AchievementUnlocked(
						timestampMs = EpochMs(observedAtMs),
						processorId = QUALIFIED_STEPS_ACHIEVEMENT_PROCESSOR_ID,
						achievementId = definition.id,
						tier = definition.tier.name,
						authorityRevision = decision.sourceEvidenceRevision,
						authorityDigest = decision.sourceResultDigest,
					)
				}
		}.orEmpty()
		database.achievementProgressDao().replaceQualified(
			metricKey = metric.storageKey,
			lastTierIndex = tier,
			lastValue = value.toDouble(),
			updatedAt = observedAtMs,
			lastUnlockedAt = if (unlocks.isNotEmpty()) {
				observedAtMs
			} else {
				qualifiedCurrent?.lastUnlockedAt?.takeIf { previousClaimedTier != null }
			},
			authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
			authorityRevision = decision.sourceEvidenceRevision,
			authorityDigest = decision.sourceResultDigest,
			authorityState = AchievementProgressEntity.AUTHORITY_STATE_READY,
			qualifiedNotificationClaimedTierIndex = maxOf(previousClaimedTier ?: tier, tier),
		)
		return Replacement(changed = true, unlocks = unlocks)
	}

	private companion object {
		val RETAINED_STEPS_METRICS = listOf(MetricKey.STEPS_TOTAL, MetricKey.BEST_DAILY_STEPS)
	}

	private data class Replacement(
		val changed: Boolean,
		val unlocks: List<DomainEvent.AchievementUnlocked>,
	)
}

internal sealed interface StepsRetainedAchievementReconcileResult {
	data class Applied(val changed: Boolean) : StepsRetainedAchievementReconcileResult
	data class Materializing(val changed: Boolean) : StepsRetainedAchievementReconcileResult
	data class Unverifiable(val changed: Boolean) : StepsRetainedAchievementReconcileResult
	data object RetryableFailure : StepsRetainedAchievementReconcileResult
}
