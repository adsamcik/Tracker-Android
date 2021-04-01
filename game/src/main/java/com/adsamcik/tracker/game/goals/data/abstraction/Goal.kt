package com.adsamcik.tracker.game.goals.data.abstraction

import android.content.Context
import com.adsamcik.tracker.shared.base.data.TrackerSession
import java.time.ZonedDateTime

/**
 * Public interface for goal definitions
 */
interface Goal {
	/**
	 * Current goal value
	 */
	val value: Double

	/**
	 * Target goal value
	 */
	val target: Double

	/**
	 * True if goal is enabled
	 */
	val isEnabled: Boolean

	/**
	 * Called when goal is enabled.
	 * From now on the goal can expect [onSessionUpdated] calls.
	 */
	suspend fun onEnable(context: Context)

	/**
	 * Called when goal is disabled.
	 * The goal is no longer active and [onSessionUpdated] will no longer be invoked.
	 */
	suspend fun onDisable(context: Context)

	/**
	 * Called when latest session data changes.
	 */
	fun onSessionUpdated(session: TrackerSession, isNewSession: Boolean)

	/**
	 * Called on a new day. Roughly sometime after midnight based on scheduling.
	 */
	fun onNewDay(context: Context, day: ZonedDateTime)
}
