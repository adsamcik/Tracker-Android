package com.adsamcik.tracker.game.goals.data.implementation

import android.content.Context
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.game.goals.data.abstraction.StepGoal
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import java.time.ZonedDateTime

/**
 * Daily step goal
 */
class DailyStepGoal(persistence: GoalPersistence) : StepGoal(persistence) {
	override val goalReachedKeyRes: Int
		get() = R.string.goals_day_goal_reached_key

	override val period: GoalPeriod
		get() = GoalPeriod.Day

	override val goalPreferenceKeyRes: Int
		get() = R.string.settings_game_goals_day_steps_key
	override val goalPreferenceDefaultRes: Int
		get() = R.string.settings_game_goals_day_steps_default

	override fun updateFromDatabase(context: Context) {
		val today = Time.today
		val tomorrow = Time.tomorrow
		val todaySessions = AppDatabase
				.database(context)
				.sessionDao()
				.getAllBetween(today.toEpochMillis(), tomorrow.toEpochMillis())

		value = todaySessions.sumBy { it.steps }
	}


	override fun getGoalTime(day: ZonedDateTime): Int {
		return day.dayOfYear + day.year * 1000
	}
}
