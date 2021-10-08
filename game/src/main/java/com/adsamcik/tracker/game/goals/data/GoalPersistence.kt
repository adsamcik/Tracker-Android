package com.adsamcik.tracker.game.goals.data

import android.content.Context
import com.adsamcik.tracker.shared.preferences.Preferences

/**
 * Takes care of persisting goal state.
 */
interface GoalPersistence {
	/**
	 * Persists positive value. Negative values are not allowed.
	 */
	fun persist(key: String, value: Int)

	/**
	 * Loads value from persistence.
	 */
	fun load(key: String): Int?
}

class PreferencesGoalPersistence(context: Context) : GoalPersistence {
	private val preferences: Preferences = Preferences.getPref(context)

	override fun persist(key: String, value: Int) {
		require(value >= 0)
		preferences.edit {
			setInt(key, value)
		}
	}

	override fun load(key: String): Int? {
		val persistedValue = preferences.getInt(key, -1)
		return if (persistedValue >= 0) persistedValue else null
	}

}
