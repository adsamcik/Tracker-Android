package com.adsamcik.tracker.game.goals.data.abstraction

import android.content.Context
import java.time.ZonedDateTime

abstract class BaseGoal : Goal {
	abstract val goalPreferenceKey: Int
	abstract val goalPreferenceDefault: Int

	abstract val goalReachedKey: Int
	abstract val notificationMessageRes: Int

	override var isEnabled: Boolean = false
		protected set

	/**
	 * Should be implemented instead of onEnable
	 */
	protected abstract suspend fun onEnableInternal(context: Context)

	/**
	 * Should be implemented instead of onDisable
	 */
	protected abstract suspend fun onDisableInternal(context: Context)

	override suspend fun onEnable(context: Context) {
		onEnableInternal(context)
		updateFromDatabase(context)
		isEnabled = true
	}

	override suspend fun onDisable(context: Context) {
		onDisableInternal(context)
		isEnabled = false
	}

	override fun onNewDay(context: Context, day: ZonedDateTime) {
		if (shouldResetToday(day)) {
			updateFromDatabase(context)
		}
	}

	/**
	 * Called when value should be updated with data from database.
	 */
	protected abstract fun updateFromDatabase(context: Context)

	/**
	 * Returns true if value should be reset today.
	 */
	protected abstract fun shouldResetToday(day: ZonedDateTime): Boolean
}
