package com.adsamcik.tracker.testing.fake

import com.adsamcik.tracker.shared.preferences.map.MapSettingsRepository
import com.adsamcik.tracker.shared.preferences.map.MapSettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory fake implementation of [MapSettingsRepository] for unit tests.
 *
 * Usage:
 * ```kotlin
 * @Test
 * fun `test map settings`() = runTest {
 *     val fakeRepo = FakeMapSettingsRepository(
 *         initialState = MapSettingsState(quality = 0.5f)
 *     )
 *     fakeRepo.setQuality(1.0f)
 *     fakeRepo.data.first().quality shouldBe 1.0f
 * }
 * ```
 */
class FakeMapSettingsRepository(
	initialState: MapSettingsState = MapSettingsState(),
) : MapSettingsRepository {

	private val _state = MutableStateFlow(initialState)

	override val data: Flow<MapSettingsState> = _state.asStateFlow()

	/** Current state snapshot for assertions. */
	val currentState: MapSettingsState
		get() = _state.value

	override suspend fun setQuality(quality: Float) {
		_state.update { it.copy(quality = quality) }
	}

	override suspend fun setMaxHeatPoints(maxHeat: Int) {
		_state.update { it.copy(maxHeatPoints = maxHeat) }
	}

	override suspend fun setVisitThresholdSeconds(seconds: Int) {
		_state.update { it.copy(visitThresholdSeconds = seconds) }
	}

	override suspend fun setLegacyHeatmapEnabled(enabled: Boolean) {
		_state.update { it.copy(legacyHeatmapEnabled = enabled) }
	}

	override suspend fun setZoomButtonsEnabled(enabled: Boolean) {
		_state.update { it.copy(zoomButtonsEnabled = enabled) }
	}

	/** Reset to default state (useful between tests). */
	fun reset() {
		_state.value = MapSettingsState()
	}

	/** Directly set a complete state (for test setup). */
	fun setState(state: MapSettingsState) {
		_state.value = state
	}
}
