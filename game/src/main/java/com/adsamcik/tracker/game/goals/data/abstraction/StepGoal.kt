package com.adsamcik.tracker.game.goals.data.abstraction

import android.content.Context
import androidx.annotation.CallSuper
import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

abstract class StepGoal(persistence: GoalPersistence) : BaseGoal(persistence) {
	override val pointMultiplier: Double
		get() = 0.01

	private var lastStepValue = 0
	private var goalPreferenceJob: Job? = null

	override fun onSessionUpdatedInternal(session: TrackerSession, isNewSession: Boolean) {
		val diff = if (isNewSession) session.steps else session.steps - lastStepValue

		lastStepValue = session.steps

		if (diff > 0) {
			value += diff
		} else if (diff < 0) {
			Reporter.report(
					"Step difference is negative. This should never happen session steps:" +
							" ${session.steps} last steps: $lastStepValue"
			)
		}
	}

	override fun onCumulativeStepsUpdated(totalSteps: Int): Boolean {
		val normalizedTotal = totalSteps.coerceAtLeast(0)
		lastStepValue = normalizedTotal
		value = normalizedTotal
		return evaluateGoalReached()
	}

	@CallSuper
	override suspend fun onEnableInternal(context: Context) {
		// Initial sync read for immediate availability; Flow subscription follows for updates
		@Suppress("DEPRECATION")
		target = Preferences.getPref(context).getIntResString(
			goalPreferenceKeyRes,
			goalPreferenceDefaultRes
		)
		goalPreferenceJob?.cancel()
		goalPreferenceJob = PreferenceFlows.intFromString(
			context,
			goalPreferenceKeyRes,
			goalPreferenceDefaultRes
		).onEach { target = it }
			.launchIn(this)
	}

	override suspend fun onDisableInternal(context: Context) {
		goalPreferenceJob?.cancel()
		goalPreferenceJob = null
	}
}
