package com.adsamcik.tracker.game.repository

import com.adsamcik.tracker.game.goals.WeeklyProgressCalculator
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest

/**
 * Reads source-qualified Steps after concrete product invalidations.
 *
 * Signal values are deliberately absent from the result: they only say that durable evidence may
 * have changed. Every number comes from [StepsNumericSummaryRepository].
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
	materializingRetryDelaysMs: List<Long> = MATERIALIZING_RETRY_DELAYS_MS,
): Flow<StepsSummaryData> {
	require(materializingRetryDelaysMs.all { delayMs -> delayMs >= 0L }) {
		"Materializing retry delays cannot be negative"
	}
	return combine(
		invalidations,
		dailyGoal,
		weeklyGoal,
		weeklyDailyLimit,
	) { _, goalDay, goalWeek, dailyLimit ->
		StepsSummaryReadInput(
			goalDay = goalDay,
			goalWeek = goalWeek,
			weeklyDailyLimit = dailyLimit,
		)
	}.transformLatest { input ->
		var attempt = 0
		while (true) {
			val authority = stepsCalendarAuthority(currentDateTime(), currentLocale())
			val summary = repository.readSummary(authority, input)
			emit(summary)
			if (!summary.isMaterializing || attempt >= materializingRetryDelaysMs.size) {
				return@transformLatest
			}
			delay(materializingRetryDelaysMs[attempt])
			attempt += 1
		}
	}
}

private suspend fun StepsNumericSummaryRepository.readSummary(
	authority: StepsCalendarAuthority,
	input: StepsSummaryReadInput,
): StepsSummaryData {
	val daily = read(
		StepsNumericSummaryRequest(
			firstEpochDay = authority.today.toEpochDay(),
			lastEpochDayInclusive = authority.today.toEpochDay(),
			fallbackCalendarZoneId = authority.zoneId,
		),
	)
	val weekly = read(
		StepsNumericSummaryRequest(
			firstEpochDay = authority.startOfWeek.toEpochDay(),
			lastEpochDayInclusive = authority.today.toEpochDay(),
			fallbackCalendarZoneId = authority.zoneId,
		),
	)
	return StepsSummaryData(
		stepsToday = daily.toQualifiedStepCount(),
		stepsWeek = weekly.toQualifiedWeeklyStepCount(
			today = authority.today,
			weeklyGoal = input.goalWeek,
			dailyLimit = input.weeklyDailyLimit,
		),
		goalDay = input.goalDay,
		goalWeek = input.goalWeek,
	)
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

private val StepsSummaryData.isMaterializing: Boolean
	get() = stepsToday.isMaterializing || stepsWeek.isMaterializing

private val QualifiedStepCount.isMaterializing: Boolean
	get() = this is QualifiedStepCount.Unavailable &&
		reason == QualifiedStepCountUnavailableReason.MATERIALIZING

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

private data class StepsSummaryReadInput(
	val goalDay: Int,
	val goalWeek: Int,
	val weeklyDailyLimit: Float,
)

private val MATERIALIZING_RETRY_DELAYS_MS = listOf(250L, 500L, 1_000L)
