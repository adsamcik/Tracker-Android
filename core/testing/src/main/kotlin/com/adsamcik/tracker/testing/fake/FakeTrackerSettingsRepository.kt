package com.adsamcik.tracker.testing.fake

import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory fake implementation of [TrackerSettingsRepository] for unit tests.
 *
 * This allows testing code that depends on TrackerSettingsRepository without
 * requiring Robolectric, Context, or DataStore.
 *
 * Usage:
 * ```kotlin
 * @Test
 * fun `test with fake settings`() = runTest {
 *     val fakeRepo = FakeTrackerSettingsRepository(
 *         initialState = TrackerSettingsState(
 *             autoUnitSwitch = true,
 *             lengthSystem = LengthSystem.Imperial,
 *             speedFormat = SpeedFormat.Minute
 *         )
 *     )
 *
 *     // Inject fakeRepo into the class under test
 *     val viewModel = MyViewModel(fakeRepo)
 *
 *     // Verify behavior
 *     viewModel.settings.first().autoUnitSwitch shouldBe true
 *
 *     // Modify settings and verify reactions
 *     fakeRepo.setAutoUnitSwitch(false)
 *     viewModel.settings.first().autoUnitSwitch shouldBe false
 * }
 * ```
 */
class FakeTrackerSettingsRepository(
    initialState: TrackerSettingsState = TrackerSettingsState.DEFAULT
) : TrackerSettingsRepository {

    private val _state = MutableStateFlow(initialState)

    override val data: Flow<TrackerSettingsState> = _state.asStateFlow()

    /** Current state snapshot for assertions. */
    val currentState: TrackerSettingsState
        get() = _state.value

    override suspend fun setAutoUnitSwitch(enabled: Boolean) {
        _state.update { it.copy(autoUnitSwitch = enabled) }
    }

    override suspend fun setLengthSystem(system: LengthSystem) {
        _state.update { it.copy(lengthSystem = system) }
    }

    override suspend fun setSpeedFormat(format: SpeedFormat) {
        _state.update { it.copy(speedFormat = format) }
    }

    /** Reset to initial state (useful between tests). */
    fun reset(state: TrackerSettingsState = TrackerSettingsState.DEFAULT) {
        _state.value = state
    }

    /** Directly set a complete state (for test setup). */
    fun setState(state: TrackerSettingsState) {
        _state.value = state
    }
}
