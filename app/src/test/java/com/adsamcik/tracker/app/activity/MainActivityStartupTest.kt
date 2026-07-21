package com.adsamcik.tracker.app.activity

import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingCompletionState
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

class MainActivityStartupTest {

    @Test
    fun `completed onboarding resolves to main`() = runTest {
        resolveStartupDestination(FakeOnboardingRepository(completed = true)) shouldBe StartupDestination.Main
    }

    @Test
    fun `incomplete onboarding resolves to onboarding`() = runTest {
        resolveStartupDestination(FakeOnboardingRepository(completed = false)) shouldBe StartupDestination.Onboarding
    }

    @Test
    fun `onboarding read failure resolves to setup safe error state`() = runTest {
        val repository = FakeOnboardingRepository(
            isCompletedFlow = flow { throw IOException("datastore unavailable") },
        )

        resolveStartupDestination(repository) shouldBe StartupDestination.OnboardingReadFailed
    }

    @Test
    fun `deep navigation only executes after startup resolves to main`() {
        val request = DeepNavigationRequest(
            target = MainActivityCompose.TARGET_GAME,
            requestId = "test-request",
        )

        gatedDeepNavigationRequest(StartupDestination.Pending, request) shouldBe null
        gatedDeepNavigationRequest(StartupDestination.Onboarding, request) shouldBe null
        gatedDeepNavigationRequest(StartupDestination.OnboardingReadFailed, request) shouldBe null
        gatedDeepNavigationRequest(StartupDestination.Main, request) shouldBe request
    }

    private class FakeOnboardingRepository(
        completed: Boolean = false,
        private val isCompletedFlow: Flow<Boolean> = flowOf(completed),
    ) : OnboardingRepository {
        override val isCompleted: Flow<Boolean> = isCompletedFlow
        override val state: Flow<OnboardingCompletionState> = flowOf(
            OnboardingCompletionState(
                completed = completed,
                completedTime = 0L,
            ),
        )

        override suspend fun markCompleted() = Unit

        override suspend fun ensureInitialized() = Unit
    }
}
