package com.adsamcik.tracker.game.goals.data.abstraction

import android.app.Notification
import android.content.Context
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
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
	 * From now on the goal can expect [onSessionPresentationUpdated] calls.
	 */
	suspend fun onEnable(context: Context)

	/**
	 * Called when goal is disabled.
	 * The goal is no longer active and [onSessionPresentationUpdated] will no longer be invoked.
	 */
	suspend fun onDisable(context: Context)

	/**
	 * Updates legacy presentation state without evaluating completion.
	 *
	 * [TrackerSessionSnapshot.steps] is not source-qualified, so callers handling that snapshot must
	 * use this entry point and cannot make points, XP, notification, or reported-period decisions.
	 */
	fun onSessionPresentationUpdated(session: TrackerSessionSnapshot, isNewSession: Boolean)

	/** Updates legacy presentation state from an unqualified cumulative value without completing. */
	fun onCumulativeStepsPresentationUpdated(totalSteps: Int) = Unit

	/** Replaces a target without evaluating it against unqualified presentation state. */
	fun onTargetPresentationUpdated(target: Int) = Unit

	/**
	 * Called on a new day. Roughly sometime after midnight based on scheduling.
	 */
	suspend fun onNewDay(context: Context, day: ZonedDateTime)

	/**
	 * Builds notification for the goal.
	 */
	fun buildNotification(context: Context): Notification
}
