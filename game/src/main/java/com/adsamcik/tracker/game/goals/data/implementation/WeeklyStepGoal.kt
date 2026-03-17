package com.adsamcik.tracker.game.goals.data.implementation

import android.content.Context
import com.adsamcik.tracker.game.preferences.GamePreferenceKeys
import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.game.goals.data.abstraction.StepGoal
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import java.time.ZonedDateTime
import java.time.temporal.ChronoField
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Weekly step goal
 */
class WeeklyStepGoal(persistence: GoalPersistence) : StepGoal(persistence) {
	override val goalReachedPreferenceKey: String
		get() = GamePreferenceKeys.GOALS_WEEK_REACHED

	override val period: GoalPeriod
		get() = GoalPeriod.Week

	override val goalPreferenceKey: String
		get() = GamePreferenceKeys.GOALS_WEEK_STEPS
	override val goalPreferenceDefault: Int
		get() = GamePreferenceKeys.GOALS_WEEK_STEPS_DEFAULT

	override suspend fun updateFromDatabase(context: Context) {
		val now = Time.now
		val startOfTheWeek = now
			.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1L)
			.with(ChronoField.NANO_OF_DAY, 0L)
		val endOfTheWeek = startOfTheWeek.plusWeeks(1L)
		val weekTrips = AppDatabase
			.database(context)
			.tripDao()
			.getBetween(startOfTheWeek.toEpochMillis(), endOfTheWeek.toEpochMillis())

		value = weekTrips.sumOf { it.steps ?: 0 }
	}


	override fun getGoalTime(day: ZonedDateTime): Int {
		return day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + day.get(IsoFields.WEEK_BASED_YEAR) * 100
	}
}
