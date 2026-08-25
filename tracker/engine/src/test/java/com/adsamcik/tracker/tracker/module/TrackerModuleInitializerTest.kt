package com.adsamcik.tracker.tracker.module

import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.resilience.TrackingAutoRecoveryAuthorization
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerModuleInitializerTest {
	@Test
	fun `background force-stop start cannot initialize control before foreground release`() = runTest {
		val foreground = CompletableDeferred<Unit>()
		var controlInitializations = 0
		val startup = async {
			val authorization = awaitTrackerAutoRecoveryAuthorization(
				reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
				awaitAuthorizationAfterReady = {
					foreground.await()
					TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
				},
				awaitFreshReadyAfterExplicitForeground = {
					TrackingStartupResult.Ready(false, 0L)
				},
				releaseAfterFreshReady = { true },
			)
			initializeTrackerAutomaticControlAfterAuthorization(
				authorization = requireNotNull(authorization),
				initialize = {
					controlInitializations += 1
				},
			)
		}
		runCurrent()

		startup.isCompleted shouldBe false
		controlInitializations shouldBe 0
		foreground.complete(Unit)
		startup.await()
		controlInitializations shouldBe 1
	}

	@Test
	fun `foreground force-stop rearm follows terminal recovery and fresh Ready exactly once`() = runTest {
		val timeline = mutableListOf<String>()
		var staleSessionTerminal = false
		val authorization = awaitTrackerAutoRecoveryAuthorization(
			reconcileStartup = {
				staleSessionTerminal = true
				timeline += "force-stop-session-terminal"
				TrackingStartupResult.Ready(false, 0L)
			},
			awaitAuthorizationAfterReady = {
				timeline += "foreground-consumed"
				TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
			},
			awaitFreshReadyAfterExplicitForeground = {
				staleSessionTerminal shouldBe true
				timeline += "fresh-ready"
				TrackingStartupResult.Ready(false, 0L)
			},
			releaseAfterFreshReady = {
				timeline += "suppression-released"
				true
			},
		)

		initializeTrackerAutomaticControlAfterAuthorization(
			authorization = requireNotNull(authorization),
			initialize = {
				staleSessionTerminal shouldBe true
				timeline += "control-reconciled"
			},
		)

		timeline shouldBe listOf(
			"force-stop-session-terminal",
			"foreground-consumed",
			"fresh-ready",
			"suppression-released",
			"control-reconciled",
		)
	}

	@Test
	fun `foreground recorded before initializer still performs one reconciliation`() = runTest {
		var reconciliations = 0
		val authorization = awaitTrackerAutoRecoveryAuthorization(
			reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
			awaitAuthorizationAfterReady = {
				TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
			},
			awaitFreshReadyAfterExplicitForeground = {
				TrackingStartupResult.Ready(false, 0L)
			},
			releaseAfterFreshReady = { true },
		)

		initializeTrackerAutomaticControlAfterAuthorization(
			authorization = requireNotNull(authorization),
			initialize = {
				reconciliations += 1
			},
		)

		reconciliations shouldBe 1
	}

	@Test
	fun `foreground authorization stays suppressed while fresh Ready is pending`() = runTest {
		val processGate = com.adsamcik.tracker.tracker.resilience.TrackingAutoRecoveryProcessGate()
		val freshReady = CompletableDeferred<TrackingStartupResult.Ready>()
		processGate.suppressAfterConfirmedForceStop()
		processGate.recordExplicitForegroundLaunch(confirmedForceStop = true) shouldBe true

		val authorization = async {
			awaitTrackerAutoRecoveryAuthorization(
				reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
				awaitAuthorizationAfterReady = {
					processGate.awaitAuthorizationAfterStartupReady(confirmedForceStop = true)
				},
				awaitFreshReadyAfterExplicitForeground = { freshReady.await() },
				releaseAfterFreshReady = {
					processGate.releaseAfterFreshStartupReady(confirmedForceStop = true)
				},
			)
		}
		runCurrent()

		authorization.isCompleted shouldBe false
		processGate.isSuppressed(confirmedForceStop = true) shouldBe true
		freshReady.complete(TrackingStartupResult.Ready(false, 0L))
		authorization.await() shouldBe
			TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
		processGate.isSuppressed(confirmedForceStop = true) shouldBe false
	}

	@Test
	fun `module initialization gate rejects duplicate direct invocation`() {
		val gate = TrackerModuleInitializationGate()

		gate.tryStart() shouldBe true
		gate.tryStart() shouldBe false
	}

	@Test
	fun `pending automation waits for coherent authority then retries to completion`() = runTest {
		val authorityReady = MutableStateFlow(false)
		val drainRequired = MutableStateFlow(true)
		var attempts = 0
		backgroundScope.launch {
			driveActivityAutomationEffectDrain(
				authorityReady = authorityReady,
				drainRequired = drainRequired,
				onRetryGenerationExhausted = { drainRequired.value = false },
				initialRetryDelayMillis = 10,
				maxRetryDelayMillis = 20,
			) {
				attempts += 1
				if (attempts == 1) {
					ActivityAutomationDrainResult.Retryable(0, 0)
				} else {
					drainRequired.value = false
					ActivityAutomationDrainResult.Complete(1, 0)
				}
			}
		}

		runCurrent()
		attempts shouldBe 0

		authorityReady.value = true
		runCurrent()
		attempts shouldBe 1

		advanceTimeBy(10)
		runCurrent()
		attempts shouldBe 2
	}

	@Test
	fun `authority closing cancels retry until a coherent revision returns`() = runTest {
		val authorityReady = MutableStateFlow(true)
		val drainRequired = MutableStateFlow(true)
		var attempts = 0
		backgroundScope.launch {
			driveActivityAutomationEffectDrain(
				authorityReady = authorityReady,
				drainRequired = drainRequired,
				onRetryGenerationExhausted = { drainRequired.value = false },
				initialRetryDelayMillis = 10,
				maxRetryDelayMillis = 20,
			) {
				attempts += 1
				ActivityAutomationDrainResult.Retryable(0, 0)
			}
		}

		runCurrent()
		attempts shouldBe 1
		authorityReady.value = false
		runCurrent()
		advanceTimeBy(100)
		runCurrent()
		attempts shouldBe 1

		authorityReady.value = true
		runCurrent()
		attempts shouldBe 2
	}

	@Test
	fun `bounded backlog continuations yield and drain without retry delay`() = runTest {
		val authorityReady = MutableStateFlow(true)
		val drainRequired = MutableStateFlow(true)
		var attempts = 0
		backgroundScope.launch {
			driveActivityAutomationEffectDrain(
				authorityReady = authorityReady,
				drainRequired = drainRequired,
				onRetryGenerationExhausted = { drainRequired.value = false },
				initialRetryDelayMillis = 10,
				maxRetryDelayMillis = 20,
			) {
				attempts += 1
				if (attempts < 3) {
					ActivityAutomationDrainResult.MorePending(attempts, 0)
				} else {
					drainRequired.value = false
					ActivityAutomationDrainResult.Complete(attempts, 0)
				}
			}
		}

		runCurrent()

		attempts shouldBe 3
		currentTime shouldBe 0
	}

	@Test
	fun `projection failure waits for a later bounded signal instead of retry spinning`() = runTest {
		val authorityReady = MutableStateFlow(true)
		val drainRequired = MutableStateFlow(true)
		var attempts = 0
		backgroundScope.launch {
			driveActivityAutomationEffectDrain(
				authorityReady = authorityReady,
				drainRequired = drainRequired,
				onRetryGenerationExhausted = { drainRequired.value = false },
				initialRetryDelayMillis = 10,
				maxRetryDelayMillis = 20,
			) {
				attempts += 1
				if (attempts == 1) {
					ActivityAutomationDrainResult.ProjectionDeferred()
				} else {
					ActivityAutomationDrainResult.Complete(0, 0)
				}
			}
		}

		runCurrent()
		attempts shouldBe 1
		advanceTimeBy(1_000)
		runCurrent()
		attempts shouldBe 1

		drainRequired.value = true
		runCurrent()
		attempts shouldBe 2
	}

	@Test
	fun `attempt budget makes no N plus one call until a new signal`() = runTest {
		val authorityReady = MutableStateFlow(true)
		val drainRequired = MutableStateFlow(true)
		var attempts = 0
		backgroundScope.launch {
			driveActivityAutomationEffectDrain(
				authorityReady = authorityReady,
				drainRequired = drainRequired,
				onRetryGenerationExhausted = { drainRequired.value = false },
				initialRetryDelayMillis = 10,
				maxRetryDelayMillis = 20,
				maxDrainAttempts = 3,
				maxDrainElapsedMillis = 1_000,
				elapsedRealtimeMillis = { currentTime },
			) {
				attempts += 1
				ActivityAutomationDrainResult.MorePending(attempts, 0)
			}
		}

		runCurrent()
		attempts shouldBe 3
		advanceTimeBy(10_000)
		runCurrent()
		attempts shouldBe 3

		drainRequired.value = true
		runCurrent()
		attempts shouldBe 6
	}

	@Test
	fun `elapsed budget stops retry wakeups with pending evidence intact`() = runTest {
		val authorityReady = MutableStateFlow(true)
		val drainRequired = MutableStateFlow(true)
		var attempts = 0
		backgroundScope.launch {
			driveActivityAutomationEffectDrain(
				authorityReady = authorityReady,
				drainRequired = drainRequired,
				onRetryGenerationExhausted = { drainRequired.value = false },
				initialRetryDelayMillis = 10,
				maxRetryDelayMillis = 20,
				maxDrainAttempts = 10,
				maxDrainElapsedMillis = 25,
				elapsedRealtimeMillis = { currentTime },
			) {
				attempts += 1
				ActivityAutomationDrainResult.Retryable(0, 0)
			}
		}

		runCurrent()
		advanceTimeBy(25)
		runCurrent()
		attempts shouldBe 2
		drainRequired.value shouldBe false

		advanceTimeBy(10_000)
		runCurrent()
		attempts shouldBe 2

		drainRequired.value = true
		runCurrent()
		attempts shouldBe 3
	}
}
