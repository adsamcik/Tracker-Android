package com.adsamcik.tracker.game.goals.data

import android.content.Context
import com.adsamcik.tracker.shared.base.data.TrackerSession

/**
 * Public interface for goal definitions
 */
interface GoalDefinition {
	/**
	 * Current goal value
	 */
	val value: Double

	/**
	 * Target goal value
	 */
	val target: Double

	/**
	 * Called when goal is enabled.
	 * From now on the goal can expect [onSessionUpdated] calls.
	 */
	fun onEnable(context: Context)

	/**
	 * Called when goal is disabled.
	 * The goal is no longer active and [onSessionUpdated] will no longer be invoked.
	 */
	fun onDisable(context: Context)

	/**
	 * Called when latest session data changes.
	 */
	fun onSessionUpdated(session: TrackerSession, isNewSession: Boolean)
}
