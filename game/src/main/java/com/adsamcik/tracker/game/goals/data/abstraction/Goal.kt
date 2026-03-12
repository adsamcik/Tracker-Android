package com.adsamcik.tracker.game.goals.data.abstraction

import android.app.Notification
import android.content.Context
import com.adsamcik.tracker.shared.base.data.TrackerSession
import java.time.ZonedDateTime

/**
 * Public interface for goal definitions.
 *
 * Interface exposes immediate callbacks instead of owning Flow/StateFlow state.
 * Implementations can bridge into reactive streams at higher layers when needed.
 */
interface Goal {
	/**
	 * Current goal value
	 */
	val value: Int

	/**
	 * Target goal value
	 */
	val target: Int

	/**
	 * Point multiplier.
	 * Used for calculating amount of points awarded.
	 */
	val pointMultiplier: Double

	/**
	 * On value changed listener
	 */
	var onValueChanged: (value: Int) -> Unit

	/**
	 * On target changed listener
	 */
	var onTargetChanged: (value: Int) -> Unit

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
	 * Returns true if goal is reached.
	 */
	fun onSessionUpdated(session: TrackerSession, isNewSession: Boolean): Boolean

	/**
	 * Called when a cumulative (absolute) step value is available.
	 * Returns true if goal is reached.
	 */
	fun onCumulativeStepsUpdated(totalSteps: Int): Boolean = false

	/**
	 * Called on a new day. Roughly sometime after midnight based on scheduling.
	 */
	suspend fun onNewDay(context: Context, day: ZonedDateTime)

	/**
	 * Builds notification for the goal.
	 */
	fun buildNotification(context: Context): Notification
}
