package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.tracker.api.AmbientStepsProviderCleanupFailure
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AmbientStepsProviderLifecycleOwnerTest {
	@Test
	fun `reconcile uses one exact boundary for demand and registration`() = runTest {
		val boundary = boundary(10L)
		val demand = unavailable()
		var demandBoundary: AmbientStepsDemandBoundary? = null
		var registrationBoundary: AmbientStepsDemandBoundary? = null
		val subject = AmbientStepsProviderLifecycleOwner(
			currentBoundary = { boundary },
			reconcileDemand = {
				demandBoundary = it
				demand
			},
			reconcileRegistration = { actualDemand, actualBoundary ->
				actualDemand shouldBe demand
				registrationBoundary = actualBoundary
				AmbientStepsProviderRegistrationResult.Inactive(demand)
			},
			closeRegistration = { completeCleanup() },
		)

		subject.reconcile() shouldBe AmbientStepsProviderRegistrationResult.Inactive(demand)

		demandBoundary shouldBe boundary
		registrationBoundary shouldBe boundary
	}

	@Test
	fun `deletion cleanup waits for admitted provider reconciliation`() = runTest {
		val providerEntered = CompletableDeferred<Unit>()
		val releaseProvider = CompletableDeferred<Unit>()
		val events = mutableListOf<String>()
		val demand = unavailable()
		val subject = AmbientStepsProviderLifecycleOwner(
			currentBoundary = { boundary(10L) },
			reconcileDemand = { demand },
			reconcileRegistration = { _, _ ->
				events += "provider-start"
				providerEntered.complete(Unit)
				releaseProvider.await()
				events += "provider-finish"
				AmbientStepsProviderRegistrationResult.Inactive(demand)
			},
			closeRegistration = {
				events += "provider-close"
				completeCleanup()
			},
		)

		val reconcile = async { subject.reconcile() }
		providerEntered.await()
		val cleanup = async { subject.closeForCollectedDataDeletion() }
		runCurrent()

		cleanup.isCompleted shouldBe false
		releaseProvider.complete(Unit)
		reconcile.await()
		cleanup.await().complete shouldBe true
		events shouldBe listOf("provider-start", "provider-finish", "provider-close")
	}

	@Test
	fun `cleanup failure is mapped without exposing provider identity`() = runTest {
		val subject = AmbientStepsProviderLifecycleOwner(
			currentBoundary = { boundary(10L) },
			reconcileDemand = { unavailable() },
			reconcileRegistration = { demand, _ ->
				AmbientStepsProviderRegistrationResult.Inactive(demand)
			},
			closeRegistration = {
				AmbientStepsProviderCleanupResult(
					complete = false,
					pendingProviders = setOf(AmbientStepsProvider.LOCAL_RECORDING_STEPS),
					failure = AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED,
					retryable = true,
				)
			},
		)

		val result = subject.closeForCollectedDataDeletion()

		result.complete shouldBe false
		result.failure shouldBe AmbientStepsProviderCleanupFailure.PROVIDER_REMOVAL_FAILED
		result.retryable shouldBe true
	}

	private fun boundary(at: Long) = AmbientStepsDemandBoundary(
		bootId = "ambient-steps-lifecycle-test",
		elapsedRealtimeNanos = at,
		wallTimeMs = at,
	)

	private fun unavailable() = AmbientStepsDemandReconciliation.Unavailable(
		healthConnect = HealthConnectAmbientStepsAvailability.SDK_UNAVAILABLE,
		localRecording = LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_MISSING,
	)

	private fun completeCleanup() = AmbientStepsProviderCleanupResult(
		complete = true,
		pendingProviders = emptySet(),
	)
}
