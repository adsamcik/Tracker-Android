package com.adsamcik.tracker.game.goals

import androidx.room.withTransaction
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.game.progression.XpCalculator
import com.adsamcik.tracker.game.repository.StepsCalendarAuthority
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.StepsGoalEffectWriteResult
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarAuthority
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionBatch
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionWindow
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Records daily and week-to-date source-qualified Steps goal decisions without projecting rewards.
 *
 * The source snapshot is read first, then its revision is checked inside the deletion-linearized
 * Room writer transaction. A changed source or closed startup generation leaves no effect write.
 * Materializing windows retain the previous durable decision until the canonical writer settles.
 */
@Singleton
internal class StepsGoalDecisionReconciler @Inject constructor(
	private val database: AppDatabase,
	private val source: StepsNumericDecisionRepository,
	private val dispatchers: DispatchersProvider,
	private val startupGate: TrackingStartupGate,
) {
	suspend fun reconcile(
		authority: StepsCalendarAuthority,
		settings: GoalsSettingsState,
		observedAtMs: Long,
	): StepsGoalDecisionReconcileResult {
		require(observedAtMs >= 0L)
		require(settings.dailyStepGoal > 0 && settings.weeklyStepGoal > 0)
		require(
			settings.weeklyProgressDailyLimit.isFinite() &&
				settings.weeklyProgressDailyLimit > 0f &&
				settings.weeklyProgressDailyLimit <= 1f,
		)
		val requests = decisionRequests(authority)
		val sourceRead = try {
			source.readDecisionBatch(requests)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return retryable(StepsGoalDecisionRetryableReason.STORAGE_UNAVAILABLE)
		}
		val snapshot = when (sourceRead) {
			is StepsNumericDecisionBatch.Snapshot -> sourceRead
			StepsNumericDecisionBatch.StorageUnavailable -> return retryable(
				StepsGoalDecisionRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		if (snapshot.windows.map(StepsNumericDecisionWindow::request) != requests ||
			snapshot.windows.any { window ->
				(window.summary as? StepsNumericSummary.Unverifiable)?.reason ==
					StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE
			}
		) {
			return retryable(StepsGoalDecisionRetryableReason.STORAGE_UNAVAILABLE)
		}
		val expectedGeneration = startupGate.currentGeneration
		return try {
			startupGate.withReadyGenerationOperation(expectedGeneration) {
				withContext(dispatchers.io) {
					database.withTransaction {
						if (database.sourceEvidenceStateDao().get()?.revision !=
							snapshot.sourceEvidenceRevision
						) {
							return@withTransaction retryable(
								StepsGoalDecisionRetryableReason.SOURCE_CHANGED,
							)
						}
						val daily = recordDaily(
							window = snapshot.windows[DAILY_WINDOW_INDEX],
							authority = authority,
							settings = settings,
							sourceEvidenceRevision = snapshot.sourceEvidenceRevision,
							observedAtMs = observedAtMs,
						)
						val weekly = recordWeekly(
							window = snapshot.windows[WEEKLY_WINDOW_INDEX],
							authority = authority,
							settings = settings,
							sourceEvidenceRevision = snapshot.sourceEvidenceRevision,
							observedAtMs = observedAtMs,
						)
						if (daily.invalidatesDailyAchievementAuthority()) {
							database.achievementProgressDao().markQualifiedStepsMaterializing(
								sourceEvidenceRevision = snapshot.sourceEvidenceRevision,
								updatedAtMs = observedAtMs,
							)
						}
						StepsGoalDecisionReconcileResult.Applied(daily, weekly)
					}
				}
			} ?: retryable(StepsGoalDecisionRetryableReason.STARTUP_GENERATION_CHANGED)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			retryable(StepsGoalDecisionRetryableReason.STORAGE_UNAVAILABLE)
		}
	}

	private fun StepsGoalPeriodDecisionWriteResult.invalidatesDailyAchievementAuthority(): Boolean =
		this == StepsGoalPeriodDecisionWriteResult.INSERTED ||
			this == StepsGoalPeriodDecisionWriteResult.REVISED ||
			this == StepsGoalPeriodDecisionWriteResult.DEFERRED_MATERIALIZING

	private suspend fun recordDaily(
		window: StepsNumericDecisionWindow,
		authority: StepsCalendarAuthority,
		settings: GoalsSettingsState,
		sourceEvidenceRevision: Long,
		observedAtMs: Long,
	): StepsGoalPeriodDecisionWriteResult = record(
		window = window,
		periodKind = StepsGoalEffectEntity.PERIOD_DAY,
		periodStartEpochDay = authority.today.toEpochDay(),
		periodEndEpochDay = authority.today.toEpochDay(),
		qualifiedThroughEpochDay = authority.today.toEpochDay(),
		targetSteps = settings.dailyStepGoal,
		weeklyDailyLimitBits = null,
		qualifiedSteps = (window.summary as? StepsNumericSummary.Ready)?.totalSteps,
		desiredXp = XpCalculator.goalXp(),
		sourceEvidenceRevision = sourceEvidenceRevision,
		observedAtMs = observedAtMs,
	)

	private suspend fun recordWeekly(
		window: StepsNumericDecisionWindow,
		authority: StepsCalendarAuthority,
		settings: GoalsSettingsState,
		sourceEvidenceRevision: Long,
		observedAtMs: Long,
	): StepsGoalPeriodDecisionWriteResult {
		val ready = window.summary as? StepsNumericSummary.Ready
		val qualifiedSteps = ready?.let { summary ->
			WeeklyProgressCalculator.cappedTotal(
				dailySteps = summary.days.associate { day ->
					LocalDate.ofEpochDay(day.epochDay) to day.steps.toPresentationInt()
				},
				today = authority.today,
				todayLiveSteps = null,
				weeklyGoal = settings.weeklyStepGoal,
				dailyLimit = settings.weeklyProgressDailyLimit,
			).toLong()
		}
		return record(
			window = window,
			periodKind = StepsGoalEffectEntity.PERIOD_WEEK,
			periodStartEpochDay = authority.startOfWeek.toEpochDay(),
			periodEndEpochDay = Math.addExact(authority.startOfWeek.toEpochDay(), WEEK_END_OFFSET),
			qualifiedThroughEpochDay = authority.today.toEpochDay(),
			targetSteps = settings.weeklyStepGoal,
			weeklyDailyLimitBits = settings.weeklyProgressDailyLimit.toBits(),
			qualifiedSteps = qualifiedSteps,
			desiredXp = NO_WEEKLY_XP,
			sourceEvidenceRevision = sourceEvidenceRevision,
			observedAtMs = observedAtMs,
		)
	}

	@Suppress("LongParameterList")
	private suspend fun record(
		window: StepsNumericDecisionWindow,
		periodKind: String,
		periodStartEpochDay: Long,
		periodEndEpochDay: Long,
		qualifiedThroughEpochDay: Long,
		targetSteps: Int,
		weeklyDailyLimitBits: Int?,
		qualifiedSteps: Long?,
		desiredXp: Int,
		sourceEvidenceRevision: Long,
		observedAtMs: Long,
	): StepsGoalPeriodDecisionWriteResult {
		if (window.summary == StepsNumericSummary.Materializing) {
			return StepsGoalPeriodDecisionWriteResult.DEFERRED_MATERIALIZING
		}
		val complete = qualifiedSteps != null && qualifiedSteps >= targetSteps.toLong()
		val decisionState = when {
			window.summary is StepsNumericSummary.Unverifiable ->
				StepsGoalEffectEntity.STATE_UNVERIFIABLE
			complete -> StepsGoalEffectEntity.STATE_READY_COMPLETE
			else -> StepsGoalEffectEntity.STATE_READY_INCOMPLETE
		}
		val candidate = StepsGoalEffectEntity(
			effectIdentity = StepsGoalEffectEntity.identity(periodKind, periodStartEpochDay),
			periodKind = periodKind,
			periodStartEpochDay = periodStartEpochDay,
			periodEndEpochDay = periodEndEpochDay,
			qualifiedThroughEpochDay = qualifiedThroughEpochDay,
			calendarAuthority = when (val calendar = window.calendarAuthority) {
				is StepsNumericCalendarAuthority.Exact -> calendar.canonical
				StepsNumericCalendarAuthority.Unavailable ->
					StepsGoalEffectEntity.CALENDAR_AUTHORITY_UNAVAILABLE
			},
			targetSteps = targetSteps.toLong(),
			weeklyDailyLimitBits = weeklyDailyLimitBits,
			decisionState = decisionState,
			unavailableReason = (window.summary as? StepsNumericSummary.Unverifiable)?.reason?.name,
			qualifiedSteps = qualifiedSteps,
			sourceAuthorityDigest = window.sourceResultDigest,
			sourceEvidenceRevision = sourceEvidenceRevision,
			effectRevision = 1L,
			completionPointsMicros = Math.multiplyExact(
				targetSteps.toLong(),
				POINTS_MICROS_PER_TARGET_STEP,
			),
			completionXp = desiredXp,
			desiredPointsMicros = if (complete) {
				Math.multiplyExact(targetSteps.toLong(), POINTS_MICROS_PER_TARGET_STEP)
			} else 0L,
			desiredXp = if (complete) desiredXp else 0,
			firstCompletedAtMs = observedAtMs.takeIf { complete },
			pointsAppliedRevision = 0L,
			xpAppliedRevision = 0L,
			notificationClaimedRevision = null,
			notificationClaimedAtMs = null,
			updatedAtMs = observedAtMs,
		)
		return database.stepsGoalEffectDao().recordDecision(candidate).toPeriodResult()
	}

	private fun decisionRequests(authority: StepsCalendarAuthority): List<StepsNumericSummaryRequest> {
		val today = authority.today.toEpochDay()
		val weekStart = authority.startOfWeek.toEpochDay()
		require(today in weekStart..Math.addExact(weekStart, WEEK_END_OFFSET))
		return listOf(
			StepsNumericSummaryRequest(today, today, authority.zoneId),
			StepsNumericSummaryRequest(weekStart, today, authority.zoneId),
		)
	}

	private fun StepsGoalEffectWriteResult.toPeriodResult(): StepsGoalPeriodDecisionWriteResult =
		when (this) {
			StepsGoalEffectWriteResult.INSERTED -> StepsGoalPeriodDecisionWriteResult.INSERTED
			StepsGoalEffectWriteResult.UNCHANGED -> StepsGoalPeriodDecisionWriteResult.UNCHANGED
			StepsGoalEffectWriteResult.REVISED -> StepsGoalPeriodDecisionWriteResult.REVISED
			StepsGoalEffectWriteResult.STALE -> StepsGoalPeriodDecisionWriteResult.SUPERSEDED
		}

	private fun retryable(reason: StepsGoalDecisionRetryableReason) =
		StepsGoalDecisionReconcileResult.RetryableFailure(reason)

	private fun Long.toPresentationInt(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

	private companion object {
		const val DAILY_WINDOW_INDEX = 0
		const val WEEKLY_WINDOW_INDEX = 1
		const val WEEK_END_OFFSET = 6L
		const val POINTS_MICROS_PER_TARGET_STEP = 10_000L
		const val NO_WEEKLY_XP = 0
	}
}

internal sealed interface StepsGoalDecisionReconcileResult {
	data class Applied(
		val daily: StepsGoalPeriodDecisionWriteResult,
		val weekly: StepsGoalPeriodDecisionWriteResult,
	) : StepsGoalDecisionReconcileResult

	data class RetryableFailure(
		val reason: StepsGoalDecisionRetryableReason,
	) : StepsGoalDecisionReconcileResult
}

internal enum class StepsGoalPeriodDecisionWriteResult {
	INSERTED,
	UNCHANGED,
	REVISED,
	SUPERSEDED,
	DEFERRED_MATERIALIZING,
}

internal enum class StepsGoalDecisionRetryableReason {
	STORAGE_UNAVAILABLE,
	SOURCE_CHANGED,
	STARTUP_GENERATION_CHANGED,
}
