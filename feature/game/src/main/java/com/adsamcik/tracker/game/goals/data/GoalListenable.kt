package com.adsamcik.tracker.game.goals.data

import android.content.Context
import com.adsamcik.tracker.game.goals.data.abstraction.Goal
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime

/**
 * Listenable goal data with reactive Flow-based state.
 */
data class GoalListenable(val goal: Goal) {
	private val lock = Any()
	private val valueMutable: MutableStateFlow<Int> = MutableStateFlow(0)
	val value: StateFlow<Int> get() = valueMutable.asStateFlow()

	private val targetMutable: MutableStateFlow<Int> = MutableStateFlow(0)
	val target: StateFlow<Int> get() = targetMutable.asStateFlow()

	init {
		goal.onTargetChanged = {
			targetMutable.value = it
		}

		goal.onValueChanged = {
			valueMutable.value = it
		}
	}

	/**
	 * Called when goal is enabled.
	 * From now on the goal can expect [onSessionPresentationUpdated] calls.
	 */
	suspend fun onEnable(context: Context) {
		goal.onEnable(context)
		valueMutable.value = goal.value
		targetMutable.value = goal.target
	}

	/**
	 * Called when goal is disabled.
	 * The goal is no longer active and [onSessionPresentationUpdated] will no longer be invoked.
	 */
	suspend fun onDisable(context: Context) {
		goal.onDisable(context)
	}

	private inline fun notifyIfValueChanged(func: () -> Unit) {
		synchronized(lock) {
			val value = goal.value
			func()
			if (value != goal.value) {
				valueMutable.value = goal.value
			}
		}
	}

	/** Updates the displayed value while deliberately withholding completion evaluation. */
	fun onSessionPresentationUpdated(session: TrackerSessionSnapshot, isNewSession: Boolean) {
		notifyIfValueChanged {
			goal.onSessionPresentationUpdated(session, isNewSession)
		}
	}

	/** Updates the displayed cumulative value without making an award decision. */
	fun onCumulativeStepsPresentationUpdated(totalSteps: Int) {
		notifyIfValueChanged {
			goal.onCumulativeStepsPresentationUpdated(totalSteps)
		}
	}

	/** Replaces the displayed target without evaluating unqualified progress for an award. */
	fun onTargetPresentationUpdated(target: Int) = goal.onTargetPresentationUpdated(target)

	/**
	 * Called on a new day. Roughly sometime after midnight based on scheduling.
	 */
	suspend fun onNewDay(context: Context, day: ZonedDateTime) {
		val valueBefore = goal.value
		goal.onNewDay(context, day)
		if (valueBefore != goal.value) {
			valueMutable.value = goal.value
		}
	}
}
