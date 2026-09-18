package com.adsamcik.tracker.tracker.module

import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityState
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationDebt
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationFailure
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationResult
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationResult
import com.adsamcik.tracker.tracker.resilience.TrackingAutoRecoveryAuthorization
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsDemandReconciliation
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderRegistrationFailure
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderRegistrationResult
import com.adsamcik.tracker.tracker.source.ambient.steps.HealthConnectAmbientStepsAvailability
import com.adsamcik.tracker.tracker.source.ambient.steps.LocalRecordingAmbientStepsAvailability
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
	fun `startup waits for bootstrap reissue debt before retention or provider work`() = runTest {
		var retentionCalls = 0
		var retirementCalls = 0
		val sourceDebt = SourcePolicyRevisionReconciliationResult.Retryable(
			SourcePolicyRevisionReconciliationDebt(
				policyRevision = 1L,
				failures = listOf(
					SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable,
				),
			),
		)

		val result = reconcileTrackerStartupAuthority(
			reconcileSourcePolicy = { sourceDebt },
			reconcileRetention = {
				retentionCalls += 1
				emptyList()
			},
			retireAmbientSteps = {
				retirementCalls += 1
				AmbientStepsSettingsReconciliationResult(
					complete = true,
					operational = false,
				)
			},
		)

		result shouldBe TrackerStartupAuthorityResult.SourcePolicyDebt(sourceDebt)
		retentionCalls shouldBe 0
		retirementCalls shouldBe 0
	}

	@Test
	fun `startup retention exception retires Ambient Steps and returns typed retry debt`() = runTest {
		val events = mutableListOf<String>()
		val result = reconcileRetentionAuthorityAtStartup(
			expectedAmbientStepsState = RetentionAuthorityState.ACTIVE,
			reconcileRetention = {
				events += "retention"
				error("retention unavailable")
			},
			retireAmbientSteps = {
				events += "retire-ambient-steps"
				AmbientStepsSettingsReconciliationResult(
					complete = true,
					operational = false,
				)
			},
		)

		result.shouldBeInstanceOf<TrackerStartupAuthorityResult.RetryableRetentionDebt>()
		events shouldBe listOf("retention", "retire-ambient-steps")
	}

	@Test
	fun `returned unavailable retention is not treated as startup success`() = runTest {
		var retirements = 0
		val unavailable = RetentionAuthorityResult.Unavailable(
			source = TrackingSourceComponent.STEPS,
			scope = RetentionAuthorityScope.LIVE_AMBIENT,
			reason = RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
		)

		val result = reconcileRetentionAuthorityAtStartup(
			expectedAmbientStepsState = RetentionAuthorityState.ACTIVE,
			reconcileRetention = { listOf(unavailable) },
			retireAmbientSteps = {
				retirements += 1
				AmbientStepsSettingsReconciliationResult(
					complete = true,
					operational = false,
				)
			},
		)

		result.shouldBeInstanceOf<TrackerStartupAuthorityResult.RetryableRetentionDebt>()
		retirements shouldBe 1
	}

	@Test
	fun `wrong startup retention state retires provider and remains blocked`() = runTest {
		var retirements = 0
		val result = reconcileRetentionAuthorityAtStartup(
			expectedAmbientStepsState = RetentionAuthorityState.ACTIVE,
			reconcileRetention = {
				listOf(
					RetentionAuthorityResult.Unchanged(
						source = TrackingSourceComponent.STEPS,
						scope = RetentionAuthorityScope.LIVE_AMBIENT,
						state = RetentionAuthorityState.REVOKED,
						approvalRevision = 3L,
					),
				)
			},
			retireAmbientSteps = {
				retirements += 1
				AmbientStepsSettingsReconciliationResult(
					complete = true,
					operational = false,
				)
			},
		)

		result.shouldBeInstanceOf<TrackerStartupAuthorityResult.UnverifiableRetentionDebt>()
		retirements shouldBe 1
	}

	@Test
	fun `exact startup retention state permits provider startup without retirement`() = runTest {
		var retirements = 0
		val result = reconcileRetentionAuthorityAtStartup(
			expectedAmbientStepsState = RetentionAuthorityState.ACTIVE,
			reconcileRetention = {
				listOf(
					RetentionAuthorityResult.Unchanged(
						source = TrackingSourceComponent.STEPS,
						scope = RetentionAuthorityScope.LIVE_AMBIENT,
						state = RetentionAuthorityState.ACTIVE,
						approvalRevision = 3L,
					),
				)
			},
			retireAmbientSteps = {
				retirements += 1
				AmbientStepsSettingsReconciliationResult(
					complete = true,
					operational = false,
				)
			},
		)

		result shouldBe TrackerStartupAuthorityResult.Complete
		retirements shouldBe 0
	}

	@Test
	fun `provider removal debt remains typed and blocks startup after retirement`() = runTest {
		val result = reconcileRetentionAuthorityAtStartup(
			expectedAmbientStepsState = RetentionAuthorityState.ACTIVE,
			reconcileRetention = { emptyList() },
			retireAmbientSteps = {
				AmbientStepsSettingsReconciliationResult(
					complete = false,
					operational = false,
					failure = AmbientStepsSettingsReconciliationFailure.PROVIDER_REMOVAL_FAILED,
					retryable = true,
				)
			},
		)

		val retry =
			result.shouldBeInstanceOf<TrackerStartupAuthorityResult.RetryableRetentionDebt>()
		retry.debt.retirement.failure shouldBe
			AmbientStepsSettingsReconciliationFailure.PROVIDER_REMOVAL_FAILED
	}

	@Test
	fun `ambient Steps startup leaves unavailable provider inactive without reporting failure`() = runTest {
		val unavailable = AmbientStepsDemandReconciliation.Unavailable(
			healthConnect = HealthConnectAmbientStepsAvailability.SDK_UNAVAILABLE,
			localRecording = LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_MISSING,
		)
		var failures = 0

		val result = runAmbientStepsStartupReconciliation(
			reconcile = { AmbientStepsProviderRegistrationResult.Inactive(unavailable) },
			onFailure = { failures += 1 },
		)

		result shouldBe AmbientStepsProviderRegistrationResult.Inactive(unavailable)
		failures shouldBe 0
	}

	@Test
	fun `ambient Steps startup reports typed degradation without blocking tracker handoff`() = runTest {
		var reported: Exception? = IllegalStateException("not-called")
		val degraded = AmbientStepsProviderRegistrationResult.Degraded(
			selectedProvider = null,
			activeRegistrationGeneration = null,
			failure = AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED,
			retryable = true,
		)

		val result = runAmbientStepsStartupReconciliation(
			reconcile = { degraded },
			onFailure = { reported = it },
		)

		result shouldBe degraded
		reported shouldBe null
	}

	@Test
	fun `ambient Steps startup contains ordinary failure but preserves cancellation`() = runTest {
		val failure = IllegalStateException("provider unavailable")
		var reported: Exception? = null

		runAmbientStepsStartupReconciliation(
			reconcile = { throw failure },
			onFailure = { reported = it },
		) shouldBe null
		reported shouldBe failure

		shouldThrow<CancellationException> {
			runAmbientStepsStartupReconciliation(
				reconcile = { throw CancellationException("cancel") },
				onFailure = { error("Cancellation must not be reported as provider failure") },
			)
		}
	}

	@Test
	fun `background force-stop start cannot initialize control before foreground authorization`() = runTest {
		val foreground = CompletableDeferred<Unit>()
		var controlInitializations = 0
		val startup = async {
			handoffTrackerInitializationAfterAuthorization(
				reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
				currentReadyGeneration = { 4L },
				awaitAuthorizationAfterReady = {
					foreground.await()
					TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
				},
				awaitNextReady = { error("No generation race expected") },
				withReadyGenerationOperation = { generation, operation ->
					generation shouldBe 4L
					operation()
				},
				releaseForReadyGeneration = { true },
				handoff = { authorization ->
					initializeTrackerAutomaticControlAfterAuthorization(
						authorization = authorization,
						initialize = { controlInitializations += 1 },
					)
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
	fun `foreground force-stop rearm follows terminal recovery in one Ready generation`() = runTest {
		val timeline = mutableListOf<String>()
		var staleSessionTerminal = false
		handoffTrackerInitializationAfterAuthorization(
			reconcileStartup = {
				staleSessionTerminal = true
				timeline += "force-stop-session-terminal"
				TrackingStartupResult.Ready(false, 0L)
			},
			currentReadyGeneration = {
				staleSessionTerminal shouldBe true
				timeline += "ready-generation-captured"
				7L
			},
			awaitAuthorizationAfterReady = {
				timeline += "foreground-consumed"
				TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
			},
			awaitNextReady = { error("No generation race expected") },
			withReadyGenerationOperation = { generation, operation ->
				generation shouldBe 7L
				timeline += "ready-generation-handoff"
				operation()
			},
			releaseForReadyGeneration = {
				timeline += "suppression-released"
				true
			},
			handoff = { authorization ->
				staleSessionTerminal shouldBe true
				initializeTrackerAutomaticControlAfterAuthorization(
					authorization = authorization,
					initialize = { timeline += "control-reconciled" },
				)
			},
		)

		timeline shouldBe listOf(
			"force-stop-session-terminal",
			"ready-generation-captured",
			"foreground-consumed",
			"ready-generation-handoff",
			"control-reconciled",
			"suppression-released",
		)
	}

	@Test
	fun `foreground recorded before initializer still performs one reconciliation`() = runTest {
		var reconciliations = 0
		var releases = 0
		handoffTrackerInitializationAfterAuthorization(
			reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
			currentReadyGeneration = { 3L },
			awaitAuthorizationAfterReady = {
				TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
			},
			awaitNextReady = { error("No generation race expected") },
			withReadyGenerationOperation = { generation, operation ->
				generation shouldBe 3L
				operation()
			},
			releaseForReadyGeneration = {
				releases += 1
				true
			},
			handoff = { authorization ->
				initializeTrackerAutomaticControlAfterAuthorization(
					authorization = authorization,
					initialize = { reconciliations += 1 },
				)
			},
		)

		reconciliations shouldBe 1
		releases shouldBe 1
	}

	@Test
	fun `foreground authorization stays suppressed until a replacement Ready accepts handoff`() = runTest {
		val processGate = com.adsamcik.tracker.tracker.resilience.TrackingAutoRecoveryProcessGate()
		val replacementReady = CompletableDeferred<TrackingStartupResult.Ready>()
		var readyGeneration = 1L
		var handoffAttempts = 0
		var controlInitializations = 0
		processGate.suppressAfterConfirmedForceStop()
		processGate.recordExplicitForegroundLaunch(confirmedForceStop = true) shouldBe true

		val authorization = async {
			handoffTrackerInitializationAfterAuthorization(
				reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
				currentReadyGeneration = { readyGeneration },
				awaitAuthorizationAfterReady = {
					processGate.awaitAuthorizationAfterStartupReady(confirmedForceStop = true)
				},
				awaitNextReady = { replacementReady.await() },
				withReadyGenerationOperation = { generation, operation ->
					handoffAttempts += 1
					if (handoffAttempts == 1) {
						generation shouldBe 1L
						null
					} else {
						generation shouldBe 2L
						operation()
					}
				},
				releaseForReadyGeneration = {
					processGate.releaseForReadyGeneration(confirmedForceStop = true)
				},
				handoff = { controlInitializations += 1 },
			)
		}
		runCurrent()

		authorization.isCompleted shouldBe false
		processGate.isSuppressed(confirmedForceStop = true) shouldBe true
		controlInitializations shouldBe 0
		readyGeneration = 2L
		replacementReady.complete(TrackingStartupResult.Ready(false, 0L))
		authorization.await() shouldBe
			TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
		processGate.isSuppressed(confirmedForceStop = true) shouldBe false
		controlInitializations shouldBe 1
		handoffAttempts shouldBe 2
	}

	@Test
	fun `startup race keeps one-shot initializer alive until Ready is republished`() = runTest {
		val ready = CompletableDeferred<TrackingStartupResult.Ready>()
		var generation = 1L
		var controlInitializations = 0
		val initialization = async {
			handoffTrackerInitializationAfterAuthorization(
				reconcileStartup = {
					TrackingStartupResult.RetryableFailure(
						com.adsamcik.tracker.shared.base.startup.TrackingStartupStage.PREVIOUS_EXIT,
						"STOP_RACED_INITIALIZER",
					)
				},
				currentReadyGeneration = { generation },
				awaitAuthorizationAfterReady = {
					TrackingAutoRecoveryAuthorization.ORDINARY_START
				},
				awaitNextReady = { ready.await() },
				withReadyGenerationOperation = { expectedGeneration, operation ->
					expectedGeneration shouldBe 2L
					operation()
				},
				releaseForReadyGeneration = { error("Ordinary startup has no suppression release") },
				handoff = { controlInitializations += 1 },
			)
		}
		runCurrent()

		initialization.isCompleted shouldBe false
		controlInitializations shouldBe 0
		generation = 2L
		ready.complete(TrackingStartupResult.Ready(false, 0L))
		initialization.await() shouldBe TrackingAutoRecoveryAuthorization.ORDINARY_START
		controlInitializations shouldBe 1
	}

	@Test
	fun `failed control handoff leaves force-stop suppression closed`() = runTest {
		val processGate = com.adsamcik.tracker.tracker.resilience.TrackingAutoRecoveryProcessGate()
		var releaseAttempts = 0
		var failureObserved = false
		processGate.suppressAfterConfirmedForceStop()
		processGate.recordExplicitForegroundLaunch(confirmedForceStop = true) shouldBe true

		try {
			handoffTrackerInitializationAfterAuthorization(
				reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
				currentReadyGeneration = { 5L },
				awaitAuthorizationAfterReady = {
					processGate.awaitAuthorizationAfterStartupReady(confirmedForceStop = true)
				},
				awaitNextReady = { error("A failed operation must not be treated as a stale gate") },
				withReadyGenerationOperation = { generation, operation ->
					generation shouldBe 5L
					operation()
				},
				releaseForReadyGeneration = {
					releaseAttempts += 1
					processGate.releaseForReadyGeneration(confirmedForceStop = true)
				},
				handoff = { error("singleton installation failed") },
			)
		} catch (_: IllegalStateException) {
			failureObserved = true
		}

		failureObserved shouldBe true
		releaseAttempts shouldBe 0
		processGate.isSuppressed(confirmedForceStop = true) shouldBe true
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
