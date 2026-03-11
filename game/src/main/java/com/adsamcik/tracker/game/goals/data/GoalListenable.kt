package com.adsamcik.tracker.game.goals.data

import android.content.Context
import com.adsamcik.tracker.game.goals.data.abstraction.Goal
import com.adsamcik.tracker.shared.base.data.TrackerSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime

/**
 * Listenable goal data with reactive Flow-based state.
 */
data class GoalListenable(val goal: Goal) {
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
	 * From now on the goal can expect [onSessionUpdated] calls.
	 */
	suspend fun onEnable(context: Context) {
		goal.onEnable(context)
		valueMutable.value = goal.value
		targetMutable.value = goal.target
	}

	/**
	 * Called when goal is disabled.
	 * The goal is no longer active and [onSessionUpdated] will no longer be invoked.
	 */
	suspend fun onDisable(context: Context) {
		goal.onDisable(context)
	}

	private inline fun notifyIfValueChanged(func: () -> Unit) {
		val value = goal.value
		func()
		if (value != goal.value) {
			valueMutable.value = goal.value
		}
	}

	@JvmName("notifyIfValueChangedTyped")
	private inline fun <T> notifyIfValueChanged(func: () -> T): T {
		val value = goal.value
		val returnValue = func()
		if (value != goal.value) {
			valueMutable.value = goal.value
		}
		return returnValue
	}

	/**
	 * Called when latest session data changes.
	 */
	fun onSessionUpdated(session: TrackerSession, isNewSession: Boolean): Boolean {
		return notifyIfValueChanged<Boolean> {
			goal.onSessionUpdated(session, isNewSession)
		}
	}

	/**
	 * Called when cumulative (absolute) step value changes.
	 */
	fun onCumulativeStepsUpdated(totalSteps: Int): Boolean {
		return notifyIfValueChanged<Boolean> {
			goal.onCumulativeStepsUpdated(totalSteps)
		}
	}

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
