package com.adsamcik.tracker.game.goals.data.abstraction

import android.content.Context
import androidx.annotation.CallSuper
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.observer.PreferenceObserver

abstract class StepGoal : BaseGoal() {
	private var lastStepValue = 0

	override val value: Double get() = stepCount.toDouble()

	override val target: Double get() = goalTarget.toDouble()

	protected var stepCount: Int = 0

	protected var goalTarget: Int = 0
		private set

	private val goalPreferenceObserver = { value: Int -> goalTarget = value }

	override fun onSessionUpdated(session: TrackerSession, isNewSession: Boolean) {
		val diff = if (isNewSession) session.steps else lastStepValue - session.steps

		if (diff > 0) {
			stepCount += diff
		} else if (diff < 0) {
			Reporter.report(
					"Step difference is negative. This should never happen session steps:" +
							" ${session.steps} last steps: $lastStepValue"
			)
		}
	}

	@CallSuper
	override suspend fun onEnableInternal(context: Context) {
		PreferenceObserver.observe(
				context,
				goalPreferenceKey,
				goalPreferenceDefault,
				goalPreferenceObserver
		)
	}

	override suspend fun onDisableInternal(context: Context) {
		PreferenceObserver.removeObserver(context, goalPreferenceKey, goalPreferenceObserver)
	}
}
