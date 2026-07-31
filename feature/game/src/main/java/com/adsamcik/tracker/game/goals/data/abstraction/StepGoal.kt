package com.adsamcik.tracker.game.goals.data.abstraction

import android.content.Context
import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot

abstract class StepGoal(
	persistence: GoalPersistence,
	initialTarget: Int,
) : BaseGoal(persistence) {
	override val pointMultiplier: Double
		get() = 0.01

	private var lastSessionStepValue = 0

	init {
		target = initialTarget.coerceAtLeast(1)
	}

	override fun onSessionUpdatedInternal(session: TrackerSessionSnapshot, isNewSession: Boolean) {
		val diff = sessionStepDelta(session, isNewSession)
		if (diff > 0) value += diff
	}

	protected fun sessionStepDelta(session: TrackerSessionSnapshot, isNewSession: Boolean): Int {
		val previous = lastSessionStepValue
		val diff = if (isNewSession) session.steps else session.steps - previous
		lastSessionStepValue = session.steps
		return diff.coerceAtLeast(0)
	}

	override fun onCumulativeStepsUpdated(totalSteps: Int): Boolean {
		value = totalSteps.coerceAtLeast(0)
		return evaluateGoalReached()
	}

	override fun onTargetUpdated(target: Int): Boolean {
		replaceTarget(target)
		return evaluateGoalReached()
	}

	fun replaceTarget(target: Int) {
		this.target = target.coerceAtLeast(1)
	}

	override suspend fun onEnableInternal(context: Context) = Unit

	override suspend fun onDisableInternal(context: Context) = Unit
}
