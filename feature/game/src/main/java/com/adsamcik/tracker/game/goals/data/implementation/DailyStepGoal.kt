package com.adsamcik.tracker.game.goals.data.implementation

import android.content.Context
import com.adsamcik.tracker.game.preferences.GamePreferenceKeys
import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.game.goals.data.abstraction.StepGoal
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import java.time.ZonedDateTime

/**
 * Daily step goal
 */
class DailyStepGoal(
	persistence: GoalPersistence,
	initialTarget: Int,
) : StepGoal(persistence, initialTarget) {
	override val goalReachedPreferenceKey: String
		get() = GamePreferenceKeys.GOALS_DAY_REACHED

	override val period: GoalPeriod
		get() = GoalPeriod.Day

	override suspend fun updateFromDatabase(context: Context) {
		// Legacy Trip.steps seeds presentation invalidation only; GoalTracker never evaluates it for
		// points, XP, notifications, or reported-period persistence.
		val today = Time.today
		val tomorrow = Time.tomorrow
		val todayTrips = AppDatabase
			.database(context)
			.tripDao()
			.getBetween(today.toEpochMillis(), tomorrow.toEpochMillis())

		value = todayTrips.sumOf { it.steps ?: 0 }
	}


	override fun getGoalTime(day: ZonedDateTime): Int {
		return day.dayOfYear + day.year * 1000
	}
}
