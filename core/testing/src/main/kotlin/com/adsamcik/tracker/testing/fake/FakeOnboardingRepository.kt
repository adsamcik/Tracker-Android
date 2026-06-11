package com.adsamcik.tracker.testing.fake

import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingCompletionState
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory fake implementation of [OnboardingRepository] for unit tests.
 *
 * Usage:
 * ```kotlin
 * @Test
 * fun `test onboarding flow`() = runTest {
 *     val fakeRepo = FakeOnboardingRepository()
 *     fakeRepo.isCompleted.first() shouldBe false
 *     fakeRepo.markCompleted()
 *     fakeRepo.isCompleted.first() shouldBe true
 * }
 * ```
 */
class FakeOnboardingRepository(
	initialState: OnboardingCompletionState = OnboardingCompletionState(
		completed = false,
		completedTime = 0L,
	),
) : OnboardingRepository {

	private val _state = MutableStateFlow(initialState)

	override val state: Flow<OnboardingCompletionState> = _state.asStateFlow()

	override val isCompleted: Flow<Boolean> = _state.map { it.completed }

	/** Current state snapshot for assertions. */
	val currentState: OnboardingCompletionState
		get() = _state.value

	override suspend fun markCompleted() {
		_state.value = OnboardingCompletionState(
			completed = true,
			completedTime = System.currentTimeMillis(),
		)
	}

	override suspend fun ensureInitialized() {
		// No-op in fake — no legacy migration needed.
	}

	/** Reset to default state (useful between tests). */
	fun reset() {
		_state.value = OnboardingCompletionState(completed = false, completedTime = 0L)
	}

	/** Directly set a complete state (for test setup). */
	fun setState(state: OnboardingCompletionState) {
		_state.value = state
	}
}
