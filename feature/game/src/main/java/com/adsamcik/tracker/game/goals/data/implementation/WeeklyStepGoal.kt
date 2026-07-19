package com.adsamcik.tracker.game.goals.data.implementation

import android.content.Context
import com.adsamcik.tracker.game.goals.WeeklyProgressCalculator
import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.game.goals.data.abstraction.StepGoal
import com.adsamcik.tracker.game.preferences.GamePreferenceKeys
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoField
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields
import java.util.Locale

class WeeklyStepGoal(
	persistence: GoalPersistence,
	initialTarget: Int,
	initialDailyLimit: Float,
) : StepGoal(persistence, initialTarget) {
	override val goalReachedPreferenceKey: String
		get() = GamePreferenceKeys.GOALS_WEEK_REACHED

	override val period: GoalPeriod
		get() = GoalPeriod.Week

	private var dailyLimit = initialDailyLimit
	private var currentDate: LocalDate = Time.now.toLocalDate()
	private var dailyRawSteps: Map<LocalDate, Int> = emptyMap()

	override fun onSessionUpdatedInternal(session: TrackerSession, isNewSession: Boolean) {
		rollDateForwardIfNeeded()
		val updated = dailyRawSteps.toMutableMap()
		updated[currentDate] = (
			updated[currentDate].orZero().toLong() +
				sessionStepDelta(session, isNewSession)
			)
			.coerceAtMost(Int.MAX_VALUE.toLong())
			.toInt()
		dailyRawSteps = updated
		updateLiveValue()
	}

	override fun onCumulativeStepsUpdated(totalSteps: Int): Boolean {
		rollDateForwardIfNeeded()
		dailyRawSteps = dailyRawSteps.toMutableMap().apply {
			this[currentDate] = totalSteps.coerceAtLeast(0)
		}
		updateLiveValue()
		return evaluateGoalReached()
	}

	fun updateConfiguration(
		target: Int,
		dailyLimit: Float,
		evaluateCompletion: Boolean = true,
	): Boolean {
		this.target = target.coerceAtLeast(1)
		this.dailyLimit = dailyLimit
		updateLiveValue()
		return evaluateCompletion && evaluateGoalReached()
	}

	override suspend fun updateFromDatabase(context: Context) {
		loadCurrentWeek(context)
	}

	private suspend fun loadCurrentWeek(context: Context) {
		val now = Time.now
		val zone = now.zone
		val startOfWeek = now
			.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1L)
			.with(ChronoField.NANO_OF_DAY, 0L)
		val endOfWeek = startOfWeek.plusWeeks(1L)
		currentDate = now.toLocalDate()
		dailyRawSteps = AppDatabase.database(context)
			.tripDao()
			.getBetween(startOfWeek.toEpochMillis(), endOfWeek.toEpochMillis())
			.stepsByLocalDate(zone)
		updateLiveValue()
	}

	private fun updateLiveValue() {
		value = WeeklyProgressCalculator.cappedTotal(
			dailySteps = dailyRawSteps,
			today = currentDate,
			todayLiveSteps = null,
			weeklyGoal = target,
			dailyLimit = dailyLimit,
		)
	}

	private fun rollDateForwardIfNeeded() {
		val today = Time.now.toLocalDate()
		if (today == currentDate) return
		if (
			today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) !=
			currentDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) ||
			today.get(IsoFields.WEEK_BASED_YEAR) != currentDate.get(IsoFields.WEEK_BASED_YEAR)
		) {
			dailyRawSteps = emptyMap()
		}
		currentDate = today
	}

	override fun getGoalTime(day: ZonedDateTime): Int =
		day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) +
			day.get(IsoFields.WEEK_BASED_YEAR) * 100
}

private fun List<Trip>.stepsByLocalDate(zone: ZoneId): Map<LocalDate, Int> =
	groupingBy {
		Instant.ofEpochMilli(it.startTimeMs).atZone(zone).toLocalDate()
	}.fold(0) { total, trip ->
		(total.toLong() + (trip.steps ?: 0).coerceAtLeast(0))
			.coerceAtMost(Int.MAX_VALUE.toLong())
			.toInt()
	}

private fun Int?.orZero(): Int = this ?: 0
