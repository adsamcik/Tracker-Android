package com.adsamcik.tracker.testing.fake

import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory fake implementation of [TrackingParamsRepository] for unit tests.
 *
 * Usage:
 * ```kotlin
 * @Test
 * fun `test tracking params`() = runTest {
 *     val fakeRepo = FakeTrackingParamsRepository(
 *         initialState = TrackingParamsState(locationEnabled = false)
 *     )
 *     fakeRepo.setLocationEnabled(true)
 *     fakeRepo.data.first().locationEnabled shouldBe true
 * }
 * ```
 */
class FakeTrackingParamsRepository(
	initialState: TrackingParamsState = TrackingParamsState(),
) : TrackingParamsRepository {

	private val _state = MutableStateFlow(initialState)

	override val data: Flow<TrackingParamsState> = _state.asStateFlow()

	/** Current state snapshot for assertions. */
	val currentState: TrackingParamsState
		get() = _state.value

	override suspend fun update(block: TrackingParamsState.() -> TrackingParamsState) {
		_state.update { it.block() }
	}

	override suspend fun setLocationEnabled(enabled: Boolean) {
		_state.update { it.copy(locationEnabled = enabled) }
	}

	override suspend fun setActivityEnabled(enabled: Boolean) {
		_state.update { it.copy(activityEnabled = enabled) }
	}

	override suspend fun setStepsEnabled(enabled: Boolean) {
		_state.update { it.copy(stepsEnabled = enabled) }
	}

	override suspend fun setWifiEnabled(enabled: Boolean) {
		_state.update { it.copy(wifiEnabled = enabled) }
	}

	override suspend fun setCellEnabled(enabled: Boolean) {
		_state.update { it.copy(cellEnabled = enabled) }
	}

	override suspend fun setBarometerEnabled(enabled: Boolean) {
		_state.update { it.copy(barometerEnabled = enabled) }
	}

	override suspend fun setTransitionDetectionEnabled(enabled: Boolean) {
		_state.update { it.copy(transitionDetectionEnabled = enabled) }
	}

	override suspend fun setNotificationStyled(enabled: Boolean) {
		_state.update { it.copy(notificationStyled = enabled) }
	}

	override suspend fun setMinDistanceMeters(meters: Int) {
		_state.update { it.copy(minDistanceMeters = meters) }
	}

	override suspend fun setMinTimeSeconds(seconds: Int) {
		_state.update { it.copy(minTimeSeconds = seconds) }
	}

	override suspend fun setRequiredAccuracyMeters(meters: Int) {
		_state.update { it.copy(requiredAccuracyMeters = meters) }
	}

	override suspend fun setPreset(preset: TrackingPreset) {
		_state.update { it.copy(presetName = preset.name) }
	}

	/** Reset to default state (useful between tests). */
	fun reset() {
		_state.value = TrackingParamsState()
	}

	/** Directly set a complete state (for test setup). */
	fun setState(state: TrackingParamsState) {
		_state.value = state
	}
}
