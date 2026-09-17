package com.adsamcik.tracker.app.activity

import com.adsamcik.tracker.app.startup.ApplicationStartupStateStore
import com.adsamcik.tracker.app.driveTrackingStartup
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.shared.base.startup.TrackingDatabaseContainment
import com.adsamcik.tracker.shared.base.startup.TrackingDatabaseContainmentReason
import com.adsamcik.tracker.shared.base.startup.TrackingDatabaseRetryable
import com.adsamcik.tracker.shared.base.startup.TrackingDatabaseRetryableReason
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingCompletionState
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
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

	@Test
	fun `only a failed legacy import exposes destructive legacy recovery`() {
		startupFailureDestination(
			TrackingStartupResult.Blocked(TrackingStartupStage.LEGACY_IMPORT, "IMPORT_FAILED"),
		) shouldBe StartupDestination.LegacyRecovery
		startupFailureDestination(
			TrackingStartupResult.Blocked(TrackingStartupStage.LEGACY_V27, "UNKNOWN_WRITER"),
		) shouldBe StartupDestination.Recovery
		startupFailureDestination(
			TrackingStartupResult.RetryableFailure(TrackingStartupStage.LIVE_V2, "LEASE"),
		) shouldBe StartupDestination.Recovery
	}

	@Test
	fun `contained databases use preservation-only startup guidance`() {
		startupFailureDestination(
			TrackingStartupResult.Blocked(
				stage = TrackingStartupStage.STORAGE,
				failureCode = "STALE_DEVELOPMENT_V28",
				databaseContainment = TrackingDatabaseContainment(
					TrackingDatabaseContainmentReason.STALE_DEVELOPMENT_V28,
				),
			),
		) shouldBe StartupDestination.DevelopmentDatabaseContainment

		startupFailureDestination(
			TrackingStartupResult.Blocked(
				stage = TrackingStartupStage.STORAGE,
				failureCode = "INCOMPLETE_FINAL_V28_SCHEMA",
				databaseContainment = TrackingDatabaseContainment(
					TrackingDatabaseContainmentReason.INCOMPLETE_FINAL_V28_SCHEMA,
				),
			),
		) shouldBe StartupDestination.DevelopmentDatabaseContainment

		startupFailureDestination(
			TrackingStartupResult.Blocked(
				stage = TrackingStartupStage.STORAGE,
				failureCode = "UNREADABLE_DATABASE",
				databaseContainment = TrackingDatabaseContainment(
					TrackingDatabaseContainmentReason.UNREADABLE_DATABASE,
				),
			),
		) shouldBe StartupDestination.DatabaseContainment
	}

	@Test
	fun `retryable database state does not use permanent containment guidance`() {
		startupFailureDestination(
			TrackingStartupResult.RetryableFailure(
				stage = TrackingStartupStage.STORAGE,
				failureCode = "ACTIVE_DATABASE_CONTENDED",
				databaseRetryable = TrackingDatabaseRetryable(
					TrackingDatabaseRetryableReason.CONTENDED,
				),
			),
		) shouldBe StartupDestination.DatabaseRetryable
	}

	@Test
	fun `visible retryable automatically advances to main when startup becomes ready`() = runTest {
		val state = ApplicationStartupStateStore()
		val destinations = async(start = CoroutineStart.UNDISPATCHED) {
			state.snapshots
				.mapNotNull { snapshot -> snapshot.result }
				.map { startup ->
					startupDestinationForResult(
						startup = startup,
						onboardingRepository = FakeOnboardingRepository(completed = true),
					)
				}
				.take(2)
				.toList()
		}
		state.beginGeneration(7L)
		state.publish(
			generation = 6L,
			result = TrackingStartupResult.Blocked(
				TrackingStartupStage.STORAGE,
				"STALE_GENERATION",
			),
		) shouldBe false
		var attempts = 0
		val ready = TrackingStartupResult.Ready(
			legacyRecoveryPartial = false,
			liveCompletedThroughOrdinal = 0L,
		)
		launch {
			val terminal = driveTrackingStartup(
				reconcile = {
					attempts += 1
					if (attempts <= 4) {
						TrackingStartupResult.RetryableFailure(
							stage = TrackingStartupStage.STORAGE,
							failureCode = "ACTIVE_DATABASE_CONTENDED",
							databaseRetryable = TrackingDatabaseRetryable(
								TrackingDatabaseRetryableReason.CONTENDED,
							),
						)
					} else {
						ready
					}
				},
				waitBeforeRetry = { yield() },
				onRetryableVisible = { result ->
					state.publish(7L, result)
				},
			)
			state.publish(7L, terminal)
		}
		advanceUntilIdle()

		destinations.await() shouldBe listOf(
			StartupDestination.DatabaseRetryable,
			StartupDestination.Main,
		)
		attempts shouldBe 5
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
