package com.adsamcik.tracker.game.goals.data.implementation

import android.content.Context
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.goals.data.abstraction.StepGoal
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import java.time.ZonedDateTime
import java.time.temporal.ChronoField
import java.time.temporal.WeekFields
import java.util.*

/**
 * Weekly step goal
 */
class WeeklyStepGoal : StepGoal() {
	override val goalReachedKey: Int
		get() = R.string.goals_week_goal_reached_key
	override val notificationMessageRes: Int
		get() = R.string.goals_week_goal_reached_notification

	override val goalPreferenceKey: Int
		get() = R.string.settings_game_goals_week_steps_key
	override val goalPreferenceDefault: Int
		get() = R.string.settings_game_goals_week_steps_default

	override fun updateFromDatabase(context: Context) {
		val now = Time.now
		val startOfTheWeek = now.with {
			it.with(ChronoField.MILLI_OF_DAY, 0L)
			it.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1L)
		}
		val endOfTheWeek = startOfTheWeek.plusWeeks(1L)
		val lastWeekSessions = AppDatabase
				.database(context)
				.sessionDao()
				.getAllBetween(startOfTheWeek.toEpochMillis(), endOfTheWeek.toEpochMillis())

		stepCount = lastWeekSessions.sumBy { it.steps }
	}


	override fun shouldResetToday(day: ZonedDateTime): Boolean = true
}
