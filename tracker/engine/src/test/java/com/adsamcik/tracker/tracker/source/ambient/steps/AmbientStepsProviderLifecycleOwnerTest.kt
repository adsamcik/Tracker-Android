package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.tracker.api.AmbientStepsProviderCleanupFailure
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AmbientStepsProviderLifecycleOwnerTest {
	@Test
	fun `caller authority rejection prevents provider reconciliation`() = runTest {
		var providerReconciled = false
		val demand = AmbientStepsDemandReconciliation.PolicyBlocked(
			provider = null,
			reason = AmbientStepsDemandBlockReason.CALLER_AUTHORITY_UNAVAILABLE,
		)
		val subject = AmbientStepsProviderLifecycleOwner(
			currentBoundary = { boundary(10L) },
			reconcileDemand = { demand },
			reconcileRegistration = { _, _ ->
				providerReconciled = true
				error("Provider reconciliation must remain closed")
			},
			closeRegistration = { completeCleanup() },
		)

		subject.reconcile() shouldBe AmbientStepsProviderRegistrationResult.Inactive(demand)
		providerReconciled shouldBe false
	}

	@Test
	fun `failed guarded provider reconciliation retires its ambient demand`() = runTest {
		val boundary = boundary(10L)
		val demand = AmbientStepsDemandReconciliation.DemandReady(
			AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			AmbientStepsImportAccess.FOREGROUND_ONLY,
			emptySet(),
			"demand-1",
		)
		var retireCount = 0
		val subject = AmbientStepsProviderLifecycleOwner(
			currentBoundary = { boundary },
			reconcileDemand = { demand },
			reconcileRegistration = { _, _ ->
				AmbientStepsProviderRegistrationResult.Failed(
					AmbientStepsProvider.LOCAL_RECORDING_STEPS,
					AmbientStepsProviderRegistrationFailure.PROVIDER_ACTIVATION_FAILED,
					retryable = true,
				)
			},
			closeRegistration = { completeCleanup() },
			retireDemand = {
				retireCount++
				true
			},
		)

		subject.reconcile().shouldBeInstanceOf<AmbientStepsProviderRegistrationResult.Failed>()
		retireCount shouldBe 1
	}

	@Test
	fun `reconcile uses one exact boundary for demand and registration`() = runTest {
		val boundary = boundary(10L)
		val demand = unavailable()
		var demandBoundary: AmbientStepsDemandBoundary? = null
		var registrationBoundary: AmbientStepsDemandBoundary? = null
		val subject = AmbientStepsProviderLifecycleOwner(
			currentBoundary = { boundary },
			reconcileDemand = { actualBoundary, actualLease ->
				demandBoundary = actualBoundary
				actualLease shouldBe lease()
				demand
			},
			reconcileRegistration = { actualDemand, actualBoundary ->
				actualDemand shouldBe demand
				registrationBoundary = actualBoundary
				AmbientStepsProviderRegistrationResult.Inactive(demand)
			},
			closeRegistration = { completeCleanup() },
		)

		subject.reconcile(lease()) shouldBe AmbientStepsProviderRegistrationResult.Inactive(demand)

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
			reconcileDemand = { _, _ -> demand },
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

		val reconcile = async { subject.reconcile(lease()) }
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
			reconcileDemand = { _, _ -> unavailable() },
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

	@Test
	fun `settings reconciliation exposes typed provider failure`() = runTest {
		val failure = AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED
		val subject = AmbientStepsProviderLifecycleOwner(
			currentBoundary = { boundary(10L) },
			reconcileDemand = { _, _ -> unavailable() },
			reconcileRegistration = { _, _ ->
				AmbientStepsProviderRegistrationResult.Failed(
					selectedProvider = null,
					failure = failure,
					retryable = true,
				)
			},
			closeRegistration = { completeCleanup() },
		)

		subject.reconcile(lease()) shouldBe
			AmbientStepsProviderRegistrationResult.Failed(
				selectedProvider = null,
				failure = AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
				retryable = true,
			)
	}

	@Test
	fun `retention failure explicitly retires demand before provider reconciliation`() = runTest {
		val boundary = boundary(20L)
		val retired = AmbientStepsDemandReconciliation.PolicyBlocked(
			provider = null,
			reason = AmbientStepsDemandBlockReason.RETENTION_POLICY_UNAVAILABLE,
		)
		val events = mutableListOf<String>()
		val subject = AmbientStepsProviderLifecycleOwner(
			currentBoundary = { boundary },
			reconcileDemand = { _, _ -> error("Normal demand reconciliation must not run") },
			retireDemandAfterAuthorityFailure = { actualBoundary, actualLease ->
				events += "retire-demand"
				actualBoundary shouldBe boundary
				actualLease shouldBe lease()
				retired
			},
			reconcileRegistration = { demand, actualBoundary ->
				events += "close-provider"
				demand shouldBe retired
				actualBoundary shouldBe boundary
				AmbientStepsProviderRegistrationResult.Inactive(retired)
			},
			closeRegistration = { completeCleanup() },
		)

		subject.retireAfterRetentionAuthorityFailure(lease()) shouldBe
			com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationResult(
				complete = true,
				operational = false,
			)
		events shouldBe listOf("retire-demand", "close-provider")
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

	private fun lease() = AmbientReconciliationLease(
		AmbientReconciliationIdentity(
			source = AmbientTrackingSource.STEPS,
			policyRevision = 1L,
			consentEpoch = 1L,
			collectedDataEpoch = 0L,
			rolloutRevision = 1L,
			ownerCasToken = "ambient-steps-lifecycle-test",
			executionRevision = 1L,
			retentionPolicyId = "retention",
			retentionApprovalRevision = 1L,
		),
	)
}
