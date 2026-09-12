package com.adsamcik.tracker.game.repository

import com.adsamcik.tracker.game.goals.WeeklyProgressCalculator
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryBatch
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Observes source-qualified Steps only while the product is subscribed.
 *
 * Signal values are deliberately absent from the result: they only say that durable evidence may
 * have changed. They rebind calendar authority; durable source changes are observed directly, so
 * late settlement, correction, and deletion do not depend on a raw summary update or retry timer.
 * Settings only transform the current qualified values and never restart database observation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList")
internal fun sourceQualifiedStepsSummaryFlow(
	repository: StepsNumericSummaryRepository,
	invalidations: Flow<Unit>,
	dailyGoal: Flow<Int>,
	weeklyGoal: Flow<Int>,
	weeklyDailyLimit: Flow<Float>,
	stepsCapturePolicy: Flow<StepsCapturePolicyState>,
	currentDateTime: () -> ZonedDateTime,
	currentLocale: () -> Locale,
): Flow<StepsSummaryData> {
	val periods = invalidations
		.onStart { emit(Unit) }
		.map { stepsCalendarAuthority(currentDateTime(), currentLocale()) }
		.distinctUntilChanged()
		.flatMapLatest { authority -> repository.observePeriods(authority) }
	return combine(
		periods,
		dailyGoal,
		weeklyGoal,
		weeklyDailyLimit,
		stepsCapturePolicy,
	) { values, goalDay, goalWeek, dailyLimit, capturePolicy ->
		StepsSummaryData(
			stepsToday = values.daily.toQualifiedStepCount()
				.withCurrentCapturePolicy(capturePolicy),
			stepsWeek = values.weekly.toQualifiedWeeklyStepCount(
				today = values.authority.today,
				weeklyGoal = goalWeek,
				dailyLimit = dailyLimit,
			),
			goalDay = goalDay,
			goalWeek = goalWeek,
		)
	}
}

/**
 * Current capture policy can refine only a day with no retained Steps evidence. Historical values
 * and all source-local uncertainty remain authoritative, and the current switch never rewrites a
 * weekly period that may contain earlier captured days.
 */
private fun QualifiedStepCount.withCurrentCapturePolicy(
	policy: StepsCapturePolicyState,
): QualifiedStepCount {
	if (this !is QualifiedStepCount.Unavailable ||
		reason != QualifiedStepCountUnavailableReason.NOT_CAPTURED
	) {
		return this
	}
	val qualifiedReason = when (policy) {
		StepsCapturePolicyState.ENABLED -> QualifiedStepCountUnavailableReason.NOT_CAPTURED
		StepsCapturePolicyState.DISABLED -> QualifiedStepCountUnavailableReason.DISABLED
		StepsCapturePolicyState.UNVERIFIABLE ->
			QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE
	}
	return QualifiedStepCount.Unavailable(qualifiedReason)
}

internal enum class StepsCapturePolicyState {
	ENABLED,
	DISABLED,
	UNVERIFIABLE,
}

/** Reuses the validated immutable six-source authority rather than interpreting Room rows here. */
internal fun SourcePolicyAuthorityState.toStepsCapturePolicyState(): StepsCapturePolicyState =
	when (this) {
		is SourcePolicyAuthorityState.Active -> if (
			snapshot[TrackingSourceComponent.STEPS].enabled
		) {
			StepsCapturePolicyState.ENABLED
		} else {
			StepsCapturePolicyState.DISABLED
		}
		is SourcePolicyAuthorityState.Invalid,
		SourcePolicyAuthorityState.Uninitialized,
		-> StepsCapturePolicyState.UNVERIFIABLE
	}

private fun StepsNumericSummaryRepository.observePeriods(
	authority: StepsCalendarAuthority,
): Flow<QualifiedStepsPeriods> {
	val requests = listOf(
		StepsNumericSummaryRequest(
			firstEpochDay = authority.today.toEpochDay(),
			lastEpochDayInclusive = authority.today.toEpochDay(),
			fallbackCalendarZoneId = authority.zoneId,
		),
		StepsNumericSummaryRequest(
			firstEpochDay = authority.startOfWeek.toEpochDay(),
			lastEpochDayInclusive = authority.today.toEpochDay(),
			fallbackCalendarZoneId = authority.zoneId,
		),
	)
	return observeBatch(requests)
		.onStart {
			emit(
				StepsNumericSummaryBatch(
					summaries = List(requests.size) { StepsNumericSummary.Materializing },
				),
			)
		}
		.map { batch ->
			check(batch.summaries.size == requests.size) {
				"Steps period batch did not preserve requested window count"
			}
			QualifiedStepsPeriods(
				authority = authority,
				daily = batch.summaries[DAILY_SUMMARY_INDEX],
				weekly = batch.summaries[WEEKLY_SUMMARY_INDEX],
			)
		}
}

private fun StepsNumericSummary.toQualifiedWeeklyStepCount(
	today: LocalDate,
	weeklyGoal: Int,
	dailyLimit: Float,
): QualifiedStepCount = when (this) {
	is StepsNumericSummary.Ready -> QualifiedStepCount.Ready(
		WeeklyProgressCalculator.cappedTotal(
			dailySteps = days.associate { day ->
				LocalDate.ofEpochDay(day.epochDay) to day.steps.toPresentationInt()
			},
			today = today,
			todayLiveSteps = null,
			weeklyGoal = weeklyGoal,
			dailyLimit = dailyLimit,
		),
	)
	StepsNumericSummary.Materializing,
	is StepsNumericSummary.Unverifiable -> toQualifiedStepCount()
}

private fun Long.toPresentationInt(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal fun stepsCalendarAuthority(
	now: ZonedDateTime,
	locale: Locale,
): StepsCalendarAuthority {
	val today = now.toLocalDate()
	val startOfWeek = today.with(WeekFields.of(locale).dayOfWeek(), 1L)
	return StepsCalendarAuthority(
		today = today,
		startOfWeek = startOfWeek,
		zoneId = now.zone.id,
	)
}

internal data class StepsCalendarAuthority(
	val today: LocalDate,
	val startOfWeek: LocalDate,
	val zoneId: String,
)

private data class QualifiedStepsPeriods(
	val authority: StepsCalendarAuthority,
	val daily: StepsNumericSummary,
	val weekly: StepsNumericSummary,
)

private const val DAILY_SUMMARY_INDEX = 0
private const val WEEKLY_SUMMARY_INDEX = 1
