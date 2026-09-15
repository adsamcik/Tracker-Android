package com.adsamcik.tracker.game.goals

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Payload-free calendar invalidation for source-qualified Steps product reads. */
internal object GoalTracker {
	private val calendarInvalidationsMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
	internal val calendarInvalidations: SharedFlow<Unit> = calendarInvalidationsMutable.asSharedFlow()

	/** A new structural day changes requested periods, never their numeric source authority. */
	internal suspend fun onNewDay() {
		calendarInvalidationsMutable.emit(Unit)
	}
}
