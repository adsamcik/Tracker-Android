package com.adsamcik.tracker.game.goals.data.implementation

import android.content.Context
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.goals.data.abstraction.StepGoal
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import java.time.ZonedDateTime

/**
 * Daily step goal
 */
class DailyStepGoal : StepGoal() {
	override val goalReachedKey: Int
		get() = R.string.goals_day_goal_reached_key
	override val notificationMessageRes: Int
		get() = R.string.goals_day_goal_reached_notification

	override val goalPreferenceKey: Int
		get() = R.string.settings_game_goals_day_steps_key
	override val goalPreferenceDefault: Int
		get() = R.string.settings_game_goals_day_steps_default

	override fun updateFromDatabase(context: Context) {
		val today = Time.today
		val tomorrow = Time.tomorrow
		val todaySessions = AppDatabase
				.database(context)
				.sessionDao()
				.getAllBetween(today.toEpochMillis(), tomorrow.toEpochMillis())

		stepCount = todaySessions.sumBy { it.steps }
	}


	override fun shouldResetToday(day: ZonedDateTime): Boolean = true
}
