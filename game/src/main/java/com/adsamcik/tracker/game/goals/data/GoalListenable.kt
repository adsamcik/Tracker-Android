package com.adsamcik.tracker.game.goals.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.adsamcik.tracker.game.goals.data.abstraction.Goal
import com.adsamcik.tracker.shared.base.data.TrackerSession
import java.time.ZonedDateTime

/**
 * Listenable goal data
 */
data class GoalListenable(val goal: Goal) {
	val value: LiveData<Int> get() = valueMutable
	private val valueMutable: MutableLiveData<Int> = MutableLiveData()

	val target: LiveData<Int> get() = targetMutable
	private val targetMutable: MutableLiveData<Int> = MutableLiveData()

	init {
		goal.onTargetChanged = {
			targetMutable.postValue(it)
		}

		goal.onValueChanged = {
			valueMutable.postValue(it)
		}
	}

	/**
	 * Called when goal is enabled.
	 * From now on the goal can expect [onSessionUpdated] calls.
	 */
	suspend fun onEnable(context: Context) {
		goal.onEnable(context)
		valueMutable.postValue(goal.value)
		targetMutable.postValue(goal.target)
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
			valueMutable.postValue(value)
		}
	}

	@JvmName("notifyIfValueChangedTyped")
	private inline fun <T> notifyIfValueChanged(func: () -> T): T {
		val value = goal.value
		val returnValue = func()
		if (value != goal.value) {
			valueMutable.postValue(value)
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
	 * Called on a new day. Roughly sometime after midnight based on scheduling.
	 */
	fun onNewDay(context: Context, day: ZonedDateTime) {
		notifyIfValueChanged {
			goal.onNewDay(context, day)
		}
	}
}
