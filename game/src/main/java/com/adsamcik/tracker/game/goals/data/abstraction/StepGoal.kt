package com.adsamcik.tracker.game.goals.data.abstraction

import android.content.Context
import androidx.annotation.CallSuper
import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.observer.PreferenceObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class StepGoal(persistence: GoalPersistence) : BaseGoal(persistence) {
	private var lastStepValue = 0

	private val goalPreferenceObserver = { value: Int -> target = value }

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

	@CallSuper
	override suspend fun onEnableInternal(context: Context) {
		launch(Dispatchers.Main) {
			PreferenceObserver.observe(
					context,
					goalPreferenceKeyRes,
					goalPreferenceDefaultRes,
					goalPreferenceObserver
			)
		}.join()
	}

	override suspend fun onDisableInternal(context: Context) {
		PreferenceObserver.removeObserver(context, goalPreferenceKeyRes, goalPreferenceObserver)
	}
}
