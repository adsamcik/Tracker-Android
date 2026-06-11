package com.adsamcik.tracker.testing.fake

import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesRepository
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory fake implementation of [OnlineMapTilesRepository] for unit tests.
 *
 * Default initial state is fully opt-out (matches production default), so
 * tests that don't explicitly enable online tiles see the offline-only mode.
 *
 * Usage:
 * ```kotlin
 * val fakeRepo = FakeOnlineMapTilesRepository(
 *     initialState = OnlineMapTilesState(enabled = true, providerId = "protomaps")
 * )
 * fakeRepo.setEnabled(false)
 * fakeRepo.data.first().enabled shouldBe false
 * ```
 */
class FakeOnlineMapTilesRepository(
	initialState: OnlineMapTilesState = OnlineMapTilesState(),
) : OnlineMapTilesRepository {

	private val _state = MutableStateFlow(initialState)

	override val data: Flow<OnlineMapTilesState> = _state.asStateFlow()

	/** Current state snapshot for assertions. */
	val currentState: OnlineMapTilesState
		get() = _state.value

	override suspend fun setEnabled(enabled: Boolean) {
		_state.update { it.copy(enabled = enabled) }
	}

	override suspend fun setProviderId(providerId: String) {
		_state.update { it.copy(providerId = providerId) }
	}

	override suspend fun setCustomUrl(customUrl: String) {
		_state.update { it.copy(customUrl = customUrl) }
	}

	/** Reset to default state (useful between tests). */
	fun reset() {
		_state.value = OnlineMapTilesState()
	}

	/** Directly set a complete state (for test setup). */
	fun setState(state: OnlineMapTilesState) {
		_state.value = state
	}
}
