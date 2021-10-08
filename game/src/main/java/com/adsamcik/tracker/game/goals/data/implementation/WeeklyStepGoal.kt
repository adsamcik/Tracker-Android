package com.adsamcik.tracker.game.goals.data.implementation

import android.content.Context
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.game.goals.data.abstraction.StepGoal
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import java.time.ZonedDateTime
import java.time.temporal.ChronoField
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields
import java.util.*

/**
 * Weekly step goal
 */
class WeeklyStepGoal(persistence: GoalPersistence) : StepGoal(persistence) {
	override val goalReachedKeyRes: Int
		get() = R.string.goals_week_goal_reached_key

	override val period: GoalPeriod
		get() = GoalPeriod.Week

	override val goalPreferenceKeyRes: Int
		get() = R.string.settings_game_goals_week_steps_key
	override val goalPreferenceDefaultRes: Int
		get() = R.string.settings_game_goals_week_steps_default

	override fun updateFromDatabase(context: Context) {
		val now = Time.now
		val startOfTheWeek = now
			.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1L)
			.with(ChronoField.NANO_OF_DAY, 0L)
		val endOfTheWeek = startOfTheWeek.plusWeeks(1L)
		val lastWeekSessions = AppDatabase
			.database(context)
			.sessionDao()
			.getAllBetween(startOfTheWeek.toEpochMillis(), endOfTheWeek.toEpochMillis())

		value = lastWeekSessions.sumOf { it.steps }
	}


	override fun getGoalTime(day: ZonedDateTime): Int {
		return day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + day.get(IsoFields.WEEK_BASED_YEAR) * 100
	}
}
