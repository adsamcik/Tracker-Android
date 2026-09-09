package com.adsamcik.tracker.game.repository

import com.adsamcik.tracker.game.goals.WeeklyProgressCalculator
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
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
	) { values, goalDay, goalWeek, dailyLimit ->
		StepsSummaryData(
			stepsToday = values.daily.toQualifiedStepCount(),
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

private fun StepsNumericSummaryRepository.observePeriods(
	authority: StepsCalendarAuthority,
): Flow<QualifiedStepsPeriods> = combine(
	observe(
		StepsNumericSummaryRequest(
			firstEpochDay = authority.today.toEpochDay(),
			lastEpochDayInclusive = authority.today.toEpochDay(),
			fallbackCalendarZoneId = authority.zoneId,
		),
	).onStart { emit(StepsNumericSummary.Materializing) },
	observe(
		StepsNumericSummaryRequest(
			firstEpochDay = authority.startOfWeek.toEpochDay(),
			lastEpochDayInclusive = authority.today.toEpochDay(),
			fallbackCalendarZoneId = authority.zoneId,
		),
	).onStart { emit(StepsNumericSummary.Materializing) },
) { daily, weekly ->
	QualifiedStepsPeriods(authority, daily, weekly)
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
