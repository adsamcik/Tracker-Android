package com.adsamcik.tracker.game.goals

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Replaces correction-sensitive goal streak progress from the durable qualified DAY ledger. */
@Singleton
internal class StepsGoalAchievementReconciler @Inject constructor(
	private val database: AppDatabase,
	private val dispatchers: DispatchersProvider,
	private val startupGate: TrackingStartupGate,
	private val domainEvents: DomainEventRepository,
) {
	suspend fun reconcile(observedAtMs: Long): StepsGoalAchievementReconcileResult {
		require(observedAtMs >= 0L)
		val expectedGeneration = startupGate.currentGeneration
		val result = try {
			startupGate.withReadyGenerationOperation(expectedGeneration) {
				withContext(dispatchers.io) {
					database.withTransaction {
						val sourceRevision = database.sourceEvidenceStateDao().get()?.revision
							?: return@withTransaction StepsGoalAchievementReconcileResult.RetryableFailure
						val daily = database.stepsGoalEffectDao().getDailyDecisions()
						val pendingRepair = database.stepsGoalRepairDayDao().next() != null
						val digest = daily.authorityDigest()
						if (pendingRepair) {
							return@withTransaction StepsGoalAchievementReconcileResult.Materializing(
								changed = markAuthorityState(
									sourceRevision,
									digest,
									AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING,
									observedAtMs,
								),
							)
						}
						if (daily.isEmpty() || daily.any { effect ->
								effect.decisionState != StepsGoalEffectEntity.STATE_READY_COMPLETE &&
									effect.decisionState != StepsGoalEffectEntity.STATE_READY_INCOMPLETE
							}
						) {
							return@withTransaction StepsGoalAchievementReconcileResult.Unverifiable(
								changed = markAuthorityState(
									sourceRevision,
									digest,
									AchievementProgressEntity.AUTHORITY_STATE_UNVERIFIABLE,
									observedAtMs,
								),
							)
						}

						val completeDays = daily.asSequence()
							.filter { it.decisionState == StepsGoalEffectEntity.STATE_READY_COMPLETE }
							.map(StepsGoalEffectEntity::periodStartEpochDay)
							.distinct()
							.sorted()
							.toList()
						val streak = replace(
							metric = MetricKey.GOAL_STREAK_DAYS,
							value = longestRun(completeDays),
							sourceRevision = sourceRevision,
							digest = digest,
							observedAtMs = observedAtMs,
						)
						val weeks = replace(
							metric = MetricKey.PERFECT_WEEKS,
							value = perfectIsoWeeks(completeDays),
							sourceRevision = sourceRevision,
							digest = digest,
							observedAtMs = observedAtMs,
						)
						domainEvents.persist(streak.unlocks + weeks.unlocks)
						StepsGoalAchievementReconcileResult.Applied(streak.changed || weeks.changed)
					}
				}
			} ?: StepsGoalAchievementReconcileResult.RetryableFailure
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			StepsGoalAchievementReconcileResult.RetryableFailure
		}
		return result
	}

	private suspend fun markAuthorityState(
		sourceRevision: Long,
		digest: String,
		authorityState: String,
		observedAtMs: Long,
	): Boolean = GOAL_STREAK_METRICS.fold(false) { changed, metric ->
		val current = database.achievementProgressDao().getByMetric(metric.storageKey)
		if (current?.authorityKind == AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1 &&
			current.authorityRevision == sourceRevision && current.authorityDigest == digest &&
			current.authorityState == authorityState
		) {
			changed
		} else {
			val qualifiedCurrent = current?.takeIf {
				it.authorityKind == AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1
			}
			database.achievementProgressDao().replaceQualified(
				metricKey = metric.storageKey,
				lastTierIndex = qualifiedCurrent?.lastTierIndex ?: -1,
				lastValue = qualifiedCurrent?.lastValue ?: 0.0,
				updatedAt = observedAtMs,
				lastUnlockedAt = qualifiedCurrent?.lastUnlockedAt,
				authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
				authorityRevision = sourceRevision,
				authorityDigest = digest,
				authorityState = authorityState,
				qualifiedNotificationClaimedTierIndex = qualifiedCurrent
					?.qualifiedNotificationClaimedTierIndex,
			)
			true
		}
	}

	private suspend fun replace(
		metric: MetricKey,
		value: Long,
		sourceRevision: Long,
		digest: String,
		observedAtMs: Long,
	): Replacement {
		val definitions = AchievementCatalog.byMetric(metric)
		val tier = definitions
			.filter { definition -> value.toDouble() >= definition.threshold }
			.maxOfOrNull { definition -> definition.tierIndex } ?: -1
		val current = database.achievementProgressDao().getByMetric(metric.storageKey)
		if (current != null && current.lastTierIndex == tier && current.lastValue == value.toDouble() &&
			current.authorityKind == AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1 &&
			current.authorityRevision == sourceRevision && current.authorityDigest == digest &&
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
						authorityRevision = sourceRevision,
						authorityDigest = digest,
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
			authorityRevision = sourceRevision,
			authorityDigest = digest,
			authorityState = AchievementProgressEntity.AUTHORITY_STATE_READY,
			qualifiedNotificationClaimedTierIndex = maxOf(previousClaimedTier ?: tier, tier),
		)
		return Replacement(changed = true, unlocks = unlocks)
	}

	private fun longestRun(days: List<Long>): Long {
		if (days.isEmpty()) return 0L
		var best = 1L
		var current = 1L
		for (index in 1 until days.size) {
			current = if (days[index - 1] != Long.MAX_VALUE && days[index] == days[index - 1] + 1L) {
				current + 1L
			} else {
				1L
			}
			best = maxOf(best, current)
		}
		return best
	}

	private fun perfectIsoWeeks(days: List<Long>): Long = days
		.map(LocalDate::ofEpochDay)
		.groupBy { date -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
		.count { (monday, completed) ->
			completed.map(LocalDate::toEpochDay).distinct().size == DAYS_PER_WEEK &&
			completed.minOrNull() == monday && completed.maxOrNull() == monday.plusDays(6L)
		}
		.toLong()

	private fun List<StepsGoalEffectEntity>.authorityDigest(): String {
		val bytes = ByteArrayOutputStream()
		DataOutputStream(bytes).use { output ->
			output.writeInt(AUTHORITY_DIGEST_VERSION)
			forEach { effect ->
				output.writeLong(effect.periodStartEpochDay)
				output.writeLong(effect.effectRevision)
				output.writeUTF(effect.decisionState)
				output.writeLong(effect.targetSteps)
				output.writeUTF(effect.sourceAuthorityDigest)
			}
		}
		return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).toHex()
	}

	private fun ByteArray.toHex(): String = buildString(size * 2) {
		for (byte in this@toHex) {
			val value = byte.toInt() and 0xff
			append(HEX[value ushr 4])
			append(HEX[value and 0x0f])
		}
	}

	private companion object {
		const val DAYS_PER_WEEK = 7
		const val AUTHORITY_DIGEST_VERSION = 1
		const val HEX = "0123456789abcdef"
		val GOAL_STREAK_METRICS = listOf(MetricKey.GOAL_STREAK_DAYS, MetricKey.PERFECT_WEEKS)
	}

	private data class Replacement(
		val changed: Boolean,
		val unlocks: List<DomainEvent.AchievementUnlocked>,
	)
}

internal const val QUALIFIED_STEPS_ACHIEVEMENT_PROCESSOR_ID =
	"qualified-steps-achievement-v1"

internal sealed interface StepsGoalAchievementReconcileResult {
	data class Applied(val changed: Boolean) : StepsGoalAchievementReconcileResult
	data class Materializing(val changed: Boolean) : StepsGoalAchievementReconcileResult
	data class Unverifiable(val changed: Boolean) : StepsGoalAchievementReconcileResult
	data object RetryableFailure : StepsGoalAchievementReconcileResult
}
