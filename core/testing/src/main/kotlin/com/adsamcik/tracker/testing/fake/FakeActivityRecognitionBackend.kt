package com.adsamcik.tracker.testing.fake

import com.adsamcik.tracker.activity.api.backend.ActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.ActivityUpdate
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.backend.RecognitionConfig
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fake [ActivityRecognitionBackend] for unit tests.
 *
 * Allows tests to:
 * - Verify that recognition was started/stopped
 * - Emit synthetic activity and transition updates
 * - Inspect the last configuration that was applied
 *
 * Usage:
 * ```kotlin
 * @Test
 * fun `test activity tracking`() = runTest {
 *     val fakeBackend = FakeActivityRecognitionBackend()
 *     val manager = ActivityRequestManager(fakeBackend)
 *
 *     manager.requestActivity(context, request)
 *     fakeBackend.isRunning shouldBe true
 *
 *     fakeBackend.emitUpdate(ActivityUpdate(
 *         activity = RecognizedActivity(DetectedActivityType.WALKING, 85),
 *         elapsedTimeMillis = 1000L,
 *     ))
 * }
 * ```
 */
class FakeActivityRecognitionBackend(
	override val isAvailable: Boolean = true,
) : ActivityRecognitionBackend {

	override val name: String = "Fake"

	override var lastActivity: RecognizedActivity = RecognizedActivity.UNKNOWN
		private set

	override var lastActivityElapsedTimeMillis: Long = 0L
		private set

	private val _activityUpdates = MutableSharedFlow<ActivityUpdate>(extraBufferCapacity = 16)
	override val activityUpdates: Flow<ActivityUpdate> = _activityUpdates.asSharedFlow()

	private val _transitionUpdates = MutableSharedFlow<List<TransitionUpdate>>(extraBufferCapacity = 16)
	override val transitionUpdates: Flow<List<TransitionUpdate>> = _transitionUpdates.asSharedFlow()

	/** Whether [startUpdates] has been called without a matching [stopUpdates]. */
	var isRunning: Boolean = false
		private set

	/** The most recent configuration passed to [startUpdates], or null. */
	var lastConfig: RecognitionConfig? = null
		private set

	/** How many times [startUpdates] has been called. */
	var startCount: Int = 0
		private set

	/** How many times [stopUpdates] has been called. */
	var stopCount: Int = 0
		private set

	override suspend fun startUpdates(config: RecognitionConfig): Boolean {
		if (!isAvailable) return false
		lastConfig = config
		isRunning = true
		startCount++
		return true
	}

	override suspend fun stopUpdates() {
		isRunning = false
		stopCount++
	}

	/** Emit a synthetic activity update into the [activityUpdates] flow. */
	suspend fun emitUpdate(update: ActivityUpdate) {
		lastActivity = update.activity
		lastActivityElapsedTimeMillis = update.elapsedTimeMillis
		_activityUpdates.emit(update)
	}

	/** Emit a synthetic transition batch into the [transitionUpdates] flow. */
	suspend fun emitTransitions(updates: List<TransitionUpdate>) {
		_transitionUpdates.emit(updates)
	}

	/** Reset all counters and state. */
	fun reset() {
		isRunning = false
		lastConfig = null
		startCount = 0
		stopCount = 0
		lastActivity = RecognizedActivity.UNKNOWN
		lastActivityElapsedTimeMillis = 0L
	}
}
