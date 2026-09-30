package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationResult
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsImportAccess
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsPermission
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProvider
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderLifecycleOwner
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderRegistrationResult
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.worker.AmbientStepsImportWorkScheduler
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AmbientStepsPurposeOwnerTest {
	@Test
	fun `production owner threads exact settlement identity into provider lifecycle`() = runTest {
		val lifecycle = mockk<AmbientStepsProviderLifecycleOwner>()
		coEvery {
			lifecycle.reconcileForRetentionFloor(any(), "retention-steps-operation")
		} returns AmbientStepsProviderRegistrationResult.Active(
			provider = AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			registrationGeneration = 4L,
			importAccess = AmbientStepsImportAccess.BACKGROUND_ALLOWED,
			optionalPermissions = emptySet(),
			rearmedInThisProcess = true,
		)
		val lease = AmbientReconciliationLease(
			AmbientReconciliationIdentity.from(
				TrackingPurposeLeaseIdentity(
					sourcePurpose =
						TrackingSource.STEPS.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
					policyRevision = 3L,
					consentEpoch = 5L,
					collectedDataEpoch = 7L,
					rolloutRevision = 11L,
					executionRevision = 1L,
					ownerCasToken = "steps-settlement-owner",
					retainedFromMs = 13L,
				),
				retentionPolicyId = "retention-steps",
				retentionApprovalRevision = 17L,
			),
		)

		DefaultAmbientStepsPurposeOwner(lifecycle, mockk(relaxed = true)).reconcileForRetentionFloor(
			lease,
			"retention-steps-operation",
		) shouldBe AmbientSourceOperationalAvailability.ready(
			AmbientTrackingSource.STEPS,
			AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			lease.purposeLeaseIdentity,
		)
		coVerify(exactly = 1) {
			lifecycle.reconcileForRetentionFloor(lease, "retention-steps-operation")
		}
		coVerify(exactly = 0) { lifecycle.reconcile(any()) }
	}

	@Test
	fun `production owner binds provider readiness to the exact floor lease`() = runTest {
		val lifecycle = mockk<AmbientStepsProviderLifecycleOwner>()
		coEvery { lifecycle.reconcile(any()) } returns
			AmbientStepsProviderRegistrationResult.Active(
				provider = AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				registrationGeneration = 4L,
				importAccess = AmbientStepsImportAccess.BACKGROUND_ALLOWED,
				optionalPermissions = emptySet(),
				rearmedInThisProcess = true,
			)
		coEvery { lifecycle.retireAfterRetentionAuthorityFailure(any()) } returns
			AmbientStepsSettingsReconciliationResult(complete = true, operational = false)
		val lease = AmbientReconciliationLease(
			AmbientReconciliationIdentity.from(
				TrackingPurposeLeaseIdentity(
					sourcePurpose =
						TrackingSource.STEPS.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
					policyRevision = 3L,
					consentEpoch = 5L,
					collectedDataEpoch = 7L,
					rolloutRevision = 11L,
					executionRevision = 1L,
					ownerCasToken = "steps-purpose-owner",
					retainedFromMs = 13L,
				),
				retentionPolicyId = "retention-steps",
				retentionApprovalRevision = 17L,
			),
		)
		val subject = DefaultAmbientStepsPurposeOwner(lifecycle, mockk(relaxed = true))

		subject.reconcile(lease) shouldBe AmbientSourceOperationalAvailability.ready(
			AmbientTrackingSource.STEPS,
			AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			lease.purposeLeaseIdentity,
		)
		subject.retireAfterRetentionAuthorityFailure(lease) shouldBe true
	}

	@Test
	fun `foreground only Health Connect retains collection and offers optional permission repair`() = runTest {
		val lifecycle = mockk<AmbientStepsProviderLifecycleOwner>()
		val scheduler = mockk<AmbientStepsImportWorkScheduler>(relaxed = true)
		coEvery { lifecycle.reconcile(any()) } returns AmbientStepsProviderRegistrationResult.Active(
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS, 4L,
			AmbientStepsImportAccess.FOREGROUND_ONLY,
			setOf(AmbientStepsPermission.HEALTH_CONNECT_BACKGROUND_READ), true,
		)
		val lease = testLease()

		val availability = DefaultAmbientStepsPurposeOwner(lifecycle, scheduler).reconcile(lease)

		availability.state shouldBe AmbientSourceOperationalState.DEGRADED
		availability.reason shouldBe AmbientSourceUnavailableReason.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL
		availability.operationalIdentity shouldBe lease.purposeLeaseIdentity
		coVerify(exactly = 1) { scheduler.reconcile(backgroundAllowed = false, requestNow = true) }
	}

	@Test
	fun `accepted provider schedules one enable copy while ordinary reconciliation preserves cadence`() = runTest {
		val lifecycle = mockk<AmbientStepsProviderLifecycleOwner>()
		val scheduler = mockk<AmbientStepsImportWorkScheduler>(relaxed = true)
		coEvery { lifecycle.reconcile(any()) } returns AmbientStepsProviderRegistrationResult.Active(
			AmbientStepsProvider.LOCAL_RECORDING_STEPS, 4L,
			AmbientStepsImportAccess.BACKGROUND_ALLOWED, emptySet(), false,
		)
		coEvery { lifecycle.retireAfterRetentionAuthorityFailure(any()) } returns
			AmbientStepsSettingsReconciliationResult(complete = true, operational = false)
		val subject = DefaultAmbientStepsPurposeOwner(lifecycle, scheduler)
		val lease = testLease()

		subject.reconcile(lease)
		subject.reconcile(lease)
		subject.retireAfterRetentionAuthorityFailure(lease) shouldBe true
		subject.reconcile(lease)

		coVerify(exactly = 2) { scheduler.reconcile(backgroundAllowed = true, requestNow = true) }
		coVerify(exactly = 1) { scheduler.reconcile(backgroundAllowed = true, requestNow = false) }
		coVerify(exactly = 1) { scheduler.cancel() }
	}

	private fun testLease() = AmbientReconciliationLease(
		AmbientReconciliationIdentity.from(
			TrackingPurposeLeaseIdentity(
				sourcePurpose = TrackingSource.STEPS.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
				policyRevision = 3L, consentEpoch = 5L, collectedDataEpoch = 7L,
				rolloutRevision = 11L, executionRevision = 1L, ownerCasToken = "steps-import-owner",
				retainedFromMs = 13L,
			),
			retentionPolicyId = "retention-steps", retentionApprovalRevision = 17L,
		),
	)
}
