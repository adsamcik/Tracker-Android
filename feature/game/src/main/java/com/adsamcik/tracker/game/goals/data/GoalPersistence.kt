package com.adsamcik.tracker.game.goals.data

import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.preferences.GamePreferenceKeys
import kotlinx.coroutines.flow.first

/**
 * Takes care of persisting goal state.
 */
interface GoalPersistence {
	/**
	 * Persists positive value. Negative values are not allowed.
	 */
	suspend fun persist(key: String, value: Int)

	/**
	 * Loads value from persistence.
	 */
	suspend fun load(key: String): Int?
}

class GoalsSettingsGoalPersistence(
	private val repository: GoalsSettingsRepository,
) : GoalPersistence {
	override suspend fun persist(key: String, value: Int) {
		require(value >= 0)
		when (key) {
			GamePreferenceKeys.GOALS_DAY_REACHED ->
				repository.setDailyGoalReachedPeriod(value)
			GamePreferenceKeys.GOALS_WEEK_REACHED ->
				repository.setWeeklyGoalReachedPeriod(value)
			else -> error("Unsupported goal persistence key: $key")
		}
	}

	override suspend fun load(key: String): Int? {
		val settings = repository.data.first()
		return when (key) {
			GamePreferenceKeys.GOALS_DAY_REACHED -> settings.dailyGoalReachedPeriod
			GamePreferenceKeys.GOALS_WEEK_REACHED -> settings.weeklyGoalReachedPeriod
			else -> error("Unsupported goal persistence key: $key")
		}
	}
}
